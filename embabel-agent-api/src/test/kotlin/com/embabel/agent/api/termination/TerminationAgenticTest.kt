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
package com.embabel.agent.api.termination

import com.embabel.agent.api.annotation.AchievesGoal
import com.embabel.agent.api.annotation.Action
import com.embabel.agent.api.annotation.support.AgentMetadataReader
import com.embabel.agent.api.dsl.Frog
import com.embabel.agent.api.dsl.agent
import com.embabel.agent.core.AgentProcess
import com.embabel.agent.core.AgentProcessCallback
import com.embabel.agent.api.tool.TerminateActionException
import com.embabel.agent.api.tool.TerminateAgentException
import com.embabel.agent.core.AgentProcessStatusCode
import com.embabel.agent.core.ProcessOptions
import com.embabel.agent.core.support.ConcurrentAgentProcess
import com.embabel.agent.core.support.InMemoryBlackboard
import com.embabel.agent.core.support.SimpleAgentProcess
import com.embabel.agent.api.event.ActionExecutionStartEvent
import com.embabel.agent.api.event.AgentProcessTerminatedEvent
import com.embabel.agent.spi.support.ExecutorAsyncer
import com.embabel.agent.spi.support.SpringContextPlatformServices
import com.embabel.agent.domain.io.UserInput
import com.embabel.agent.domain.library.Person
import com.embabel.agent.api.event.ToolCallRequestEvent
import com.embabel.agent.spi.DelayedActionExecutionSchedule
import com.embabel.agent.spi.OperationScheduler
import com.embabel.agent.spi.ScheduledActionExecutionSchedule
import com.embabel.agent.spi.ToolCallSchedule
import com.embabel.agent.spi.support.DefaultPlannerFactory
import com.embabel.agent.test.common.EventSavingAgenticEventListener
import com.embabel.agent.test.integration.IntegrationTestUtils.dummyPlatformServices
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

data class TestPerson(
    override val name: String,
) : Person

/**
 * Agent that throws TerminateActionException on first invocation.
 * On retry, the action completes normally showing the agent continued.
 */
val ActionTerminatingAgent = agent("ActionTerminator", description = "Agent that terminates action early") {
    transformation<UserInput, TestPerson>(name = "terminating_action", canRerun = true) {
        val attemptCount = (it["attemptCount"] as? Int) ?: 0
        it["attemptCount"] = attemptCount + 1
        if (attemptCount == 0) {
            throw TerminateActionException("User requested action termination")
        }
        TestPerson(name = "Attempt $attemptCount: ${it.input.content}")
    }

    transformation<TestPerson, Frog>(name = "to_frog") {
        Frog(it.input.name)
    }

    goal(name = "frog_goal", description = "Turn input into frog", satisfiedBy = Frog::class)
}

/**
 * Agent that throws TerminateAgentException.
 * The agent process should terminate with TERMINATED status.
 */
val AgentTerminatingAgent = agent("AgentTerminator", description = "Agent that terminates itself") {
    transformation<UserInput, TestPerson>(name = "terminating_action") {
        throw TerminateAgentException("Critical condition - terminating agent")
    }

    transformation<TestPerson, Frog>(name = "to_frog") {
        Frog(it.input.name)
    }

    goal(name = "frog_goal", description = "Turn input into frog", satisfiedBy = Frog::class)
}

/**
 * Agent that uses graceful ACTION termination via signal in first action.
 * The signal should be cleared after the action completes, allowing the second action to run.
 */
val GracefulActionTerminatingAgent = agent("GracefulActionTerminator", description = "Agent with graceful action termination") {
    transformation<UserInput, TestPerson>(name = "first_action_with_signal") {
        it["firstActionRan"] = true
        // Set ACTION termination signal - should be cleared after this action
        it.processContext.terminateAction("Graceful action termination")
        TestPerson(name = it.input.content)
    }

    transformation<TestPerson, Frog>(name = "second_action") {
        it["secondActionRan"] = true
        Frog(it.input.name)
    }

    goal(name = "frog_goal", description = "Turn input into frog", satisfiedBy = Frog::class)
}

/**
 * Agent that uses graceful termination via signal.
 * Sets termination signal on blackboard, checked at next tick.
 */
