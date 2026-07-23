/*
 * Copyright 2024-2026 Embabel Pty Ltd.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.embabel.plan.common.condition

import com.embabel.plan.ChildMission
import com.embabel.plan.ExecutionOutcomeCode
import com.embabel.plan.Goal
import com.embabel.plan.GoalChildMission
import com.embabel.plan.NoPlanForOccurrence
import com.embabel.plan.Plan
import com.embabel.plan.PlannerExecution
import com.embabel.plan.PlanningDirective
import com.embabel.plan.PlanningEpisodeState
import com.embabel.plan.PlanningEpisodeView
import com.embabel.plan.PlanningSession
import com.embabel.plan.PlanningSessionRequest
import com.embabel.plan.PlanningSystem
import com.embabel.plan.PlanningTurn
import com.embabel.plan.WorldInterest

/**
 * Session implementation shared by condition-based planners.
 *
 * It delegates plan construction, goal ordering, costs, values, and
 * feasibility to the selected planner. Its only occurrence-specific
 * reasoning is native condition-planner grounding: a plan is a candidate
 * for an occurrence when one of its own action preconditions explicitly
 * accepts that occurrence's runtime type.
 */
internal class ConditionPlanningSession(
    private val planner: ConditionPlanner,
    private val request: PlanningSessionRequest,
    private val missionGoals: (Plan, PlanningSessionRequest) -> Set<Goal>,
) : PlanningSession {

    private val withdrawnRootGoals = mutableSetOf<String>()

    override fun next(turn: PlanningTurn): PlanningDirective {
        turn.outcomes.firstOrNull()?.let { outcome ->
            return when (outcome.code) {
                ExecutionOutcomeCode.COMPLETED ->
                    PlanningDirective.CompleteEpisode(outcome.episodeId)

                ExecutionOutcomeCode.STUCK ->
                    PlanningDirective.AwaitEpisode(
                        episodeId = outcome.episodeId,
                        interest = WorldInterest.AnyRevision,
                        obstruction = NoPlanForOccurrence,
                    )

                ExecutionOutcomeCode.CANCELLED ->
                    PlanningDirective.CancelEpisode(outcome.episodeId, "Child attempt was cancelled")

                ExecutionOutcomeCode.FAILED -> chooseWork(turn)
            }
        }
        return chooseWork(turn)
    }

    private fun chooseWork(turn: PlanningTurn): PlanningDirective {
        val system = scopedSystem(turn.excludedActionNames)
        var rootPlans = planner.plansToGoals(system)

        while (rootPlans.firstOrNull()?.isComplete() == true) {
            val completed = rootPlans.first()
            if (completed.goal.name in request.rootMission?.goalNames.orEmpty()) {
                return PlanningDirective.CompleteProcess(completed.goal)
            }
            withdrawnRootGoals += completed.goal.name
            rootPlans = planner.plansToGoals(scopedSystem(turn.excludedActionNames))
        }

        val root = rootPlans.firstOrNull()?.let { plan ->
            ValuedRoot(plan, plan.netValue(planner.worldState()))
        }
        val episode = turn.episodes
            .asSequence()
            .filter { it.state != PlanningEpisodeState.RUNNING }
            .filter {
                it.state != PlanningEpisodeState.STUCK ||
                        it.waitingSinceRevision == null ||
                        it.waitingSinceRevision != turn.revision
            }
            .mapNotNull { candidateFor(it, system) }
            .maxByOrNull { it.value }

        if (root != null && (episode == null || root.value > episode.value)) {
            return PlanningDirective.RunRoot(PlannerExecution(root.plan))
        }
        if (episode != null && turn.availableChildCapacity > 0) {
            return PlanningDirective.RunEpisode(
                episodeId = episode.episode.id,
                mission = episode.mission,
            )
        }

        val awaiting = turn.episodes.firstOrNull {
            it.state != PlanningEpisodeState.RUNNING &&
                    (it.state != PlanningEpisodeState.STUCK || it.waitingSinceRevision != turn.revision)
        }
        if (awaiting != null) {
            return PlanningDirective.AwaitEpisode(
                episodeId = awaiting.id,
                interest = WorldInterest.AnyRevision,
                obstruction = NoPlanForOccurrence,
            )
        }
        return PlanningDirective.AwaitProcess
    }

    private fun candidateFor(
        episode: PlanningEpisodeView,
        system: PlanningSystem,
    ): ValuedEpisode? =
        episode.evaluate {
            val plan = planner.plansToGoals(system)
                .firstOrNull { it.actions.isNotEmpty() && acceptsOccurrence(it, episode.occurrence) }
                ?: return@evaluate null
            ValuedEpisode(
                episode = episode,
                mission = GoalChildMission(missionGoals(plan, request)),
                value = plan.netValue(planner.worldState()),
            )
        }

    /**
     * Condition-planner-native occurrence grounding. This is deliberately
     * inside the planner session: the evolving runtime never walks actions,
     * parses conditions, or identifies a producer/consumer chain.
     */
    private fun acceptsOccurrence(plan: Plan, occurrence: Any): Boolean =
        plan.actions
            .filterIsInstance<ConditionAction>()
            .flatMap { action -> action.preconditions.keys }
            .filter { it.startsWith(INPUT_CONDITION_PREFIX) }
            .map { it.removePrefix(INPUT_CONDITION_PREFIX) }
            .any { acceptsType(it, occurrence) }

    private fun acceptsType(typeName: String, occurrence: Any): Boolean {
        if (typeName == occurrence.javaClass.name || typeName == occurrence.javaClass.simpleName) {
            return true
        }
        return runCatching {
            Class.forName(typeName, false, occurrence.javaClass.classLoader).isInstance(occurrence)
        }.getOrDefault(false)
    }

    private fun scopedSystem(excludedActionNames: Set<String>): PlanningSystem {
        val declared = request.planningSystem
        if (excludedActionNames.isEmpty() && withdrawnRootGoals.isEmpty()) {
            return declared
        }
        return object : PlanningSystem {
            override val actions = declared.actions.filterNot { it.name in excludedActionNames }.toSet()
            override val goals = declared.goals.filterNot { it.name in withdrawnRootGoals }.toSet()
            override fun knownConditions() = declared.knownConditions()
            override fun infoString(verbose: Boolean?, indent: Int) = declared.infoString(verbose, indent)
        }
    }

    private data class ValuedRoot(
        val plan: Plan,
        val value: Double,
    )

    private data class ValuedEpisode(
        val episode: PlanningEpisodeView,
        val mission: ChildMission,
        val value: Double,
    )

    companion object {
        private const val INPUT_CONDITION_PREFIX = "it:"
    }
}
