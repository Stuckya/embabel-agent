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
package com.embabel.agent.core.support

import com.embabel.agent.core.AgentProcess
import com.embabel.agent.core.AgentProcessStatusCode
import com.embabel.plan.ChildMission
import org.slf4j.LoggerFactory

/** Bounded inspection window for framework-dispatched children. */
private const val RETAINED_CHILDREN = 32

/** An observed attempt result. Policy belongs to the planner session. */
internal enum class DispatchOutcome {
    COMPLETED,
    STUCK,
    FAILED,
    CANCELLED,
    BUDGET_EXHAUSTED,
}

/**
 * Mechanical child-process execution for a planner-issued mission.
 *
 * This type does not select a goal, derive or filter actions, infer
 * consumables, merge child state, or implement retry policy.
 */
internal class EpisodeExecutor(
    private val process: SimpleAgentProcess,
) {

    private val logger = LoggerFactory.getLogger(javaClass)
    private val recentChildren = ArrayDeque<AgentProcess>()

    var childCount = 0
        private set

    val recentChildrenView: List<AgentProcess> get() = recentChildren.toList()

    fun execute(
        episode: Episode,
        mission: ChildMission,
    ): DispatchOutcome {
        if (childCount >= process.processOptions.budget.actions) {
            logger.warn(
                "Process {} reached its action budget ({}) dispatching episode children",
                process.id,
                process.processOptions.budget.actions,
            )
            return DispatchOutcome.BUDGET_EXHAUSTED
        }

        // The planner supplies the goals. The action scope remains intact:
        // the runtime never synthesizes a chain-shaped agent.
        val childAgent = process.agent.copy(
            goals = mission.goals.mapTo(mutableSetOf()) { goal ->
                goal as? com.embabel.agent.core.Goal
                    ?: error("Child mission goal ${goal.name} is not an agent goal")
            }
        )
        val child = process.processContext.platformServices.agentPlatform.createChildProcess(
            childAgent,
            process,
            process.processOptions.copy(evolving = null),
        )

        // Hidden ledger entries are omitted from child snapshots. Insert the
        // planner-selected occurrence exactly once and last, so ordinary
        // blackboard binding receives that exact identity.
        child.addObject(episode.request)
        recordChild(child)

        val result = runCatching { child.run() }.getOrElse { failure ->
            logger.warn(
                "Process {} child attempt for occurrence {} failed",
                process.id,
                episode.id,
                failure,
            )
            return DispatchOutcome.FAILED
        }
        return when (result.status) {
            AgentProcessStatusCode.COMPLETED -> DispatchOutcome.COMPLETED
            AgentProcessStatusCode.STUCK,
            AgentProcessStatusCode.TERMINATED,
                -> DispatchOutcome.STUCK

            AgentProcessStatusCode.KILLED -> DispatchOutcome.CANCELLED

            AgentProcessStatusCode.FAILED -> DispatchOutcome.FAILED
            else -> DispatchOutcome.STUCK
        }
    }

    private fun recordChild(child: AgentProcess) {
        recentChildren.addLast(child)
        if (recentChildren.size > RETAINED_CHILDREN) {
            recentChildren.removeFirst()
        }
        childCount++
    }
}
