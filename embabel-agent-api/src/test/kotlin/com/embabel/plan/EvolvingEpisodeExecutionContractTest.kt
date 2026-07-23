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

import com.embabel.agent.api.annotation.AchievesGoal
import com.embabel.agent.api.annotation.Action
import com.embabel.agent.api.annotation.Agent
import com.embabel.agent.api.annotation.Condition
import com.embabel.agent.api.annotation.support.AgentMetadataReader
import com.embabel.agent.api.common.ActionContext
import com.embabel.agent.api.common.PlannerType
import com.embabel.agent.api.event.AgentProcessEvent
import com.embabel.agent.api.event.AgenticEventListener
import com.embabel.agent.api.event.EpisodeFinishedEvent
import com.embabel.agent.api.event.EpisodeStartedEvent
import com.embabel.agent.api.event.OccurrenceAcceptedEvent
import com.embabel.agent.api.event.OccurrenceConsumedEvent
import com.embabel.agent.core.Agent as CoreAgent
import com.embabel.agent.core.AgentProcessStatusCode
import com.embabel.agent.core.EpisodeOutcome
import com.embabel.agent.core.ProcessOptions
import com.embabel.agent.core.support.InMemoryBlackboard
import com.embabel.agent.core.support.NIRVANA
import com.embabel.agent.core.support.SimpleAgentProcess
import com.embabel.agent.spi.PlannerFactory
import com.embabel.agent.spi.support.DefaultPlannerFactory
import com.embabel.agent.test.integration.IntegrationTestUtils.dummyPlatformServices
import org.junit.jupiter.api.Test
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

data class EpisodeWorkRequested(val id: String)
data class EpisodeWorkPrepared(val id: String)
data class EpisodeWorkPermit(val id: String)
data class EpisodeWorkCompleted(val id: String)
data class ConditionSelectedRequest(val id: String)
data class ConditionSelectedMarker(val id: String)
data class ConditionSelectedResult(val id: String)
data class OpaqueMissionRequest(val id: String)
data class OpaqueMissionResult(val id: String)
data class EvolutionSeed(val id: String)
data class ContextEvolvedRequest(val id: String)
data class EvolutionStarted(val id: String)
data class EvolutionHandled(val id: String)
data class ParentEvolutionRequest(val id: String)
data class ChildEvolutionRequest(val id: String)
data class ParentEvolutionHandled(val id: String)
data class ChildEvolutionHandled(val id: String)

class EvolvingEpisodeExecutionContractTest {

    @Agent(description = "An episode that can start before its permit exists")
    inner class PermitGatedAgent {

        @Action
        fun prepare(request: EpisodeWorkRequested): EpisodeWorkPrepared =
            EpisodeWorkPrepared(request.id)

        @Action(canRerun = true)
        @AchievesGoal(description = "Episode work completed")
        fun finish(
            prepared: EpisodeWorkPrepared,
            permit: EpisodeWorkPermit,
        ): EpisodeWorkCompleted = EpisodeWorkCompleted(prepared.id)
    }

    @Agent(description = "An occurrence selected through the planner's native condition model")
    inner class ConditionSelectedAgent {

        @Action
        fun registerRequestType(request: ConditionSelectedRequest): ConditionSelectedMarker =
            ConditionSelectedMarker(request.id)

        @Condition(name = "conditionSelectedWorkRequested")
        fun workRequested(request: ConditionSelectedRequest): Boolean = request.id.isNotBlank()

        @Action(pre = ["conditionSelectedWorkRequested"])
        @AchievesGoal(description = "Condition-selected work completed")
        fun complete(): ConditionSelectedResult = ConditionSelectedResult("completed")
    }

    @Agent(description = "A child launched from a planner-owned opaque mission")
    inner class OpaqueMissionAgent {

        @Action
        @AchievesGoal(description = "Opaque mission completed")
        fun handle(request: OpaqueMissionRequest): OpaqueMissionResult =
            OpaqueMissionResult(request.id)
    }

    @Agent(description = "Internal and external evolution share one ingress contract")
    inner class UnifiedEvolutionAgent {

        @Action
        @AchievesGoal(description = "Internal evolution started")
        fun start(seed: EvolutionSeed, context: ActionContext): EvolutionStarted {
            context.evolve(ContextEvolvedRequest("internal-${seed.id}"))
            return EvolutionStarted(seed.id)
        }

        @Action(canRerun = true)
        @AchievesGoal(description = "Evolved request handled")
        fun handle(request: ContextEvolvedRequest): EvolutionHandled =
            EvolutionHandled(request.id)
    }

