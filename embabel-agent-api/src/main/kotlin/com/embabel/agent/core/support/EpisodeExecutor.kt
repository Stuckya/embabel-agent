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

/** An observed attempt result. Policy belongs to the planner session. */
internal enum class DispatchOutcome {
    COMPLETED,
    STUCK,
    FAILED,
    CANCELLED,
    BUDGET_EXHAUSTED,
}

internal data class EpisodeDispatch(
    val child: AgentProcess?,
    val outcome: DispatchOutcome,
)

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
    private var childCount = 0

    fun execute(
        episode: Episode,
        mission: ChildMission,
        onChildCreated: (AgentProcess) -> Unit,
    ): EpisodeDispatch {
        if (childCount >= process.processOptions.budget.actions) {
            logger.warn(
                "Process {} reached its action budget ({}) dispatching episode children",
                process.id,
                process.processOptions.budget.actions,
            )
            return EpisodeDispatch(null, DispatchOutcome.BUDGET_EXHAUSTED)
        }

        // The mission is planner-owned and opaque here. Its materialization
        // operation supplies the complete child agent without exposing goals,
        // actions, costs, or dependencies to the runtime.
        val childAgent = mission.materialize(process.agent)
        val child = process.processContext.platformServices.agentPlatform.createChildProcess(
            childAgent,
            process,
            process.processOptions.copy(evolving = null),
        )

        // Hidden ledger entries are omitted from child snapshots. Insert the
        // planner-selected occurrence exactly once and last, so ordinary
        // blackboard binding receives that exact identity.
        child.addObject(episode.request)
        childCount++
        onChildCreated(child)

        val result = runCatching { child.run() }.getOrElse { failure ->
            logger.warn(
                "Process {} child attempt for occurrence {} failed",
                process.id,
                episode.id,
                failure,
            )
            return EpisodeDispatch(child, DispatchOutcome.FAILED)
        }
        val outcome = when (result.status) {
            AgentProcessStatusCode.COMPLETED -> DispatchOutcome.COMPLETED
            AgentProcessStatusCode.STUCK,
            AgentProcessStatusCode.TERMINATED,
                -> DispatchOutcome.STUCK

            AgentProcessStatusCode.KILLED -> DispatchOutcome.CANCELLED

            AgentProcessStatusCode.FAILED -> DispatchOutcome.FAILED
            else -> DispatchOutcome.STUCK
        }
        return EpisodeDispatch(child, outcome)
    }
}
