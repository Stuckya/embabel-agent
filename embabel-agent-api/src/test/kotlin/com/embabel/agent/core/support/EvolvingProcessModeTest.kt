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

import com.embabel.agent.api.dsl.agent
import com.embabel.agent.api.event.ActionExecutionResultEvent
import com.embabel.agent.core.AgendaCompletionMode
import com.embabel.agent.core.AgendaCompletionPredicate
import com.embabel.agent.core.AgendaEntry
import com.embabel.agent.core.AgendaEntryApprovalRequest
import com.embabel.agent.core.AgendaEntryApprovalResponse
import com.embabel.agent.core.AgendaEntryApproved
import com.embabel.agent.core.AgendaEntryApprover
import com.embabel.agent.core.AgendaLane
import com.embabel.agent.core.AgendaPlanningGoal
import com.embabel.agent.core.AgentProcess
import com.embabel.agent.core.AgentProcessStatusCode
import com.embabel.agent.core.ActionStatusCode
import com.embabel.agent.core.CompletionPolicy
import com.embabel.agent.core.EvolutionOptions
import com.embabel.agent.core.GoalAgenda
import com.embabel.agent.core.IngressOptions
import com.embabel.agent.core.IngressWake
import com.embabel.agent.core.ProcessOptions
import com.embabel.agent.core.ProcessOutcome
import com.embabel.agent.core.ProcessOutcomeCode
import com.embabel.agent.domain.io.UserInput
import com.embabel.agent.spi.support.DefaultPlannerFactory
import com.embabel.agent.api.event.AgentProcessTerminatedEvent
import com.embabel.agent.test.common.EventSavingAgenticEventListener
import com.embabel.agent.test.integration.IntegrationTestUtils.dummyPlatformServices
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicInteger

data class EconomicSignal(
    val name: String,
)

data class SafetySignal(
    val name: String,
)

data class EconomicOutcome(
    val name: String,
)

data class SafetyOutcome(
    val name: String,
)

data class DuplicateFirstOutcome(
    val name: String,
)

data class DuplicateSecondOutcome(
    val name: String,
)

data class BlockingWorkSignal(
    val name: String,
)

data class BlockingWorkOutcome(
    val name: String,
)

object SafetyPreemptProbe {

    lateinit var actionStarted: CountDownLatch
    lateinit var cancellationObserved: CountDownLatch

    fun reset() {
        actionStarted = CountDownLatch(1)
        cancellationObserved = CountDownLatch(1)
    }
}

val EvolvingAgendaAgent = agent("EvolvingAgendaAgent", description = "Tests evolving agenda projection") {
    transformation<EconomicSignal, EconomicOutcome>(name = "economic-work") {
        EconomicOutcome(it.input.name)
    }
    transformation<SafetySignal, SafetyOutcome>(name = "safety-work") {
        SafetyOutcome(it.input.name)
    }
    goal(
        name = "economic-goal",
        description = "Complete economic work",
        satisfiedBy = EconomicOutcome::class,
        value = { 1.0 },
    )
    goal(
        name = "safety-goal",
        description = "Complete safety work",
        satisfiedBy = SafetyOutcome::class,
        value = { 0.1 },
    )
}

val DuplicateGoalNameAgent = agent("DuplicateGoalNameAgent", description = "Tests agenda entry identity") {
    transformation<SafetySignal, DuplicateFirstOutcome>(name = "first-duplicate-work") {
        DuplicateFirstOutcome(it.input.name)
    }
    transformation<EconomicSignal, DuplicateSecondOutcome>(name = "second-duplicate-work") {
        DuplicateSecondOutcome(it.input.name)
    }
    goal(
        name = "duplicate-goal",
        description = "Complete first duplicate goal",
        satisfiedBy = DuplicateFirstOutcome::class,
        value = { 0.1 },
    )
    goal(
        name = "duplicate-goal",
        description = "Complete second duplicate goal",
        satisfiedBy = DuplicateSecondOutcome::class,
        value = { 1.0 },
    )
}