val GracefulAgentTerminatingAgent = agent("GracefulTerminator", description = "Agent that terminates gracefully") {
    transformation<UserInput, TestPerson>(name = "signal_termination") {
        it["signalSet"] = true
        it.processContext.terminateAgent("Graceful shutdown requested")
        TestPerson(name = it.input.content)
    }

    transformation<TestPerson, Frog>(name = "to_frog") {
        Frog(it.input.name)
    }

    goal(name = "frog_goal", description = "Turn input into frog", satisfiedBy = Frog::class)
}

data class FastCancellationPart(
    val name: String,
)

data class SlowCancellationPart(
    val sawCancellation: Boolean,
)

data class CancellationJoinResult(
    val sawCancellation: Boolean,
)

data class DelayedCancellationResult(
    val sawCancellation: Boolean,
)

data class WorkerThreadCancellationResult(
    val sawCancellation: Boolean,
)

fun delayedCancellationProbeAgent(
    sawCancellation: AtomicBoolean,
) = agent("DelayedCancellationProbe", description = "Agent with delayed cancellation probe") {
    transformation<UserInput, DelayedCancellationResult>(name = "delayed_probe") {
        val cancellationRequested = AgentProcess.get()!!.processContext.cancellationToken.isCancellationRequested
        sawCancellation.set(cancellationRequested)
        DelayedCancellationResult(cancellationRequested)
    }

    goal(
        name = "delayed_probe_goal",
        description = "Observe cancellation after action scheduling delay",
        satisfiedBy = DelayedCancellationResult::class,
    )
}

fun workerThreadCancellationTokenProbeAgent(
    sawCancellation: AtomicBoolean,
) = agent("WorkerThreadCancellationTokenProbe", description = "Agent with worker-thread cancellation probe") {
    transformation<UserInput, WorkerThreadCancellationResult>(name = "worker_thread_probe") {
        val token = it.processContext.cancellationToken
        val executor = Executors.newSingleThreadExecutor()
        try {
            it.processContext.terminateAction("current action cancellation")
            val workerSawCancellation = executor.submit<Boolean> {
                token.isCancellationRequested && token.reason == "current action cancellation"
            }.get(5, TimeUnit.SECONDS)
            sawCancellation.set(workerSawCancellation)
            WorkerThreadCancellationResult(workerSawCancellation)
        } finally {
            executor.shutdownNow()
        }
    }

    goal(
        name = "worker_thread_probe_goal",
        description = "Observe current-action cancellation from a delegated worker thread",
        satisfiedBy = WorkerThreadCancellationResult::class,
    )
}

fun workerThreadProcessContextProbeAgent(
    sawCancellation: AtomicBoolean,
) = agent("WorkerThreadProcessContextProbe", description = "Agent with worker-thread process context probe") {
    transformation<UserInput, WorkerThreadCancellationResult>(name = "worker_thread_process_context_probe") {
        val delegatedContext = it.processContext
        val executor = Executors.newSingleThreadExecutor()
        try {
            delegatedContext.terminateAction("current action cancellation")
            val workerSawCancellation = executor.submit<Boolean> {
                val token = delegatedContext.cancellationToken
                token.isCancellationRequested && token.reason == "current action cancellation"
            }.get(5, TimeUnit.SECONDS)
            sawCancellation.set(workerSawCancellation)
            WorkerThreadCancellationResult(workerSawCancellation)
        } finally {
            executor.shutdownNow()
        }
    }
    goal(
        name = "worker_thread_process_context_probe_goal",
        description = "Observe current-action cancellation from delegated worker ProcessContext access",
        satisfiedBy = WorkerThreadCancellationResult::class,
    )
}

private class DelayingActionScheduler(
    private val actionScheduled: CountDownLatch,
) : OperationScheduler {

    override fun scheduleAction(actionExecutionStartEvent: ActionExecutionStartEvent) =
        DelayedActionExecutionSchedule(Duration.ofMillis(250))
            .also { actionScheduled.countDown() }

    override fun scheduleToolCall(functionCallRequestEvent: ToolCallRequestEvent): ToolCallSchedule =
        ToolCallSchedule()
}