    @Agent(description = "An episode that evolves a causally linked follow-up")
    inner class ChildEvolutionAgent {

        @Action(canRerun = true)
        @AchievesGoal(description = "Parent evolution handled")
        fun handleParent(
            request: ParentEvolutionRequest,
            context: ActionContext,
        ): ParentEvolutionHandled {
            context.evolve(ChildEvolutionRequest("child-${request.id}"))
            return ParentEvolutionHandled(request.id)
        }

        @Action(canRerun = true)
        @AchievesGoal(description = "Child evolution handled")
        fun handleChild(request: ChildEvolutionRequest): ChildEvolutionHandled =
            ChildEvolutionHandled(request.id)
    }

    private data object WholeAgentMission : ChildMission {

        override fun materialize(parent: CoreAgent): CoreAgent = parent
    }

    private class MissionOnlyPlanner : Planner<PlanningSystem, WorldState, Plan> {

        override fun openSession(request: PlanningSessionRequest): PlanningSession =
            object : PlanningSession {
                override fun next(turn: PlanningTurn): PlanningDirective {
                    turn.outcomes.firstOrNull()?.let {
                        return when (it.outcome) {
                            EpisodeOutcome.COMPLETED ->
                                PlanningDirective.CompleteOccurrence(it.occurrenceId)

                            EpisodeOutcome.STUCK,
                            EpisodeOutcome.FAILED,
                            EpisodeOutcome.CANCELLED,
                                -> PlanningDirective.AwaitProcess
                        }
                    }
                    return turn.occurrences.firstOrNull()?.let {
                        PlanningDirective.RunEpisode(it.id, WholeAgentMission)
                    } ?: PlanningDirective.AwaitProcess
                }
            }

        override fun worldState(): WorldState = error("Ordinary planning is delegated to the child")

        override fun planToGoal(
            actions: Collection<com.embabel.plan.Action>,
            goal: Goal,
        ): Plan? = error("Ordinary planning is delegated to the child")

        override fun prune(planningSystem: PlanningSystem): PlanningSystem =
            error("Ordinary planning is delegated to the child")
    }

    @Test
    fun `a stuck child is a terminal episode while its occurrence remains available`() {
        val events = mutableListOf<AgentProcessEvent>()
        val process = evolvingProcess(events)
        val occurrenceId = process.evolve(EpisodeWorkRequested("job-1"))

        val result = process.run()

        assertEquals(AgentProcessStatusCode.STUCK, result.status)
        val finished = events.filterIsInstance<EpisodeFinishedEvent>()
            .single { it.agentProcess.id == process.id }
        val execution = finished.execution
        assertEquals(occurrenceId, execution.occurrenceId)
        assertNotEquals(occurrenceId.value, execution.id.value)
        assertEquals(EpisodeOutcome.STUCK, execution.outcome)
        assertTrue(execution.childProcessId.isNotBlank())
        assertEquals(
            listOf("prepare"),
            execution.trace.actions.map { it.actionName.substringAfterLast('.') },
        )
        assertTrue(
            events.filterIsInstance<OccurrenceConsumedEvent>()
                .none { it.agentProcess.id == process.id && it.occurrenceId == occurrenceId },
            "A STUCK episode is terminal evidence; it does not consume its occurrence",
        )
    }