val ResumableSuppressionAgent = agent("ResumableSuppressionAgent", description = "Tests completed agenda suppression") {
    transformation<EconomicSignal, EconomicOutcome>(name = "low-value-economic-work") {
        EconomicOutcome(it.input.name)
    }
    transformation<SafetySignal, SafetyOutcome>(name = "high-value-safety-work") {
        SafetyOutcome(it.input.name)
    }
    goal(
        name = "economic-goal",
        description = "Complete economic work",
        satisfiedBy = EconomicOutcome::class,
        value = { 0.1 },
    )
    goal(
        name = "safety-goal",
        description = "Complete safety work",
        satisfiedBy = SafetyOutcome::class,
        value = { 1.0 },
    )
}

val SafetyPreemptAgent = agent("SafetyPreemptAgent", description = "Tests safety preemption") {
    transformation<BlockingWorkSignal, BlockingWorkOutcome>(name = "blocking-economic-work") {
        SafetyPreemptProbe.actionStarted.countDown()
        val token = AgentProcess.get()!!.processContext.cancellationToken
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (System.nanoTime() < deadline) {
            if (token.isCancellationRequested) {
                SafetyPreemptProbe.cancellationObserved.countDown()
                return@transformation BlockingWorkOutcome("cancelled")
            }
            Thread.sleep(10)
        }
        BlockingWorkOutcome("completed")
    }
    transformation<SafetySignal, SafetyOutcome>(name = "preempt-safety-work") {
        SafetyOutcome(it.input.name)
    }
    goal(
        name = "economic-goal",
        description = "Complete blocking economic work",
        satisfiedBy = BlockingWorkOutcome::class,
        value = { 1.0 },
    )
    goal(
        name = "safety-goal",
        description = "Complete safety work",
        satisfiedBy = SafetyOutcome::class,
        value = { 0.1 },
    )
}

class EvolvingProcessModeTest {

    @Test
    fun `activated agenda entry projects effective planning system without mutating agent goals`() {
        val blackboard = InMemoryBlackboard()
        blackboard += EconomicSignal("profitable")
        val safetyGoal = EvolvingAgendaAgent.goals.single { it.name == "safety-goal" }
        val safetyEntry = AgendaEntry(
            id = "safety-entry",
            goal = safetyGoal,
            source = "test",
            lane = AgendaLane.SAFETY,
            completionMode = AgendaCompletionMode.TERMINAL,
            activationKey = "danger",
        )
        val agentProcess = SimpleAgentProcess(
            id = "test-evolving-agenda",
            agent = EvolvingAgendaAgent,
            processOptions = ProcessOptions().withEvolution(
                EvolutionOptions(
                    agendaCatalog = GoalAgenda().withEntry(safetyEntry),
                )
            ),
            blackboard = blackboard,
            platformServices = dummyPlatformServices(),
            plannerFactory = DefaultPlannerFactory,
            parentId = null,
        )

        agentProcess.ingress.publish(
            SafetySignal("danger"),
            IngressOptions(activationKey = "danger"),
        )
        val result = agentProcess.run()

        assertEquals(AgentProcessStatusCode.COMPLETED, result.status)
        assertTrue(result.lastResult() is SafetyOutcome)
        assertEquals(2, EvolvingAgendaAgent.goals.size, "Agenda projection must not mutate Agent.goals")
    }

    @Test
    fun `activation key activates an agenda entry only once`() {
        val approvalCalls = AtomicInteger()
        val approver = object : AgendaEntryApprover {
            override fun approve(request: AgendaEntryApprovalRequest): AgendaEntryApprovalResponse {
                approvalCalls.incrementAndGet()
                return AgendaEntryApproved(request)
            }
        }
        val blackboard = InMemoryBlackboard()
        val safetyGoal = EvolvingAgendaAgent.goals.single { it.name == "safety-goal" }
        val safetyEntry = AgendaEntry(
            id = "safety-entry",
            goal = safetyGoal,
            source = "test",
            lane = AgendaLane.SAFETY,
            completionMode = AgendaCompletionMode.TERMINAL,
            activationKey = "danger",
        )
        val agentProcess = SimpleAgentProcess(
            id = "test-evolving-agenda",
            agent = EvolvingAgendaAgent,
            processOptions = ProcessOptions().withEvolution(
                EvolutionOptions(
                    agendaCatalog = GoalAgenda().withEntry(safetyEntry),
                    agendaEntryApprover = approver,
                )
            ),
            blackboard = blackboard,
            platformServices = dummyPlatformServices(),
            plannerFactory = DefaultPlannerFactory,
            parentId = null,
        )

        agentProcess.ingress.publish(
            SafetySignal("first"),
            IngressOptions(activationKey = "danger"),
        )
        agentProcess.ingress.publish(
            SafetySignal("second"),
            IngressOptions(activationKey = "danger"),
        )
        agentProcess.run()

        assertEquals(1, approvalCalls.get())
        assertEquals(1, agentProcess.goalAgenda.entries.size)
    }

