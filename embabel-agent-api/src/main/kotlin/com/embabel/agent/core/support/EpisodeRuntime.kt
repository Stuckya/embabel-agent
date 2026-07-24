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

import com.embabel.agent.api.event.EpisodeFinishedEvent
import com.embabel.agent.api.event.EpisodeStartedEvent
import com.embabel.agent.api.event.OccurrenceAcceptedEvent
import com.embabel.agent.api.event.OccurrenceConsumedEvent
import com.embabel.agent.core.Action
import com.embabel.agent.core.ActionStatus
import com.embabel.agent.core.AgentProcessStatusCode
import com.embabel.agent.core.EpisodeExecution
import com.embabel.agent.core.EpisodeId
import com.embabel.agent.core.EpisodeOutcome
import com.embabel.agent.core.EpisodeTrace
import com.embabel.agent.core.OccurrenceId
import com.embabel.plan.PlanningDirective
import com.embabel.plan.PlanningOccurrenceState
import com.embabel.plan.PlanningOccurrenceView
import com.embabel.plan.PlanningSession
import com.embabel.plan.PlanningSessionRequest
import com.embabel.plan.PlanningTurn
import com.embabel.plan.WorldState
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * Mechanical occurrence and child lifecycle for one evolving process.
 *
 * Planning decisions cross exactly one seam: [PlanningSession.next]. This
 * runtime never derives rules, walks goals or actions, selects a goal,
 * scores work, constructs a chain, infers consumables, or diagnoses a
 * blocker.
 */