    @Test
    fun `a retained occurrence runs again in a fresh child episode after the world changes`() {
        val events = mutableListOf<AgentProcessEvent>()
        val process = evolvingProcess(events)
        val occurrenceId = process.evolve(EpisodeWorkRequested("job-1"))
        process.run()

        process.addObject(EpisodeWorkPermit("permit-1"))
        val result = process.run()

        assertEquals(AgentProcessStatusCode.STUCK, result.status)
        val starts = events.filterIsInstance<EpisodeStartedEvent>()
            .filter { it.agentProcess.id == process.id }
        val finishes = events.filterIsInstance<EpisodeFinishedEvent>()
            .filter { it.agentProcess.id == process.id }
        assertEquals(2, starts.size)
        assertEquals(2, finishes.size)
        assertTrue(starts.all { it.occurrenceId == occurrenceId })
        assertTrue(finishes.all { it.occurrenceId == occurrenceId })
        assertEquals(starts.map { it.episodeId }, finishes.map { it.episodeId })
        assertEquals(starts.map { it.childProcessId }, finishes.map { it.childProcessId })
        assertNotEquals(finishes[0].episodeId, finishes[1].episodeId)
        assertNotEquals(finishes[0].childProcessId, finishes[1].childProcessId)
        assertEquals(listOf(EpisodeOutcome.STUCK, EpisodeOutcome.COMPLETED), finishes.map { it.outcome })

        val consumed = events.filterIsInstance<OccurrenceConsumedEvent>()
            .single { it.agentProcess.id == process.id && it.occurrenceId == occurrenceId }
        assertEquals(finishes[1].episodeId, consumed.episodeId)
        assertTrue(events.indexOf(starts[0]) < events.indexOf(finishes[0]))
        assertTrue(events.indexOf(finishes[0]) < events.indexOf(starts[1]))
        assertTrue(events.indexOf(starts[1]) < events.indexOf(finishes[1]))
        assertTrue(events.indexOf(finishes[1]) < events.indexOf(consumed))
    }

    @Test
    fun `the planner can explicitly select an occurrence without an action input type`() {
        val events = mutableListOf<AgentProcessEvent>()
        val process = evolvingProcess(
            agentInstance = ConditionSelectedAgent(),
            plannerType = PlannerType.GOAP,
            events = events,
        )
        val occurrenceId = process.evolve(ConditionSelectedRequest("job-1"))

        val result = process.run()

        assertEquals(AgentProcessStatusCode.STUCK, result.status)
        val finished = events.filterIsInstance<EpisodeFinishedEvent>()
            .single { it.agentProcess.id == process.id }
        assertEquals(occurrenceId, finished.occurrenceId)
        assertEquals(EpisodeOutcome.COMPLETED, finished.outcome)
        assertEquals(
            listOf("complete"),
            finished.trace.actions.map { it.actionName.substringAfterLast('.') },
        )
        assertTrue(
            events.filterIsInstance<OccurrenceConsumedEvent>()
                .any { it.agentProcess.id == process.id && it.occurrenceId == occurrenceId },
        )
    }

    @Test
    fun `the runtime executes an opaque planner mission without inspecting it`() {
        val events = mutableListOf<AgentProcessEvent>()
        val listener = collectingListener(events)
        val agent = AgentMetadataReader().createAgentMetadata(OpaqueMissionAgent()) as CoreAgent
        val plannerFactory = PlannerFactory { options, worldStateDeterminer ->
            if (options.evolving != null) {
                MissionOnlyPlanner()
            } else {
                DefaultPlannerFactory.createPlanner(options, worldStateDeterminer)
            }
        }
        val process = SimpleAgentProcess(
            "opaque-mission-contract",
            null,
            agent,
            ProcessOptions.DEFAULT.withEvolving().withListener(listener),
            InMemoryBlackboard(),
            dummyPlatformServices(),
            plannerFactory,
            Instant.now(),
        )
        val occurrenceId = process.evolve(OpaqueMissionRequest("job-1"))

        val result = process.run()

        assertEquals(AgentProcessStatusCode.STUCK, result.status)
        val finished = events.filterIsInstance<EpisodeFinishedEvent>()
            .single { it.agentProcess.id == process.id }
        assertEquals(occurrenceId, finished.occurrenceId)
        assertEquals(EpisodeOutcome.COMPLETED, finished.outcome)
        assertEquals(
            listOf("handle"),
            finished.trace.actions.map { it.actionName.substringAfterLast('.') },
        )
    }