    @Test
    fun `resumable agenda entry is removed and process re-arbitrates`() {
        val blackboard = InMemoryBlackboard()
        blackboard += SafetySignal("danger")
        blackboard += EconomicSignal("profitable")
        val safetyGoal = EvolvingAgendaAgent.goals.single { it.name == "safety-goal" }
        val safetyEntry = AgendaEntry(
            id = "safety-entry",
            goal = safetyGoal,
            source = "test",
            lane = AgendaLane.SAFETY,
            completionMode = AgendaCompletionMode.RESUMABLE,
        )
        val agentProcess = SimpleAgentProcess(
            id = "test-resumable-agenda",
            agent = EvolvingAgendaAgent,
            processOptions = ProcessOptions().withEvolution(
                EvolutionOptions(
                    agendaCatalog = GoalAgenda().withEntry(safetyEntry),
                )
            ),
            blackboard = blackboard,
            platformServices = dummyPlatformServices(),
            plannerFactory = DefaultPlannerFactory,
            parentId = null,
        )

        agentProcess.tick()
        assertTrue(agentProcess.lastResult() is SafetyOutcome)

        agentProcess.tick()
        assertEquals(AgentProcessStatusCode.RUNNING, agentProcess.status)
        assertEquals(ProcessOutcomeCode.CONTINUE, agentProcess.outcome.code)
        assertEquals(emptyList<AgendaEntry>(), agentProcess.goalAgenda.entries)

        agentProcess.tick()
        assertTrue(agentProcess.lastResult() is EconomicOutcome)

        agentProcess.tick()
        assertEquals(AgentProcessStatusCode.COMPLETED, agentProcess.status)
        assertTrue(agentProcess.lastResult() is EconomicOutcome)
    }

    @Test
    fun `completion policy outcomes are reflected in process status and outcome`() {
        val outcomes = mapOf(
            ProcessOutcomeCode.COMPLETED to AgentProcessStatusCode.COMPLETED,
            ProcessOutcomeCode.EXHAUSTED to AgentProcessStatusCode.TERMINATED,
            ProcessOutcomeCode.CANCELLED to AgentProcessStatusCode.TERMINATED,
        )

        outcomes.forEach { (outcomeCode, expectedStatus) ->
            val agentProcess = SimpleAgentProcess(
                id = "test-policy-$outcomeCode",
                agent = EvolvingAgendaAgent,
                processOptions = ProcessOptions().withEvolution(
                    EvolutionOptions(
                        completionPolicy = CompletionPolicy { _, _ ->
                            ProcessOutcome(
                                code = outcomeCode,
                                reason = "policy selected $outcomeCode",
                            )
                        },
                    )
                ),
                blackboard = InMemoryBlackboard(),
                platformServices = dummyPlatformServices(),
                plannerFactory = DefaultPlannerFactory,
                parentId = null,
            )

            agentProcess.run()

            assertEquals(expectedStatus, agentProcess.status)
            assertEquals(outcomeCode, agentProcess.outcome.code)
        }
    }

    @Test
    fun `cancelled completion policy emits terminated event`() {
        val listener = EventSavingAgenticEventListener()
        val agentProcess = SimpleAgentProcess(
            id = "test-policy-cancelled-event",
            agent = EvolvingAgendaAgent,
            processOptions = ProcessOptions().withEvolution(
                EvolutionOptions(
                    completionPolicy = CompletionPolicy { _, _ ->
                        ProcessOutcome(
                            code = ProcessOutcomeCode.CANCELLED,
                            reason = "policy cancelled",
                        )
                    },
                )
            ),
            blackboard = InMemoryBlackboard(),
            platformServices = dummyPlatformServices(listener),
            plannerFactory = DefaultPlannerFactory,
            parentId = null,
        )

        agentProcess.run()

        assertEquals(AgentProcessStatusCode.TERMINATED, agentProcess.status)
        assertTrue(listener.processEvents.any { it is AgentProcessTerminatedEvent })
    }

