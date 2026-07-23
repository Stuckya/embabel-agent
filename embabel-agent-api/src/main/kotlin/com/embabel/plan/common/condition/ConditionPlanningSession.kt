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

import com.embabel.agent.core.EpisodeOutcome
import com.embabel.plan.ChildMission
import com.embabel.plan.Goal
import com.embabel.plan.GoalChildMission
import com.embabel.plan.NoPlanForOccurrence
import com.embabel.plan.Plan
import com.embabel.plan.PlannerExecution
import com.embabel.plan.PlanningDirective
import com.embabel.plan.PlanningOccurrenceState
import com.embabel.plan.PlanningOccurrenceView
import com.embabel.plan.PlanningSession
import com.embabel.plan.PlanningSessionRequest
import com.embabel.plan.PlanningSystem
import com.embabel.plan.PlanningTurn
import com.embabel.plan.WorldInterest

/**
 * Session implementation shared by condition-based planners.
 *
 * It delegates plan construction, goal ordering, costs, values, and
 * feasibility to the selected planner. Each occurrence is presented as a
 * planner-visible world and the session accepts the planner's result without
 * inspecting its actions or reconstructing why that plan is valid.
 */
internal class ConditionPlanningSession(
    private val planner: ConditionPlanner,
    private val request: PlanningSessionRequest,
    private val missionGoals: (Plan, PlanningSessionRequest) -> Set<Goal>,
) : PlanningSession {

    private val withdrawnRootGoals = mutableSetOf<String>()

    override fun next(turn: PlanningTurn): PlanningDirective {
        turn.outcomes.firstOrNull()?.let { outcome ->
            return when (outcome.outcome) {
                EpisodeOutcome.COMPLETED ->
                    PlanningDirective.CompleteOccurrence(outcome.occurrenceId)

                EpisodeOutcome.STUCK ->
                    PlanningDirective.AwaitOccurrence(
                        occurrenceId = outcome.occurrenceId,
                        interest = WorldInterest.AnyRevision,
                        obstruction = NoPlanForOccurrence,
                    )

                EpisodeOutcome.CANCELLED ->
                    PlanningDirective.CancelOccurrence(outcome.occurrenceId, "Child attempt was cancelled")

                EpisodeOutcome.FAILED -> chooseWork(turn)
            }
        }
        return chooseWork(turn)
    }

    private fun chooseWork(turn: PlanningTurn): PlanningDirective {
        var system = scopedSystem(turn.excludedActionNames)
        var rootPlans = planner.plansToGoals(system)

        while (rootPlans.firstOrNull()?.isComplete() == true) {
            val completed = rootPlans.first()
            if (completed.goal.name in request.rootMission?.goalNames.orEmpty()) {
                return PlanningDirective.CompleteProcess(completed.goal)
            }
            withdrawnRootGoals += completed.goal.name
            system = scopedSystem(turn.excludedActionNames)
            rootPlans = planner.plansToGoals(system)
        }

        val planningSnapshot = planner.snapshot(system)
        val root = rootPlans.firstOrNull()?.let { plan ->
            ValuedRoot(plan, plan.netValue(planner.worldState()))
        }
        val episode = turn.occurrences
            .asSequence()
            .filter { it.state != PlanningOccurrenceState.RUNNING }
            .filter {
                it.state != PlanningOccurrenceState.AWAITING ||
                        it.waitingSinceRevision == null ||
                        it.waitingSinceRevision != turn.revision
            }
            .mapNotNull { candidateFor(it, system, planningSnapshot) }
            .maxByOrNull { it.value }

        if (root != null && (episode == null || root.value > episode.value)) {
            return PlanningDirective.RunRoot(PlannerExecution(root.plan))
        }
        if (episode != null && turn.availableChildCapacity > 0) {
            return PlanningDirective.RunEpisode(
                occurrenceId = episode.episode.id,
                mission = episode.mission,
            )
        }

        val awaiting = turn.occurrences.firstOrNull {
            it.state != PlanningOccurrenceState.RUNNING &&
                    (it.state != PlanningOccurrenceState.AWAITING || it.waitingSinceRevision != turn.revision)
        }
        if (awaiting != null) {
            return PlanningDirective.AwaitOccurrence(
                occurrenceId = awaiting.id,
                interest = WorldInterest.AnyRevision,
                obstruction = NoPlanForOccurrence,
            )
        }
        return PlanningDirective.AwaitProcess
    }

    private fun candidateFor(
        episode: PlanningOccurrenceView,
        system: PlanningSystem,
        planningSnapshot: ConditionPlanningSnapshot,
    ): ValuedEpisode? =
        episode.evaluate {
            val plan = planner.planForOccurrence(
                system = system,
                before = planningSnapshot,
                occurrenceId = episode.id,
                occurrence = episode.occurrence,
            )
                ?: return@evaluate null
            ValuedEpisode(
                episode = episode,
                mission = GoalChildMission(missionGoals(plan, request)),
                value = plan.netValue(planner.worldState()),
            )
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
        val episode: PlanningOccurrenceView,
        val mission: ChildMission,
        val value: Double,
    )

}