private class PausingActionScheduler(
    private val onSchedule: () -> Unit,
) : OperationScheduler {

    override fun scheduleAction(actionExecutionStartEvent: ActionExecutionStartEvent) =
        ScheduledActionExecutionSchedule(Instant.now().plusSeconds(60))
            .also { onSchedule() }

    override fun scheduleToolCall(functionCallRequestEvent: ToolCallRequestEvent): ToolCallSchedule =
        ToolCallSchedule()
}

@com.embabel.agent.api.annotation.Agent(
    description = "Agent with concurrent cancellation probes",
    scan = false,
)
class ConcurrentCancellationProbeAgent {

    val bothActionsStarted = CountDownLatch(2)
    val slowSawCancellation = AtomicBoolean(false)

    @Action
    fun fast(input: UserInput): FastCancellationPart {
        awaitBothActions()
        Thread.sleep(50)
        return FastCancellationPart(input.content)
    }

    @Action
    fun slow(input: UserInput): SlowCancellationPart {
        awaitBothActions()
        Thread.sleep(250)
        val sawCancellation = AgentProcess.get()!!.processContext.cancellationToken.isCancellationRequested
        slowSawCancellation.set(sawCancellation)
        return SlowCancellationPart(sawCancellation)
    }

    @Action
    @AchievesGoal(description = "Join concurrent cancellation probe results")
    fun join(
        fast: FastCancellationPart,
        slow: SlowCancellationPart,
    ): CancellationJoinResult = CancellationJoinResult(slow.sawCancellation)

    private fun awaitBothActions() {
        bothActionsStarted.countDown()
        check(bothActionsStarted.await(5, TimeUnit.SECONDS)) {
            "Concurrent cancellation probe actions did not both start"
        }
    }
}

@com.embabel.agent.api.annotation.Agent(
    description = "Agent with scoped concurrent cancellation probes",
    scan = false,
)
class ScopedConcurrentCancellationProbeAgent {

    val bothActionsStarted = CountDownLatch(2)
    val siblingSawCancellation = AtomicBoolean(false)

    @Action
    fun terminating(input: UserInput): FastCancellationPart {
        awaitBothActions()
        AgentProcess.get()!!.processContext.terminateAction("current action only")
        Thread.sleep(250)
        return FastCancellationPart(input.content)
    }

    @Action
    fun sibling(input: UserInput): SlowCancellationPart {
        awaitBothActions()
        Thread.sleep(50)
        val sawCancellation = AgentProcess.get()!!.processContext.cancellationToken.isCancellationRequested
        siblingSawCancellation.set(sawCancellation)
        return SlowCancellationPart(sawCancellation)
    }

    @Action
    @AchievesGoal(description = "Join scoped concurrent cancellation probe results")
    fun join(
        fast: FastCancellationPart,
        slow: SlowCancellationPart,
    ): CancellationJoinResult = CancellationJoinResult(slow.sawCancellation)

    private fun awaitBothActions() {
        bothActionsStarted.countDown()
        check(bothActionsStarted.await(5, TimeUnit.SECONDS)) {
            "Scoped concurrent cancellation probe actions did not both start"
        }
    }
}

class TerminationAgenticTest {