    @Test
    fun `agenda completion removes the selected entry when goals share a name`() {
        val blackboard = InMemoryBlackboard()
        blackboard += SafetySignal("low-value")
        blackboard += EconomicSignal("high-value")
        val firstGoal = DuplicateGoalNameAgent.goals.single {
            it.outputType?.name == DuplicateFirstOutcome::class.java.name
        }
        val secondGoal = DuplicateGoalNameAgent.goals.single {
            it.outputType?.name == DuplicateSecondOutcome::class.java.name
        }
        val firstEntry = AgendaEntry(
            id = "first-entry",
            goal = firstGoal,
            completionMode = AgendaCompletionMode.RESUMABLE,
        )
        val secondEntry = AgendaEntry(
            id = "second-entry",
            goal = secondGoal,
            completionMode = AgendaCompletionMode.RESUMABLE,
        )
        val agentProcess = SimpleAgentProcess(
            id = "test-duplicate-goal-name",
            agent = DuplicateGoalNameAgent,
            processOptions = ProcessOptions().withEvolution(
                EvolutionOptions(
                    agendaCatalog = GoalAgenda()
                        .withEntry(firstEntry)
                        .withEntry(secondEntry),
                )
            ),
            blackboard = blackboard,
            platformServices = dummyPlatformServices(),
            plannerFactory = DefaultPlannerFactory,
            parentId = null,
        )

        agentProcess.tick()
        agentProcess.tick()

        assertTrue(agentProcess.lastResult() is DuplicateSecondOutcome)
        assertEquals(listOf("first-entry"), agentProcess.goalAgenda.entries.map { it.id })
    }

    @Test
    fun `agenda projection carries entry bindings into selected goal`() {
        val blackboard = InMemoryBlackboard()
        blackboard += SafetySignal("rune platebody")
        val safetyGoal = EvolvingAgendaAgent.goals.single { it.name == "safety-goal" }
        val safetyEntry = AgendaEntry(
            id = "bound-safety-entry",
            goal = safetyGoal,
            bindings = mapOf("item" to "rune platebody"),
            completionMode = AgendaCompletionMode.RESUMABLE,
        )
        val agentProcess = SimpleAgentProcess(
            id = "test-agenda-bindings",
            agent = EvolvingAgendaAgent,
            processOptions = ProcessOptions().withEvolution(
                EvolutionOptions(
                    agendaCatalog = GoalAgenda().withEntry(safetyEntry),
                )
            ),
            blackboard = blackboard,
            platformServices = dummyPlatformServices(),
            plannerFactory = DefaultPlannerFactory,
            parentId = null,
        )

        agentProcess.tick()

        val agendaGoal = agentProcess.goal as AgendaPlanningGoal
        assertEquals("rune platebody", agendaGoal.entry.bindings["item"])
    }

    @Test
    fun `completed resumable agenda goal is not reselected from base agent goals`() {
        val blackboard = InMemoryBlackboard()
        blackboard += SafetySignal("danger")
        blackboard += EconomicSignal("profitable")
        val safetyGoal = ResumableSuppressionAgent.goals.single { it.name == "safety-goal" }
        val safetyEntry = AgendaEntry(
            id = "safety-entry",
            goal = safetyGoal,
            completionMode = AgendaCompletionMode.RESUMABLE,
        )
        val agentProcess = SimpleAgentProcess(
            id = "test-resumable-suppression",
            agent = ResumableSuppressionAgent,
            processOptions = ProcessOptions().withEvolution(
                EvolutionOptions(
                    agendaCatalog = GoalAgenda().withEntry(safetyEntry),
                )
            ),
            blackboard = blackboard,
            platformServices = dummyPlatformServices(),
            plannerFactory = DefaultPlannerFactory,
            parentId = null,
        )

        agentProcess.tick()
        agentProcess.tick()
        agentProcess.tick()

        assertEquals(AgentProcessStatusCode.RUNNING, agentProcess.status)
        assertTrue(agentProcess.lastResult() is EconomicOutcome)
    }

