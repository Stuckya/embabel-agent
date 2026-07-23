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
package com.embabel.plan

import com.embabel.agent.core.Agent
import com.embabel.agent.core.EpisodeExecution
import com.embabel.agent.core.GoalTarget
import com.embabel.agent.core.OccurrenceId

/**
 * The declared mission whose completion may complete an evolving root.
 * Null in [PlanningSessionRequest] means that the root is intentionally
 * long-lived.
 */
data class RootMission(
    val goalNames: Set<String>,
)

/**
 * Immutable information supplied when a process opens its planner-owned
 * session.
 */
data class PlanningSessionRequest(
    val planningSystem: PlanningSystem,
    val rootMission: RootMission?,
    val rootObjective: GoalTarget? = null,
)

/**
 * Runtime-owned lifecycle state exposed to the planner. The runtime never
 * decides what this state means for planning.
 */
enum class PlanningOccurrenceState {
    PENDING,
    RUNNING,
    AWAITING,
}

/**
 * One occurrence-backed intention visible to the planner.
 *
 * [evaluate] provides a mechanical visibility window for this occurrence.
 * The selected planner decides when to use that window and what conclusions
 * to draw from it.
 */
interface PlanningOccurrenceView {

    val id: OccurrenceId

    val occurrence: Any

    val state: PlanningOccurrenceState

    val attemptCount: Int

    val waitingSinceRevision: Long?

    fun <T> evaluate(block: () -> T): T
}

data class PlanningTurn(
    val revision: Long,
    val occurrences: List<PlanningOccurrenceView>,
    val outcomes: List<EpisodeExecution>,
    val excludedActionNames: Set<String> = emptySet(),
    val availableChildCapacity: Int = 1,
)

/**
 * A planner-produced mission for a fresh child. Its representation is opaque
 * to the runtime; only the planner-owned materialization operation is visible.
 */
interface ChildMission {

    fun materialize(parent: Agent): Agent
}

class GoalChildMission(
    private val goals: Set<Goal>,
) : ChildMission {

    override fun materialize(parent: Agent): Agent =
        parent.copy(
            goals = goals.mapTo(mutableSetOf()) { goal ->
                goal as? com.embabel.agent.core.Goal
                    ?: error("Child mission goal ${goal.name} is not an agent goal")
            }
        )
}

data class PlannerExecution(
    val plan: Plan,
)

sealed interface WorldInterest {

    data object AnyRevision : WorldInterest
}

interface PlanningObstruction

data object NoPlanForOccurrence : PlanningObstruction

sealed interface PlanningDirective {

    data class RunRoot(
        val execution: PlannerExecution,
    ) : PlanningDirective

    data class RunEpisode(
        val occurrenceId: OccurrenceId,
        val mission: ChildMission,
    ) : PlanningDirective

    data class AwaitOccurrence(
        val occurrenceId: OccurrenceId,
        val interest: WorldInterest,
        val obstruction: PlanningObstruction? = null,
    ) : PlanningDirective

    data class CompleteOccurrence(
        val occurrenceId: OccurrenceId,
    ) : PlanningDirective

    data class CancelOccurrence(
        val occurrenceId: OccurrenceId,
        val reason: String,
    ) : PlanningDirective

    data object AwaitProcess : PlanningDirective

    data class CompleteProcess(
        val goal: Goal,
    ) : PlanningDirective
}

/**
 * Process-lifetime planning conversation. Evolving execution obtains every
 * work-selection and lifecycle decision through this interface.
 */
interface PlanningSession {

    fun next(turn: PlanningTurn): PlanningDirective
}