internal class EpisodeRuntime(
    private val process: SimpleAgentProcess,
    private val setStatus: (AgentProcessStatusCode) -> Unit,
    private val makeRunning: () -> Boolean,
) {

    private val logger = LoggerFactory.getLogger(javaClass)
    private val evolvingDeclaration = process.processOptions.evolving

    init {
        require(evolvingDeclaration == null || !process.processOptions.ephemeral) {
            "An ephemeral process cannot evolve: episodes execute in child processes"
        }
    }

    private val session: PlanningSession? =
        evolvingDeclaration?.let {
            process.planner.openSession(
                PlanningSessionRequest(
                    planningSystem = process.agent.planningSystem,
                    rootMission = null,
                    rootObjective = it.objective,
                )
            )
        }

    private data class AcceptedOccurrence(
        val id: OccurrenceId,
        val occurrence: Any,
        val causedBy: Episode?,
        val publishedBy: String?,
    )

    private val acceptedOccurrences = ConcurrentLinkedQueue<AcceptedOccurrence>()
    private val episodes = linkedMapOf<OccurrenceId, Episode>()
    private val outcomes = ArrayDeque<EpisodeExecution>()
    private val lastExecutionIds = mutableMapOf<OccurrenceId, EpisodeId>()
    private val executor by lazy { EpisodeExecutor(process) }

    private var revision: Long = 0
    private data class ExecutingEpisode(
        val occurrence: Episode,
        val episodeId: EpisodeId,
        var childProcessId: String? = null,
    )

    private val executingEpisode = ThreadLocal<ExecutingEpisode?>()
    private val executingAction = ThreadLocal<String?>()

    val isEvolving: Boolean get() = evolvingDeclaration != null

    var evolveDelegate: ((Any, String?) -> OccurrenceId)? = null

    var shareDelegate: ((Any) -> Unit)? = null

    fun evolve(
        fact: Any,
        publishedBy: String? = executingAction.get(),
    ): OccurrenceId {
        if (!isEvolving) {
            return evolveDelegate?.invoke(fact, publishedBy)
                ?: throw IllegalArgumentException(
                    "evolve requires an evolving process: declare withEvolving() on the process options"
                )
        }
        require(
            process.status !in setOf(
                AgentProcessStatusCode.COMPLETED,
                AgentProcessStatusCode.FAILED,
                AgentProcessStatusCode.KILLED,
                AgentProcessStatusCode.TERMINATED,
            )
        ) {
            "Cannot evolve terminal process ${process.id} in state ${process.status}"
        }

        val occurrenceId = OccurrenceId.create()
        val execution = executingEpisode.get()
        val accepted = AcceptedOccurrence(
            id = occurrenceId,
            occurrence = fact,
            causedBy = execution?.occurrence,
            publishedBy = publishedBy,
        )
        acceptedOccurrences += accepted
        process.processContext.onProcessEvent(
            OccurrenceAcceptedEvent(
                agentProcess = process,
                occurrenceId = occurrenceId,
                occurrence = fact,
                causedBy = accepted.causedBy?.id,
                causedByEpisodeId = execution?.episodeId,
                causedByChildProcessId = execution?.childProcessId,
                publishedBy = publishedBy,
            )
        )
        makeRunning()
        return occurrenceId
    }

    /**
     * Explicit standing-state transfer. Ordinary child writes never cross
     * this seam.
     */
    fun share(fact: Any) {
        if (!isEvolving && shareDelegate != null) {
            shareDelegate?.invoke(fact)
            return
        }
        process.addObject(fact)
    }

    fun onStandingStateChanged() {
        if (!isEvolving) {
            return
        }
        advanceRevision()
        makeRunning()
    }

    fun nextDirective(excludedActionNames: Set<String>): PlanningDirective {
        val planningSession = checkNotNull(session) { "No planning session outside evolving mode" }
        admitAcceptedOccurrences()
        val turn = PlanningTurn(
            revision = revision,
            occurrences = episodes.values.map(::viewOf),
            outcomes = outcomes.toList(),
            excludedActionNames = excludedActionNames,
            availableChildCapacity = 1,
        )
        val directive = planningSession.next(turn)
        outcomes.clear()
        return directive
    }

    fun runEpisode(directive: PlanningDirective.RunEpisode) {
        val episode = requireEpisode(directive.occurrenceId)
        val episodeId = EpisodeId.create()
        episode.run()
        val execution = ExecutingEpisode(episode, episodeId)
        executingEpisode.set(execution)
        val dispatch = try {
            executor.execute(episode, directive.mission) { child ->
                execution.childProcessId = child.id
                process.processContext.onProcessEvent(
                    EpisodeStartedEvent(
                        agentProcess = process,
                        occurrenceId = episode.id,
                        episodeId = episodeId,
                        childProcessId = child.id,
                    )
                )
            }
        } finally {
            executingEpisode.remove()
        }
        val child = dispatch.child
        val outcome = dispatch.outcome
        val completedExecution = if (child != null) {
            val terminalOutcome = when (outcome) {
                DispatchOutcome.COMPLETED -> EpisodeOutcome.COMPLETED
                DispatchOutcome.STUCK -> EpisodeOutcome.STUCK
                DispatchOutcome.FAILED -> EpisodeOutcome.FAILED
                DispatchOutcome.CANCELLED -> EpisodeOutcome.CANCELLED
                DispatchOutcome.BUDGET_EXHAUSTED -> null
            }
            if (terminalOutcome != null) {
                val completed = EpisodeExecution(
                    id = episodeId,
                    occurrenceId = episode.id,
                    childProcessId = child.id,
                    outcome = terminalOutcome,
                    trace = EpisodeTrace(child.history.toList()),
                )
                lastExecutionIds[episode.id] = episodeId
                process.processContext.onProcessEvent(
                    EpisodeFinishedEvent(
                        agentProcess = process,
                        execution = completed,
                    )
                )
                completed
            } else {
                null
            }
        } else {
            null
        }
        when (outcome) {
            DispatchOutcome.COMPLETED,
            DispatchOutcome.STUCK,
                -> Unit

            DispatchOutcome.FAILED -> {
                episode.retry()
            }

            DispatchOutcome.CANCELLED -> Unit

            DispatchOutcome.BUDGET_EXHAUSTED -> {
                setStatus(AgentProcessStatusCode.TERMINATED)
                return
            }
        }
        completedExecution?.let(outcomes::addLast)
        advanceRevision()
        makeRunning()
    }

    fun awaitOccurrence(directive: PlanningDirective.AwaitOccurrence) {
        requireEpisode(directive.occurrenceId).await(revision)
        makeRunning()
    }

    fun completeOccurrence(directive: PlanningDirective.CompleteOccurrence) {
        val episode = requireEpisode(directive.occurrenceId)
        episode.complete()
        episodes.remove(episode.id)
        val completedExecutionId = lastExecutionIds.remove(episode.id)
        if (completedExecutionId != null) {
            process.processContext.onProcessEvent(
                OccurrenceConsumedEvent(
                    agentProcess = process,
                    occurrenceId = episode.id,
                    episodeId = completedExecutionId,
                )
            )
        }
        advanceRevision()
    }

    fun cancelOccurrence(directive: PlanningDirective.CancelOccurrence) {
        val episode = requireEpisode(directive.occurrenceId)
        episode.cancel()
        episodes.remove(episode.id)
        lastExecutionIds.remove(episode.id)
        advanceRevision()
        makeRunning()
    }

    fun reportAbandonedOccurrences() {
        if (episodes.isNotEmpty()) {
            logger.info(
                "Process {} finished with {} active occurrence(s)",
                process.id,
                episodes.size,
            )
        }
    }

    fun runTrackingPublisher(action: Action, execute: () -> ActionStatus): ActionStatus {
        executingAction.set(action.name)
        try {
            return execute()
        } finally {
            executingAction.remove()
        }
    }

    private fun viewOf(episode: Episode): PlanningOccurrenceView =
        object : PlanningOccurrenceView {
            override val id = episode.id
            override val occurrence = episode.request
            override val state = when (episode.state) {
                EpisodeState.PENDING -> PlanningOccurrenceState.PENDING
                EpisodeState.RUNNING -> PlanningOccurrenceState.RUNNING
                EpisodeState.STUCK -> PlanningOccurrenceState.AWAITING
                EpisodeState.COMPLETED,
                EpisodeState.CANCELLED,
                    -> error("Terminal episode ${episode.id} remained active")
            }
            override val attemptCount = episode.attemptCount
            override val waitingSinceRevision = episode.waitingSinceRevision

            override fun <T> evaluate(block: () -> T): T =
                process.blackboard.withTransientObject(episode.request, block)
        }

    private fun admitAcceptedOccurrences() {
        var admitted = false
        while (true) {
            val accepted = acceptedOccurrences.poll() ?: break
            episodes[accepted.id] = Episode(
                id = accepted.id,
                request = accepted.occurrence,
                causedBy = accepted.causedBy,
                publishedBy = accepted.publishedBy,
            )
            admitted = true
        }
        if (admitted) {
            advanceRevision()
        }
    }

    private fun requireEpisode(id: OccurrenceId): Episode =
        requireNotNull(episodes[id]) { "Planner directive referenced inactive episode $id" }

    private fun advanceRevision() {
        revision++
    }

}