    @Test
    fun `composite terminal agenda entry completes only when predicate is satisfied`() {
        val blackboard = InMemoryBlackboard()
        blackboard += SafetySignal("danger")
        val safetyGoal = EvolvingAgendaAgent.goals.single { it.name == "safety-goal" }
        val compositeEntry = AgendaEntry(
            id = "composite-entry",
            goal = safetyGoal,
            completionMode = AgendaCompletionMode.COMPOSITE_TERMINAL,
            completionPredicate = AgendaCompletionPredicate { process, _, _ ->
                process.objects.any { it is EconomicOutcome }
            },
        )
        val agentProcess = SimpleAgentProcess(
            id = "test-composite-terminal",
            agent = EvolvingAgendaAgent,
            processOptions = ProcessOptions().withEvolution(
                EvolutionOptions(
                    agendaCatalog = GoalAgenda().withEntry(compositeEntry),
                )
            ),
            blackboard = blackboard,
            platformServices = dummyPlatformServices(),
            plannerFactory = DefaultPlannerFactory,
            parentId = null,
        )

        agentProcess.tick()
        agentProcess.tick()

        assertEquals(AgentProcessStatusCode.WAITING, agentProcess.status)

        agentProcess.addObject(EconomicOutcome("done"))
        agentProcess.tick()

        assertEquals(AgentProcessStatusCode.COMPLETED, agentProcess.status)
    }

    @Test
    fun `incomplete composite terminal run parks instead of spinning on already satisfied child goal`() {
        val blackboard = InMemoryBlackboard()
        blackboard += SafetySignal("danger")
        val safetyGoal = EvolvingAgendaAgent.goals.single { it.name == "safety-goal" }
        val compositeEntry = AgendaEntry(
            id = "composite-entry",
            goal = safetyGoal,
            completionMode = AgendaCompletionMode.COMPOSITE_TERMINAL,
            completionPredicate = AgendaCompletionPredicate { process, _, _ ->
                process.objects.any { it is EconomicOutcome }
            },
        )
        val agentProcess = SimpleAgentProcess(
            id = "test-composite-terminal-run",
            agent = EvolvingAgendaAgent,
            processOptions = ProcessOptions().withEvolution(
                EvolutionOptions(
                    agendaCatalog = GoalAgenda().withEntry(compositeEntry),
                )
            ),
            blackboard = blackboard,
            platformServices = dummyPlatformServices(),
            plannerFactory = DefaultPlannerFactory,
            parentId = null,
        )

        assertRunReturnsWithin(agentProcess, AgentProcessStatusCode.WAITING)
        assertEquals(ProcessOutcomeCode.CONTINUE, agentProcess.outcome.code)
    }

    @Test
    fun `safety lane has hard priority over higher value economic agenda entries`() {
        val blackboard = InMemoryBlackboard()
        blackboard += SafetySignal("danger")
        blackboard += EconomicSignal("profitable")
        val safetyGoal = EvolvingAgendaAgent.goals.single { it.name == "safety-goal" }
        val economicGoal = EvolvingAgendaAgent.goals.single { it.name == "economic-goal" }
        val safetyEntry = AgendaEntry(
            id = "safety-entry",
            goal = safetyGoal,
            lane = AgendaLane.SAFETY,
            completionMode = AgendaCompletionMode.RESUMABLE,
        )
        val economicEntry = AgendaEntry(
            id = "economic-entry",
            goal = economicGoal,
            lane = AgendaLane.ECONOMIC,
            completionMode = AgendaCompletionMode.RESUMABLE,
        )
        val agentProcess = SimpleAgentProcess(
            id = "test-safety-lane-priority",
            agent = EvolvingAgendaAgent,
            processOptions = ProcessOptions().withEvolution(
                EvolutionOptions(
                    agendaCatalog = GoalAgenda()
                        .withEntry(economicEntry)
                        .withEntry(safetyEntry),
                )
            ),
            blackboard = blackboard,
            platformServices = dummyPlatformServices(),
            plannerFactory = DefaultPlannerFactory,
            parentId = null,
        )

        agentProcess.tick()

        assertTrue(agentProcess.lastResult() is SafetyOutcome)
    }