    @Nested
    inner class ActionTermination {

        @Test
        fun `TerminateActionException allows agent to continue and retry`() {
            val dummyPlatformServices = dummyPlatformServices()
            val blackboard = InMemoryBlackboard()
            blackboard += UserInput("TestUser")

            val agentProcess = SimpleAgentProcess(
                id = "test-action-termination",
                agent = ActionTerminatingAgent,
                processOptions = ProcessOptions(),
                blackboard = blackboard,
                platformServices = dummyPlatformServices,
                plannerFactory = DefaultPlannerFactory,
                parentId = null,
            )

            val result = agentProcess.run()

            assertThat(result.status).isEqualTo(AgentProcessStatusCode.COMPLETED)
            assertThat(blackboard["attemptCount"]).isEqualTo(2)
            val frog = blackboard.lastResult() as Frog
            assertThat(frog.name).contains("Attempt 1")
        }

        /**
         * Verifies that graceful ACTION termination signal is cleared after action completes.
         * The second action should still execute because the signal was cleared.
         */
        @Test
        fun `graceful ACTION signal is cleared allowing second action to execute`() {
            val dummyPlatformServices = dummyPlatformServices()
            val blackboard = InMemoryBlackboard()
            blackboard += UserInput("TestUser")

            val agentProcess = SimpleAgentProcess(
                id = "test-graceful-action-clearing",
                agent = GracefulActionTerminatingAgent,
                processOptions = ProcessOptions(),
                blackboard = blackboard,
                platformServices = dummyPlatformServices,
                plannerFactory = DefaultPlannerFactory,
                parentId = null,
            )

            val result = agentProcess.run()

            // Both actions should have run
            assertThat(blackboard["firstActionRan"]).isEqualTo(true)
            assertThat(blackboard["secondActionRan"]).isEqualTo(true)
            // Agent should complete successfully
            assertThat(result.status).isEqualTo(AgentProcessStatusCode.COMPLETED)
            // Final result should be a Frog
            val frog = blackboard.lastResult() as Frog
            assertThat(frog.name).isEqualTo("TestUser")
        }

        @Test
        fun `external terminateAction during scheduler delay is visible when action starts`() {
            val actionScheduled = CountDownLatch(1)
            val sawCancellation = AtomicBoolean(false)
            val platformServices = (dummyPlatformServices() as SpringContextPlatformServices).copy(
                operationScheduler = DelayingActionScheduler(actionScheduled),
            )
            val blackboard = InMemoryBlackboard()
            blackboard += UserInput("TestUser")
            val agentProcess = SimpleAgentProcess(
                id = "test-delayed-action-cancellation",
                agent = delayedCancellationProbeAgent(sawCancellation),
                processOptions = ProcessOptions(),
                blackboard = blackboard,
                platformServices = platformServices,
                plannerFactory = DefaultPlannerFactory,
                parentId = null,
            )
            val executor = Executors.newSingleThreadExecutor()

            try {
                val run = executor.submit<AgentProcess> {
                    agentProcess.run()
                }
                assertThat(actionScheduled.await(5, TimeUnit.SECONDS)).isTrue()

                agentProcess.terminateAction("host requested action cancellation during delay")
                val result = run.get(10, TimeUnit.SECONDS)

                assertThat(result.status).isEqualTo(AgentProcessStatusCode.COMPLETED)
                assertThat(sawCancellation.get()).isTrue()
            } finally {
                executor.shutdownNow()
            }
        }

        @Test
        fun `terminateAction before process start does not cancel first action`() {
            val sawCancellation = AtomicBoolean(false)
            val blackboard = InMemoryBlackboard()
            blackboard += UserInput("TestUser")
            val agentProcess = SimpleAgentProcess(
                id = "test-pre-start-action-cancellation",
                agent = delayedCancellationProbeAgent(sawCancellation),
                processOptions = ProcessOptions(),
                blackboard = blackboard,
                platformServices = dummyPlatformServices(),
                plannerFactory = DefaultPlannerFactory,
                parentId = null,
            )

            agentProcess.terminateAction("no active action before start")
            val result = agentProcess.run()

            assertThat(result.status).isEqualTo(AgentProcessStatusCode.COMPLETED)
            assertThat(sawCancellation.get()).isFalse()
        }

        @Test
        fun `ACTION signal targeted during scheduled pause is cleared when action parks`() {
            val sawCancellation = AtomicBoolean(false)
            lateinit var agentProcess: SimpleAgentProcess
            val platformServices = (dummyPlatformServices() as SpringContextPlatformServices).copy(
                operationScheduler = PausingActionScheduler {
                    agentProcess.terminateAction("host requested action cancellation during pause")
                },
            )
            val blackboard = InMemoryBlackboard()
            blackboard += UserInput("TestUser")
            agentProcess = SimpleAgentProcess(
                id = "test-paused-action-cancellation-clear",
                agent = delayedCancellationProbeAgent(sawCancellation),
                processOptions = ProcessOptions(),
                blackboard = blackboard,
                platformServices = platformServices,
                plannerFactory = DefaultPlannerFactory,
                parentId = null,
            )

            val result = agentProcess.run()

            assertThat(result.status).isEqualTo(AgentProcessStatusCode.PAUSED)
            assertThat(agentProcess.terminationRequest).isNull()
        }

        @Test
        fun `captured cancellation token observes current action termination from worker thread`() {
            val sawCancellation = AtomicBoolean(false)
            val blackboard = InMemoryBlackboard()
            blackboard += UserInput("TestUser")
            val agentProcess = SimpleAgentProcess(
                id = "test-worker-thread-cancellation-token",
                agent = workerThreadCancellationTokenProbeAgent(sawCancellation),
                processOptions = ProcessOptions(),
                blackboard = blackboard,
                platformServices = dummyPlatformServices(),
                plannerFactory = DefaultPlannerFactory,
                parentId = null,
            )

            val result = agentProcess.run()

            assertThat(result.status).isEqualTo(AgentProcessStatusCode.COMPLETED)
            assertThat(sawCancellation.get()).isTrue()
        }

        @Test
        fun `delegated process context observes current action termination from worker thread`() {
            val sawCancellation = AtomicBoolean(false)
            val blackboard = InMemoryBlackboard()
            blackboard += UserInput("TestUser")
            val agentProcess = SimpleAgentProcess(
                id = "test-worker-thread-process-context-token",
                agent = workerThreadProcessContextProbeAgent(sawCancellation),
                processOptions = ProcessOptions(),
                blackboard = blackboard,
                platformServices = dummyPlatformServices(),
                plannerFactory = DefaultPlannerFactory,
                parentId = null,
            )

            val result = agentProcess.run()

            assertThat(result.status).isEqualTo(AgentProcessStatusCode.COMPLETED)
            assertThat(sawCancellation.get()).isTrue()
        }
    }

