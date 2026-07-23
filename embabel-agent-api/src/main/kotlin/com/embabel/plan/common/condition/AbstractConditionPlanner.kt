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

import com.embabel.agent.core.OccurrenceId
import com.embabel.agent.core.Goal as AgentGoal
import com.embabel.agent.core.GoalTarget
import com.embabel.plan.Goal
import com.embabel.plan.Plan
import com.embabel.plan.PlanningSystem
import com.embabel.plan.PlanningSession
import com.embabel.plan.PlanningSessionRequest
import com.embabel.plan.RootMission
import org.slf4j.Logger
import org.slf4j.LoggerFactory

abstract class AbstractConditionPlanner(
    protected val worldStateDeterminer: WorldStateDeterminer,
) : ConditionPlanner {

    protected val logger: Logger = LoggerFactory.getLogger(javaClass)

    final override fun worldState(): ConditionWorldState {
        return worldStateDeterminer.determineWorldState()
    }

    final override fun openSession(request: PlanningSessionRequest): PlanningSession {
        val resolvedRequest = request.copy(
            rootMission = request.rootMission
                ?: request.rootObjective?.let { resolveRootMission(request.planningSystem, it) },
        )
        return ConditionPlanningSession(
            planner = this,
            request = resolvedRequest,
            missionGoals = ::episodeMissionGoals,
        )
    }

    final override fun snapshot(system: PlanningSystem): ConditionPlanningSnapshot =
        ConditionPlanningSnapshot(
            planIdentities = plansToGoals(system).mapTo(mutableSetOf()) { plan ->
                plan.identity()
            },
        )

    final override fun planForOccurrence(
        system: PlanningSystem,
        before: ConditionPlanningSnapshot,
        occurrenceId: OccurrenceId,
        occurrence: Any,
    ): ConditionPlan? =
        plansToGoals(system)
            .firstOrNull { plan ->
                !plan.isComplete() &&
                        plan.identity(occurrenceId, occurrence) !in before.planIdentities
            }

    /**
     * Planner-owned construction of a child mission. GOAP's natural mission
     * is the goal of its selected plan; value-walking planners can widen
     * this in their own implementations.
     */
    protected open fun episodeMissionGoals(
        plan: Plan,
        request: PlanningSessionRequest,
    ): Set<Goal> = setOf(plan.goal)

    private fun ConditionPlan.identity(
        occurrenceId: OccurrenceId? = null,
        occurrence: Any? = null,
    ): ConditionPlanIdentity {
        val supportingConditions = actions
            .filterIsInstance<ConditionAction>()
            .flatMap { action -> action.preconditions.keys }
            .distinct()
        return ConditionPlanIdentity(
            goalName = goal.name,
            actionNames = actions.map { it.name },
            supportingEvidence = supportingConditions.associateWith { condition ->
                val evidence = worldStateDeterminer.determineEvidence(condition)
                if (
                    occurrenceId != null &&
                    evidence is BindingEvidence &&
                    evidence.value === occurrence
                ) {
                    OccurrenceBindingEvidence(occurrenceId)
                } else {
                    evidence
                }
            },
        )
    }

    /**
     * Resolve an explicit API declaration inside the planner boundary. This
     * selects only declared goals and never constructs a path or synthesizes
     * a goal.
     */
    private fun resolveRootMission(
        system: PlanningSystem,
        target: GoalTarget,
    ): RootMission {
        val candidates = system.goals.filter { goal ->
            when (target) {
                is GoalTarget.Named -> goal.name == target.goalName
                is GoalTarget.Output ->
                    (goal as? AgentGoal)
                        ?.outputType
                        ?.isAssignableTo(target.satisfiedByType) == true
            }
        }
        require(candidates.isNotEmpty()) {
            "Evolving objective $target resolves to no declared goal in scope. " +
                    "Available goals: ${system.goals.joinToString { it.name }.ifEmpty { "none" }}"
        }
        if (target is GoalTarget.Named) {
            require(candidates.size == 1) {
                "Evolving objective $target resolves to ${candidates.size} declared goals; " +
                        "a named target must identify exactly one"
            }
        }
        return RootMission(candidates.mapTo(mutableSetOf()) { it.name })
    }

    private data class OccurrenceBindingEvidence(
        val occurrenceId: OccurrenceId,
    )
}