    @Test
    fun `wake ingress makes a waiting process runnable again`() {
        val blackboard = InMemoryBlackboard()
        blackboard += UserInput("Rod")
        val agentProcess = SimpleAgentProcess(
            id = "test-wake-waiting-process",
            agent = DslWaitingAgent,
            processOptions = ProcessOptions().withEvolution(EvolutionOptions()),
            blackboard = blackboard,
            platformServices = dummyPlatformServices(),
            plannerFactory = DefaultPlannerFactory,
            parentId = null,
        )

        agentProcess.run()

        assertEquals(AgentProcessStatusCode.WAITING, agentProcess.status)

        agentProcess.ingress.publish(
            EconomicSignal("wake"),
            IngressOptions(wake = IngressWake.WAKE),
        )

        assertEquals(AgentProcessStatusCode.RUNNING, agentProcess.status)
    }

    @Test
    fun `wake ingress makes a stuck process runnable again`() {
        val agentProcess = SimpleAgentProcess(
            id = "test-wake-stuck-process",
            agent = EvolvingAgendaAgent,
            processOptions = ProcessOptions().withEvolution(EvolutionOptions()),
            blackboard = InMemoryBlackboard(),
            platformServices = dummyPlatformServices(),
            plannerFactory = DefaultPlannerFactory,
            parentId = null,
        )

        agentProcess.run()

        assertEquals(AgentProcessStatusCode.STUCK, agentProcess.status)

        agentProcess.ingress.publish(
            EconomicSignal("wake"),
            IngressOptions(wake = IngressWake.WAKE),
        )

        assertEquals(AgentProcessStatusCode.RUNNING, agentProcess.status)
    }

    @Test
    fun `runtime agenda entries can be added directly`() {
        val blackboard = InMemoryBlackboard()
        blackboard += SafetySignal("danger")
        val safetyGoal = EvolvingAgendaAgent.goals.single { it.name == "safety-goal" }
        val safetyEntry = AgendaEntry(
            id = "runtime-safety-entry",
            goal = safetyGoal,
            lane = AgendaLane.SAFETY,
            completionMode = AgendaCompletionMode.TERMINAL,
        )
        val agentProcess = SimpleAgentProcess(
            id = "test-runtime-agenda-addition",
            agent = EvolvingAgendaAgent,
            processOptions = ProcessOptions().withEvolution(EvolutionOptions()),
            blackboard = blackboard,
            platformServices = dummyPlatformServices(),
            plannerFactory = DefaultPlannerFactory,
            parentId = null,
        )

        val response = agentProcess.addAgendaEntry(safetyEntry)
        agentProcess.tick()
        agentProcess.tick()

        assertTrue(response.approved)
        assertTrue(agentProcess.lastResult() is SafetyOutcome)
        assertEquals(AgentProcessStatusCode.COMPLETED, agentProcess.status)
    }

    @Test
    fun `activation keyed resumable entries can be rearmed by a later event`() {
        val approvalCalls = AtomicInteger()
        val approver = object : AgendaEntryApprover {
            override fun approve(request: AgendaEntryApprovalRequest): AgendaEntryApprovalResponse {
                approvalCalls.incrementAndGet()
                return AgendaEntryApproved(request)
            }
        }
        val safetyGoal = EvolvingAgendaAgent.goals.single { it.name == "safety-goal" }
        val safetyEntry = AgendaEntry(
            id = "rearmable-safety-entry",
            goal = safetyGoal,
            lane = AgendaLane.SAFETY,
            completionMode = AgendaCompletionMode.RESUMABLE,
            activationKey = "danger",
        )
        val agentProcess = SimpleAgentProcess(
            id = "test-activation-rearm",
            agent = EvolvingAgendaAgent,
            processOptions = ProcessOptions().withEvolution(
                EvolutionOptions(
                    agendaCatalog = GoalAgenda().withEntry(safetyEntry),
                    agendaEntryApprover = approver,
                )
            ),
            blackboard = InMemoryBlackboard(),
            platformServices = dummyPlatformServices(),
            plannerFactory = DefaultPlannerFactory,
            parentId = null,
        )

        agentProcess.ingress.publish(
            SafetySignal("first"),
            IngressOptions(activationKey = "danger"),
        )
        agentProcess.tick()
        agentProcess.tick()
        agentProcess.objects.filterIsInstance<SafetyOutcome>().forEach { agentProcess.hide(it) }

        agentProcess.ingress.publish(
            SafetySignal("second"),
            IngressOptions(activationKey = "danger"),
        )
        agentProcess.tick()

        assertEquals(2, approvalCalls.get())
        assertEquals(listOf("rearmable-safety-entry"), agentProcess.goalAgenda.entries.map { it.id })
    }