    @Test
    fun `context and process evolution produce the same occurrence lifecycle`() {
        val events = mutableListOf<AgentProcessEvent>()
        val listener = collectingListener(events)
        val agent = AgentMetadataReader().createAgentMetadata(UnifiedEvolutionAgent()) as CoreAgent
        val process = SimpleAgentProcess(
            "unified-evolution-contract",
            null,
            agent,
            ProcessOptions.DEFAULT.withEvolving().withListener(listener),
            InMemoryBlackboard().also { it.addObject(EvolutionSeed("seed-1")) },
            dummyPlatformServices(),
            DefaultPlannerFactory,
            Instant.now(),
        )

        process.run()
        val internalAcceptance = events.filterIsInstance<OccurrenceAcceptedEvent>().single()
        val externalId = process.evolve(ContextEvolvedRequest("external-1"))
        process.run()

        val externalAcceptance = events.filterIsInstance<OccurrenceAcceptedEvent>()
            .single { it.occurrenceId == externalId }
        assertTrue(internalAcceptance.publishedBy?.endsWith(".start") == true)
        assertEquals(null, externalAcceptance.publishedBy)
        val expectedLifecycle = listOf("accepted", "started", "finished:COMPLETED", "consumed")
        assertEquals(expectedLifecycle, lifecycleFor(internalAcceptance.occurrenceId, events))
        assertEquals(expectedLifecycle, lifecycleFor(externalId, events))
    }

    @Test
    fun `an occurrence evolved inside a child is linked to its exact episode execution`() {
        val events = mutableListOf<AgentProcessEvent>()
        val listener = collectingListener(events)
        val agent = AgentMetadataReader().createAgentMetadata(ChildEvolutionAgent()) as CoreAgent
        val process = SimpleAgentProcess(
            "child-evolution-lineage",
            null,
            agent,
            ProcessOptions.DEFAULT.withEvolving().withListener(listener),
            InMemoryBlackboard(),
            dummyPlatformServices(),
            DefaultPlannerFactory,
            Instant.now(),
        )
        val parentOccurrenceId = process.evolve(ParentEvolutionRequest("parent-1"))

        process.run()

        val parentExecution = events.filterIsInstance<EpisodeFinishedEvent>()
            .single { it.occurrenceId == parentOccurrenceId }
        val childAcceptance = events.filterIsInstance<OccurrenceAcceptedEvent>()
            .single { it.occurrence is ChildEvolutionRequest }
        assertEquals(parentOccurrenceId, childAcceptance.causedByOccurrenceId)
        assertEquals(parentExecution.episodeId, childAcceptance.causedByEpisodeId)
        assertEquals(parentExecution.childProcessId, childAcceptance.causedByChildProcessId)
        assertTrue(childAcceptance.publishedBy?.endsWith(".handleParent") == true)
        assertEquals(
            listOf("accepted", "started", "finished:COMPLETED", "consumed"),
            lifecycleFor(childAcceptance.occurrenceId, events),
        )
    }

    private fun evolvingProcess(events: MutableList<AgentProcessEvent>): SimpleAgentProcess {
        return evolvingProcess(PermitGatedAgent(), PlannerType.HYBRID, events, includeNirvana = true)
    }

    private fun evolvingProcess(
        agentInstance: Any,
        plannerType: PlannerType,
        events: MutableList<AgentProcessEvent>,
        includeNirvana: Boolean = false,
    ): SimpleAgentProcess {
        val listener = collectingListener(events)
        val declared = AgentMetadataReader().createAgentMetadata(agentInstance) as CoreAgent
        val agent = if (includeNirvana) {
            declared.copy(goals = declared.goals + NIRVANA)
        } else {
            declared
        }
        return SimpleAgentProcess(
            "episode-execution-contract",
            null,
            agent,
            ProcessOptions.DEFAULT
                .withPlannerType(plannerType)
                .withEvolving()
                .withListener(listener),
            InMemoryBlackboard(),
            dummyPlatformServices(),
            DefaultPlannerFactory,
            Instant.now(),
        )
    }

    private fun collectingListener(events: MutableList<AgentProcessEvent>) =
        object : AgenticEventListener {
            override fun onProcessEvent(event: AgentProcessEvent) {
                events += event
            }
        }

    private fun lifecycleFor(
        occurrenceId: com.embabel.agent.core.OccurrenceId,
        events: List<AgentProcessEvent>,
    ): List<String> =
        events.mapNotNull { event ->
            when (event) {
                is OccurrenceAcceptedEvent ->
                    "accepted".takeIf { event.occurrenceId == occurrenceId }

                is EpisodeStartedEvent ->
                    "started".takeIf { event.occurrenceId == occurrenceId }

                is EpisodeFinishedEvent ->
                    "finished:${event.outcome}".takeIf { event.occurrenceId == occurrenceId }

                is OccurrenceConsumedEvent ->
                    "consumed".takeIf { event.occurrenceId == occurrenceId }

                else -> null
            }
        }
}