    @Nested
    inner class AgentTermination {

        @Test
        fun `TerminateAgentException terminates agent process`() {
            val dummyPlatformServices = dummyPlatformServices()
            val blackboard = InMemoryBlackboard()
            blackboard += UserInput("TestUser")

            val agentProcess = SimpleAgentProcess(
                id = "test-agent-termination",
                agent = AgentTerminatingAgent,
                processOptions = ProcessOptions(),
                blackboard = blackboard,
                platformServices = dummyPlatformServices,
                plannerFactory = DefaultPlannerFactory,
                parentId = null,
            )

            val result = agentProcess.run()

            assertThat(result.status).isEqualTo(AgentProcessStatusCode.TERMINATED)
        }

        @Test
        fun `graceful termination signal terminates agent at next tick`() {
            val listener = EventSavingAgenticEventListener()
            val dummyPlatformServices = dummyPlatformServices(listener)
            val blackboard = InMemoryBlackboard()
            blackboard += UserInput("TestUser")

            val agentProcess = SimpleAgentProcess(
                id = "test-graceful-termination",
                agent = GracefulAgentTerminatingAgent,
                processOptions = ProcessOptions(),
                blackboard = blackboard,
                platformServices = dummyPlatformServices,
                plannerFactory = DefaultPlannerFactory,
                parentId = null,
            )

            val result = agentProcess.run()

            assertThat(blackboard["signalSet"]).isEqualTo(true)
            assertThat(result.status).isEqualTo(AgentProcessStatusCode.TERMINATED)
            assertThat(listener.processEvents.filterIsInstance<AgentProcessTerminatedEvent>()).hasSize(1)
        }
    }