    @Test
    fun `reactivating resumable agenda entry does not hide prior goal output`() {
        val blackboard = InMemoryBlackboard()
        blackboard += SafetySignal("danger")
        val safetyGoal = EvolvingAgendaAgent.goals.single { it.name == "safety-goal" }
        val safetyEntry = AgendaEntry(
            id = "rearmable-safety-entry",
            goal = safetyGoal,
            lane = AgendaLane.SAFETY,
            completionMode = AgendaCompletionMode.RESUMABLE,
        )
        val agentProcess = SimpleAgentProcess(
            id = "test-resumable-reactivation-does-not-hide-output",
            agent = EvolvingAgendaAgent,
            processOptions = ProcessOptions().withEvolution(EvolutionOptions()),
            blackboard = blackboard,
            platformServices = dummyPlatformServices(),
            plannerFactory = DefaultPlannerFactory,
            parentId = null,
        )
        agentProcess.addAgendaEntry(safetyEntry)
        agentProcess.tick()
        agentProcess.tick()
        val safetyOutcome = agentProcess.objects.filterIsInstance<SafetyOutcome>().single()

        agentProcess.addAgendaEntry(safetyEntry)

        assertTrue(safetyOutcome in agentProcess.objects)
    }

    @Test
    fun `keep alive agenda entry parks process after goal is achieved`() {
        val blackboard = InMemoryBlackboard()
        blackboard += SafetySignal("danger")
        val safetyGoal = EvolvingAgendaAgent.goals.single { it.name == "safety-goal" }
        val keepAliveEntry = AgendaEntry(
            id = "keep-alive-safety-entry",
            goal = safetyGoal,
            completionMode = AgendaCompletionMode.KEEP_ALIVE,
        )
        val agentProcess = SimpleAgentProcess(
            id = "test-keep-alive",
            agent = EvolvingAgendaAgent,
            processOptions = ProcessOptions().withEvolution(
                EvolutionOptions(
                    agendaCatalog = GoalAgenda().withEntry(keepAliveEntry),
                )
            ),
            blackboard = blackboard,
            platformServices = dummyPlatformServices(),
            plannerFactory = DefaultPlannerFactory,
            parentId = null,
        )

        agentProcess.tick()
        agentProcess.tick()

        assertEquals(AgentProcessStatusCode.WAITING, agentProcess.status)
        assertEquals(ProcessOutcomeCode.CONTINUE, agentProcess.outcome.code)
    }

    @Test
    fun `keep alive run parks instead of spinning on already satisfied goal`() {
        val blackboard = InMemoryBlackboard()
        blackboard += SafetySignal("danger")
        val safetyGoal = EvolvingAgendaAgent.goals.single { it.name == "safety-goal" }
        val keepAliveEntry = AgendaEntry(
            id = "keep-alive-safety-entry",
            goal = safetyGoal,
            completionMode = AgendaCompletionMode.KEEP_ALIVE,
        )
        val agentProcess = SimpleAgentProcess(
            id = "test-keep-alive-run",
            agent = EvolvingAgendaAgent,
            processOptions = ProcessOptions().withEvolution(
                EvolutionOptions(
                    agendaCatalog = GoalAgenda().withEntry(keepAliveEntry),
                )
            ),
            blackboard = blackboard,
            platformServices = dummyPlatformServices(),
            plannerFactory = DefaultPlannerFactory,
            parentId = null,
        )

        assertRunReturnsWithin(agentProcess, AgentProcessStatusCode.WAITING)
        assertEquals(ProcessOutcomeCode.CONTINUE, agentProcess.outcome.code)
    }

