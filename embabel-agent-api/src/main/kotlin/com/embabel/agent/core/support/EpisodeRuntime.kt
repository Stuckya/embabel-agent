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

import com.embabel.agent.api.event.EpisodeCompletedEvent
import com.embabel.agent.api.event.OccurrenceAcceptedEvent
import com.embabel.agent.core.Action
import com.embabel.agent.core.ActionStatus
import com.embabel.agent.core.AgentProcess
import com.embabel.agent.core.AgentProcessStatusCode
import com.embabel.agent.core.GoalTarget
import com.embabel.agent.core.OccurrenceId
import com.embabel.plan.ExecutionOutcome
import com.embabel.plan.ExecutionOutcomeCode
import com.embabel.plan.PlanningDirective
import com.embabel.plan.PlanningEpisodeState
import com.embabel.plan.PlanningEpisodeView
import com.embabel.plan.PlanningSession
import com.embabel.plan.PlanningSessionRequest
import com.embabel.plan.PlanningTurn
import com.embabel.plan.RootMission
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

    private val objectiveGoals: Set<String> =
        evolvingDeclaration?.let { resolveObjective(it.objective) }.orEmpty()

    private val session: PlanningSession? =
        evolvingDeclaration?.let {
            process.planner.openSession(
                PlanningSessionRequest(
                    planningSystem = process.agent.planningSystem,
                    rootMission = objectiveGoals.takeIf(Set<String>::isNotEmpty)?.let(::RootMission),
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
    private val outcomes = ArrayDeque<ExecutionOutcome>()
    private val missions = mutableMapOf<OccurrenceId, com.embabel.plan.ChildMission>()
    private val executor by lazy { EpisodeExecutor(process) }

    private var revision: Long = 0
    private val executingEpisode = ThreadLocal<Episode?>()
    private val executingAction = ThreadLocal<String?>()

    var lastCompletedEpisode: Episode? = null
        private set

    val frameworkChildren: List<AgentProcess> get() = executor.recentChildrenView

    val frameworkChildCount: Int get() = executor.childCount

    val retainedArrivalBookkeeping: Int
        get() = acceptedOccurrences.size + episodes.size + outcomes.size + missions.size

    fun activeEpisode(id: OccurrenceId): Episode? = episodes[id]

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
        val accepted = AcceptedOccurrence(
            id = occurrenceId,
            occurrence = fact,
            causedBy = executingEpisode.get(),
            publishedBy = publishedBy,
        )
        acceptedOccurrences += accepted
        process.processContext.onProcessEvent(
            OccurrenceAcceptedEvent(
                agentProcess = process,
                occurrenceId = occurrenceId,
                occurrence = fact,
                causedBy = accepted.causedBy?.id,
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
            episodes = episodes.values.map(::viewOf),
            outcomes = outcomes.toList(),
            excludedActionNames = excludedActionNames,
            availableChildCapacity = 1,
        )
        val directive = planningSession.next(turn)
        outcomes.clear()
        return directive
    }

    fun runEpisode(directive: PlanningDirective.RunEpisode) {
        val episode = requireEpisode(directive.episodeId)
        episode.run()
        missions[episode.id] = directive.mission
        executingEpisode.set(episode)
        val outcome = try {
            executor.execute(episode, directive.mission)
        } finally {
            executingEpisode.remove()
        }
        when (outcome) {
            DispatchOutcome.COMPLETED ->
                outcomes += ExecutionOutcome(episode.id, ExecutionOutcomeCode.COMPLETED)

            DispatchOutcome.STUCK ->
                outcomes += ExecutionOutcome(episode.id, ExecutionOutcomeCode.STUCK)

            DispatchOutcome.FAILED -> {
                episode.retry()
                outcomes += ExecutionOutcome(episode.id, ExecutionOutcomeCode.FAILED)
            }

            DispatchOutcome.CANCELLED ->
                outcomes += ExecutionOutcome(episode.id, ExecutionOutcomeCode.CANCELLED)

            DispatchOutcome.BUDGET_EXHAUSTED -> {
                setStatus(AgentProcessStatusCode.TERMINATED)
                return
            }
        }
        advanceRevision()
        makeRunning()
    }

    fun awaitEpisode(directive: PlanningDirective.AwaitEpisode) {
        requireEpisode(directive.episodeId).await(revision)
        makeRunning()
    }

    fun completeEpisode(
        directive: PlanningDirective.CompleteEpisode,
        worldState: WorldState,
    ) {
        val episode = requireEpisode(directive.episodeId)
        episode.complete()
        episodes.remove(episode.id)
        val mission = missions.remove(episode.id)
        lastCompletedEpisode = episode
        val goal = mission?.goals?.firstOrNull { it.name != NIRVANA.name }
            ?: mission?.goals?.firstOrNull()
        if (goal != null && makeRunning()) {
            process.processContext.onProcessEvent(
                EpisodeCompletedEvent(
                    agentProcess = process,
                    worldState = worldState,
                    goal = goal,
                )
            )
        }
        advanceRevision()
    }

    fun cancelEpisode(directive: PlanningDirective.CancelEpisode) {
        val episode = requireEpisode(directive.episodeId)
        episode.cancel()
        episodes.remove(episode.id)
        missions.remove(episode.id)
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

    private fun viewOf(episode: Episode): PlanningEpisodeView =
        object : PlanningEpisodeView {
            override val id = episode.id
            override val occurrence = episode.request
            override val state = when (episode.state) {
                EpisodeState.PENDING -> PlanningEpisodeState.PENDING
                EpisodeState.RUNNING -> PlanningEpisodeState.RUNNING
                EpisodeState.STUCK -> PlanningEpisodeState.STUCK
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

    /**
     * Resolve only an explicit declaration. This does not inspect action
     * preconditions, effects, producers, consumers, or reachability.
     */
    private fun resolveObjective(target: GoalTarget?): Set<String> {
        if (target == null) {
            return emptySet()
        }
        val candidates = process.agent.goals.filter { goal ->
            when (target) {
                is GoalTarget.Named -> goal.name == target.goalName
                is GoalTarget.Output -> goal.outputType?.isAssignableTo(target.satisfiedByType) == true
            }
        }
        require(candidates.isNotEmpty()) {
            "Evolving objective $target resolves to no declared goal in scope. " +
                    "Available goals: ${process.agent.goals.joinToString { it.name }.ifEmpty { "none" }}"
        }
        if (target is GoalTarget.Named) {
            require(candidates.size == 1) {
                "Evolving objective $target resolves to ${candidates.size} declared goals; " +
                        "a named target must identify exactly one"
            }
        }
        return candidates.mapTo(mutableSetOf()) { it.name }
    }
}