    @Nested
    inner class ConcurrentActionTermination {

        @Test
        fun `TerminateActionException allows concurrent agent to continue and retry`() {
            val dummyPlatformServices = dummyPlatformServices()
            val blackboard = InMemoryBlackboard()
            blackboard += UserInput("TestUser")

            val agentProcess = ConcurrentAgentProcess(
                id = "test-concurrent-action-termination",
                agent = ActionTerminatingAgent,
                processOptions = ProcessOptions(),
                blackboard = blackboard,
                platformServices = dummyPlatformServices,
                plannerFactory = DefaultPlannerFactory,
                parentId = null,
            )

            val result = agentProcess.run()

            assertThat(result.status).isEqualTo(AgentProcessStatusCode.COMPLETED)
            assertThat(blackboard["attemptCount"]).isEqualTo(2)
            val frog = blackboard.lastResult() as Frog
            assertThat(frog.name).contains("Attempt 1")
        }

        /**
         * Verifies that graceful ACTION termination signal is cleared after action completes.
         * The second action should still execute because the signal was cleared.
         */
        @Test
        fun `graceful ACTION signal is cleared allowing second action to execute`() {
            val dummyPlatformServices = dummyPlatformServices()
            val blackboard = InMemoryBlackboard()
            blackboard += UserInput("TestUser")

            val agentProcess = ConcurrentAgentProcess(
                id = "test-concurrent-graceful-action-clearing",
                agent = GracefulActionTerminatingAgent,
                processOptions = ProcessOptions(),
                blackboard = blackboard,
                platformServices = dummyPlatformServices,
                plannerFactory = DefaultPlannerFactory,
                parentId = null,
            )

            val result = agentProcess.run()

            // Both actions should have run
            assertThat(blackboard["firstActionRan"]).isEqualTo(true)
            assertThat(blackboard["secondActionRan"]).isEqualTo(true)
            // Agent should complete successfully
            assertThat(result.status).isEqualTo(AgentProcessStatusCode.COMPLETED)
            // Final result should be a Frog
            val frog = blackboard.lastResult() as Frog
            assertThat(frog.name).isEqualTo("TestUser")
        }

        @Test
        fun `external terminateAction is not consumed by the first unrelated concurrent action to finish`() {
            val probe = ConcurrentCancellationProbeAgent()
            val agent = AgentMetadataReader().createAgentMetadata(probe) as com.embabel.agent.core.Agent
            val blackboard = InMemoryBlackboard()
            blackboard += UserInput("TestUser")
            val actionExecutor = Executors.newFixedThreadPool(2)
            val platformServices = (dummyPlatformServices() as SpringContextPlatformServices).copy(
                asyncer = ExecutorAsyncer(actionExecutor),
            )
            val agentProcess = ConcurrentAgentProcess(
                id = "test-concurrent-action-cancellation-targeting",
                agent = agent,
                processOptions = ProcessOptions(),
                blackboard = blackboard,
                platformServices = platformServices,
                plannerFactory = DefaultPlannerFactory,
                parentId = null,
            )
            val runExecutor = Executors.newSingleThreadExecutor()

            try {
                val run = runExecutor.submit<AgentProcess> {
                    agentProcess.run()
                }
                assertThat(probe.bothActionsStarted.await(5, TimeUnit.SECONDS)).isTrue()

                agentProcess.terminateAction("host requested action cancellation")
                val result = run.get(10, TimeUnit.SECONDS)

                assertThat(result.status).isEqualTo(AgentProcessStatusCode.COMPLETED)
                assertThat(probe.slowSawCancellation.get()).isTrue()
            } finally {
                runExecutor.shutdownNow()
                actionExecutor.shutdownNow()
            }
        }

        @Test
        fun `current action terminateAction does not trip sibling concurrent cancellation token`() {
            val probe = ScopedConcurrentCancellationProbeAgent()
            val agent = AgentMetadataReader().createAgentMetadata(probe) as com.embabel.agent.core.Agent
            val blackboard = InMemoryBlackboard()
            blackboard += UserInput("TestUser")
            val actionExecutor = Executors.newFixedThreadPool(2)
            val platformServices = (dummyPlatformServices() as SpringContextPlatformServices).copy(
                asyncer = ExecutorAsyncer(actionExecutor),
            )
            val agentProcess = ConcurrentAgentProcess(
                id = "test-scoped-concurrent-action-cancellation-token",
                agent = agent,
                processOptions = ProcessOptions(),
                blackboard = blackboard,
                platformServices = platformServices,
                plannerFactory = DefaultPlannerFactory,
                parentId = null,
            )

            try {
                val result = agentProcess.run()

                assertThat(result.status).isEqualTo(AgentProcessStatusCode.COMPLETED)
                assertThat(probe.siblingSawCancellation.get()).isFalse()
            } finally {
                actionExecutor.shutdownNow()
            }
        }

        @Test
        fun `terminateAction before concurrent actions have tokens does not cancel launched actions`() {
            val sawCancellation = AtomicBoolean(false)
            val blackboard = InMemoryBlackboard()
            blackboard += UserInput("TestUser")
            val actionExecutor = Executors.newSingleThreadExecutor()
            val platformServices = (dummyPlatformServices() as SpringContextPlatformServices).copy(
                asyncer = ExecutorAsyncer(actionExecutor),
            )
            val agentProcess = ConcurrentAgentProcess(
                id = "test-empty-target-action-termination",
                agent = delayedCancellationProbeAgent(sawCancellation),
                processOptions = ProcessOptions(),
                blackboard = blackboard,
                platformServices = platformServices,
                plannerFactory = DefaultPlannerFactory,
                parentId = null,
                callbacks = listOf(
                    object : AgentProcessCallback {
                        override fun beforeActionLaunched(process: AgentProcess) {
                            process.terminateAction("no active actions yet")
                        }

                        override fun onActionLaunched(
                            process: AgentProcess,
                            action: com.embabel.agent.core.Action,
                        ) {
                        }

                        override fun onActionCompleted(
                            process: AgentProcess,
                            action: com.embabel.agent.core.Action,
                        ) {
                        }
                    }
                ),
            )

            try {
                val result = agentProcess.run()

                assertThat(result.status).isEqualTo(AgentProcessStatusCode.COMPLETED)
                assertThat(sawCancellation.get()).isFalse()
            } finally {
                actionExecutor.shutdownNow()
            }
        }
    }