    @Test
    fun `safety preempt marks cooperative in flight action terminated and replans to safety agenda`() {
        SafetyPreemptProbe.reset()
        val listener = EventSavingAgenticEventListener()
        val blackboard = InMemoryBlackboard()
        blackboard += BlockingWorkSignal("profitable")
        val safetyGoal = SafetyPreemptAgent.goals.single { it.name == "safety-goal" }
        val safetyEntry = AgendaEntry(
            id = "preempt-safety-entry",
            goal = safetyGoal,
            lane = AgendaLane.SAFETY,
            completionMode = AgendaCompletionMode.TERMINAL,
            activationKey = "danger",
        )
        val agentProcess = SimpleAgentProcess(
            id = "test-safety-preempt-end-to-end",
            agent = SafetyPreemptAgent,
            processOptions = ProcessOptions().withEvolution(
                EvolutionOptions(
                    agendaCatalog = GoalAgenda().withEntry(safetyEntry),
                )
            ),
            blackboard = blackboard,
            platformServices = dummyPlatformServices(listener),
            plannerFactory = DefaultPlannerFactory,
            parentId = null,
        )
        val executor = Executors.newSingleThreadExecutor()
        try {
            val run = executor.submit<AgentProcess> { agentProcess.run() }
            assertTrue(SafetyPreemptProbe.actionStarted.await(1, TimeUnit.SECONDS))

            agentProcess.ingress.publish(
                SafetySignal("danger"),
                IngressOptions(wake = IngressWake.SAFETY_PREEMPT, activationKey = "danger"),
            )

            assertTrue(SafetyPreemptProbe.cancellationObserved.await(1, TimeUnit.SECONDS))
            val result = run.get(2, TimeUnit.SECONDS)

            assertEquals(AgentProcessStatusCode.COMPLETED, result.status)
            assertTrue(result.lastResult() is SafetyOutcome)
            val blockingActionResult = listener.processEvents
                .filterIsInstance<ActionExecutionResultEvent>()
                .single { it.action.name == "blocking-economic-work" }
            assertEquals(ActionStatusCode.TERMINATED, blockingActionResult.actionStatus.status)
        } finally {
            agentProcess.terminateAgent("test cleanup")
            executor.shutdownNow()
        }
    }

    @Test
    fun `drained resumable agenda with no remaining plan terminates as exhausted`() {
        val blackboard = InMemoryBlackboard()
        blackboard += SafetySignal("danger")
        val safetyGoal = ResumableSuppressionAgent.goals.single { it.name == "safety-goal" }
        val safetyEntry = AgendaEntry(
            id = "safety-entry",
            goal = safetyGoal,
            completionMode = AgendaCompletionMode.RESUMABLE,
        )
        val agentProcess = SimpleAgentProcess(
            id = "test-resumable-exhaustion",
            agent = ResumableSuppressionAgent,
            processOptions = ProcessOptions().withEvolution(
                EvolutionOptions(
                    agendaCatalog = GoalAgenda().withEntry(safetyEntry),
                )
            ),
            blackboard = blackboard,
            platformServices = dummyPlatformServices(),
            plannerFactory = DefaultPlannerFactory,
            parentId = null,
        )

        agentProcess.tick()
        agentProcess.tick()
        agentProcess.tick()

        assertEquals(AgentProcessStatusCode.TERMINATED, agentProcess.status)
        assertEquals(ProcessOutcomeCode.EXHAUSTED, agentProcess.outcome.code)
    }

    private fun assertRunReturnsWithin(
        agentProcess: SimpleAgentProcess,
        expectedStatus: AgentProcessStatusCode,
    ) {
        val executor = Executors.newSingleThreadExecutor()
        val run = executor.submit<AgentProcess> { agentProcess.run() }
        try {
            val result = run.get(500, TimeUnit.MILLISECONDS)
            assertEquals(expectedStatus, result.status)
        } catch (e: TimeoutException) {
            agentProcess.terminateAgent("test cleanup after run timeout")
            run.cancel(true)
            throw AssertionError("run() did not return within 500 ms; possible tight loop", e)
        } finally {
            executor.shutdownNow()
            executor.awaitTermination(1, TimeUnit.SECONDS)
        }
    }
}