    @Nested
    inner class ConcurrentAgentTermination {

        @Test
        fun `TerminateAgentException terminates concurrent agent process`() {
            val dummyPlatformServices = dummyPlatformServices()
            val blackboard = InMemoryBlackboard()
            blackboard += UserInput("TestUser")

            val agentProcess = ConcurrentAgentProcess(
                id = "test-concurrent-agent-termination",
                agent = AgentTerminatingAgent,
                processOptions = ProcessOptions(),
                blackboard = blackboard,
                platformServices = dummyPlatformServices,
                plannerFactory = DefaultPlannerFactory,
                parentId = null,
            )

            val result = agentProcess.run()

            assertThat(result.status).isEqualTo(AgentProcessStatusCode.TERMINATED)
        }

        @Test
        fun `graceful termination signal terminates concurrent agent at next tick`() {
            val dummyPlatformServices = dummyPlatformServices()
            val blackboard = InMemoryBlackboard()
            blackboard += UserInput("TestUser")

            val agentProcess = ConcurrentAgentProcess(
                id = "test-concurrent-graceful-termination",
                agent = GracefulAgentTerminatingAgent,
                processOptions = ProcessOptions(),
                blackboard = blackboard,
                platformServices = dummyPlatformServices,
                plannerFactory = DefaultPlannerFactory,
                parentId = null,
            )

            val result = agentProcess.run()

            assertThat(blackboard["signalSet"]).isEqualTo(true)
            assertThat(result.status).isEqualTo(AgentProcessStatusCode.TERMINATED)
        }
    }

    @Nested
    inner class DirectAgentProcessApi {

        @Test
        fun `terminateAgent called directly on AgentProcess sets signal`() {
            val dummyPlatformServices = dummyPlatformServices()
            val blackboard = InMemoryBlackboard()
            blackboard += UserInput("TestUser")

            val agentProcess = SimpleAgentProcess(
                id = "test-direct-terminate-agent",
                agent = GracefulAgentTerminatingAgent,
                processOptions = ProcessOptions(),
                blackboard = blackboard,
                platformServices = dummyPlatformServices,
                plannerFactory = DefaultPlannerFactory,
                parentId = null,
            )

            // Call terminateAgent directly on AgentProcess (not via extension)
            agentProcess.terminateAgent("Direct API call")

            // Verify signal is set by running the process
            val result = agentProcess.run()
            assertThat(result.status).isEqualTo(AgentProcessStatusCode.TERMINATED)
        }

        @Test
        fun `terminateAction called directly on AgentProcess sets signal`() {
            val dummyPlatformServices = dummyPlatformServices()
            val blackboard = InMemoryBlackboard()
            blackboard += UserInput("TestUser")

            val agentProcess = SimpleAgentProcess(
                id = "test-direct-terminate-action",
                agent = GracefulActionTerminatingAgent,
                processOptions = ProcessOptions(),
                blackboard = blackboard,
                platformServices = dummyPlatformServices,
                plannerFactory = DefaultPlannerFactory,
                parentId = null,
            )

            // The GracefulActionTerminatingAgent calls terminateAction via extension
            // This test verifies the agent process method works correctly
            val result = agentProcess.run()

            // Both actions should have run (signal cleared after first action)
            assertThat(blackboard["firstActionRan"]).isEqualTo(true)
            assertThat(blackboard["secondActionRan"]).isEqualTo(true)
            assertThat(result.status).isEqualTo(AgentProcessStatusCode.COMPLETED)
        }
    }
}
