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
import com.embabel.agent.api.annotation.support.AgentMetadataReader
import com.embabel.agent.api.common.ActionContext
import com.embabel.agent.api.event.AgentProcessEvent
import com.embabel.agent.api.event.AgentProcessFinishedEvent
import com.embabel.agent.api.event.AgenticEventListener
import com.embabel.agent.api.event.EpisodeFinishedEvent
import com.embabel.agent.api.event.GoalAchievedEvent
import com.embabel.agent.api.event.OccurrenceAcceptedEvent
import com.embabel.agent.api.event.OccurrenceConsumedEvent
import com.embabel.agent.core.Agent as CoreAgent
import com.embabel.agent.core.AgentProcessStatusCode
import com.embabel.agent.core.Evolving
import com.embabel.agent.core.GoalTarget
import com.embabel.agent.core.ProcessOptions
import com.embabel.agent.core.Action as CoreAction
import com.embabel.agent.core.AgentProcess
import com.embabel.agent.core.AgentProcessCallback
import com.embabel.agent.core.last
import com.embabel.agent.core.support.ConcurrentAgentProcess
import com.embabel.agent.core.support.InMemoryBlackboard
import com.embabel.agent.core.support.SimpleAgentProcess
import com.embabel.agent.spi.support.DefaultPlannerFactory
import com.embabel.agent.test.integration.IntegrationTestUtils.dummyPlatformServices
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

data class SignalReceived(val id: String)
data class PartA(val id: String)
data class PartB(val id: String)
data class Enablement(val id: String)
data class SignalTriaged(val id: String)
data class SignalArchived(val id: String)

/**
 * The episode contract: serial admission, identity consumption, grounding,
 * retry, event ordering, and the canRerun boundary. Occurrences arrive only
 * through evolve(); everything else is standing state. Dispatch mechanics are
 * pinned in GoalEpisodeFrameworkDispatchTest; this suite pins the contract
 * those mechanics carry.
 */
class GoalEvolvingContractTest {

    @Test
    fun `equal evolved values receive distinct occurrence identities`() {
        val process = evolvingProcess(CalibrationAgent())

        val first = process.evolve(CalibrationRequested("cal-1"))
        val second = process.evolve(CalibrationRequested("cal-1"))

        assertNotEquals(first, second, "Occurrence identity, not value equality, defines an episode")
    }

    @Agent(description = "Single-step calibration driven by evolved occurrences")
    inner class CalibrationAgent {

        @Action(canRerun = true, value = 0.9)
        @AchievesGoal(description = "Calibration completed", value = 1.0)
        fun calibrate(request: CalibrationRequested, context: ActionContext): CalibrationCompleted {
            context.share(ExecutedStep("calibrate:${request.id}"))
            return CalibrationCompleted(request.id)
        }
    }

    @Agent(description = "Two-step calibration path whose only off-chain input is the request")
    inner class TwoStepAgent {

        @Action(canRerun = true, value = 0.5)
        fun prepKit(request: CalibrationRequested, context: ActionContext): CalibrationKit {
            context.share(ExecutedStep("prepKit:${request.id}"))
            return CalibrationKit(request.id)
        }

        @Action(canRerun = true, value = 0.9)
        @AchievesGoal(description = "Calibration completed", value = 1.0)
        fun calibrate(kit: CalibrationKit, context: ActionContext): CalibrationCompleted {
            context.share(ExecutedStep("calibrate:${kit.id}"))
            return CalibrationCompleted(kit.id)
        }
    }

    @Agent(description = "Calibration reading an evolved request and standing zone info")
    inner class DualInputAgent {

        @Action(canRerun = true, value = 0.9)
        @AchievesGoal(description = "Calibration completed", value = 1.0)
        fun calibrate(request: CalibrationRequested, zone: ZoneInfo, context: ActionContext): CalibrationCompleted {
            context.share(ExecutedStep("calibrate:${request.id}@${zone.name}"))
            return CalibrationCompleted(request.id)
        }
    }

    @Agent(description = "Completing action that fails on its first attempt")
    inner class FlakyAgent {

        var attempts = 0

        @Action(canRerun = true, value = 0.9)
        @AchievesGoal(description = "Calibration completed", value = 1.0)
        fun calibrate(request: CalibrationRequested, context: ActionContext): CalibrationCompleted {
            attempts++
            context.share(ExecutedStep("attempt:$attempts"))
            if (attempts == 1) {
                throw IllegalStateException("flaky calibration")
            }
            return CalibrationCompleted(request.id)
        }
    }

    @Agent(description = "Calibration episode with a non-rerunnable completing action")
    inner class OneShotAgent {

        @Action(value = 0.9)
        @AchievesGoal(description = "Calibration completed", value = 1.0)
        fun calibrate(request: CalibrationRequested, context: ActionContext): CalibrationCompleted {
            context.share(ExecutedStep("calibrate:${request.id}"))
            return CalibrationCompleted(request.id)
        }
    }

    @Agent(description = "Two independently achievable chain steps feeding one goal")
    inner class ParallelStepAgent {

        @Action(canRerun = true, value = 0.5)
        fun makePartA(request: CalibrationRequested, context: ActionContext): PartA {
            context.share(ExecutedStep("partA:${request.id}"))
            return PartA(request.id)
        }

        @Action(canRerun = true, value = 0.5)
        fun makePartB(request: CalibrationRequested, context: ActionContext): PartB {
            context.share(ExecutedStep("partB:${request.id}"))
            return PartB(request.id)
        }

        @Action(canRerun = true, value = 0.9)
        @AchievesGoal(description = "Calibration completed", value = 1.0)
        fun assemble(a: PartA, b: PartB, context: ActionContext): CalibrationCompleted {
            context.share(ExecutedStep("assemble:${a.id}"))
            return CalibrationCompleted(a.id)
        }
    }

    @Test
    fun `completed occurrences emit one consumed event each`() {
        val events = mutableListOf<AgentProcessEvent>()
        val listener = object : AgenticEventListener {
            override fun onProcessEvent(event: AgentProcessEvent) {
                events += event
            }
        }
        val process = evolvingProcess(CalibrationAgent(), listener = listener)
        repeat(3) { n ->
            process.evolve(CalibrationRequested("cal-$n"))
            process.run()
        }
        assertNull(process.last<CalibrationRequested>(), "Every occurrence was consumed")
        assertEquals(
            3,
            events.filterIsInstance<OccurrenceConsumedEvent>()
                .count { it.agentProcess.id == process.id },
        )
    }

    @Test
    fun `an evolving concurrent process executes one action per tick - attribution is single-threaded bookkeeping`() {
        // The current planner session advertises one child-capacity slot,
        // so a ConcurrentAgentProcess preserves the same serial directive
        // contract. Wider admission is future planner-session policy.
        val batches = mutableListOf<MutableList<String>>()
        val callback = object : AgentProcessCallback {
            override fun beforeActionLaunched(process: AgentProcess) {
                batches.add(mutableListOf())
            }

            override fun onActionLaunched(process: AgentProcess, action: CoreAction) {
                batches.last().add(action.name)
            }

            override fun onActionCompleted(process: AgentProcess, action: CoreAction) {}
        }
        val blackboard = InMemoryBlackboard()
        val agent = AgentMetadataReader().createAgentMetadata(ParallelStepAgent()) as CoreAgent
        val process = ConcurrentAgentProcess(
            "evolving-contract-serial",
            null,
            agent,
            ProcessOptions.DEFAULT.withEvolving(),
            blackboard,
            dummyPlatformServices(),
            DefaultPlannerFactory,
            Instant.now(),
            callbacks = listOf(callback),
        )
        process.evolve(CalibrationRequested("cal-1"))

        val result = process.run()

        val steps = result.objects.filterIsInstance<ExecutedStep>().map { it.name }
        assertEquals(
            setOf("partA:cal-1", "partB:cal-1", "assemble:cal-1"), steps.toSet(),
            "The whole chain ran",
        )
        assertNull(result.last<CalibrationRequested>(), "The occurrence was consumed")
        assertTrue(
            batches.all { it.size <= 1 },
            "An evolving process executes one action per tick, never a concurrent batch: got $batches",
        )
    }

    @Test
    fun `planner grounding survives a null thread context classloader`() {
        // Occurrence type grounding must use the defining classloader: a
        // null or foreign TCCL must not turn deployment detail into missing
        // planner capability.
        val agent = AgentMetadataReader().createAgentMetadata(CalibrationAgent()) as CoreAgent
        val thread = Thread.currentThread()
        val original = thread.contextClassLoader
        thread.contextClassLoader = null
        try {
            val process = SimpleAgentProcess(
                "evolving-contract-tccl",
                null,
                agent,
                ProcessOptions.DEFAULT.withEvolving(),
                InMemoryBlackboard(),
                dummyPlatformServices(),
                DefaultPlannerFactory,
                Instant.now(),
            )
            process.evolve(CalibrationRequested("cal-1"))
            val result = process.run()
            assertNull(result.last<CalibrationRequested>(), "The planner grounded and consumed the occurrence")
        } finally {
            thread.contextClassLoader = original
        }
    }

    @Test
    fun `two equal facts are two occurrences - identity decides, never equality`() {
        // Every arrival structure is keyed by instance identity: two facts
        // with equal content are two requests for work, and a refactor that
        // switched any of that bookkeeping to equality would merge them
        val process = evolvingProcess(CalibrationAgent())
        process.evolve(CalibrationRequested("cal-1"))
        process.evolve(CalibrationRequested("cal-1"))

        val result = process.run()

        assertEquals(AgentProcessStatusCode.STUCK, result.status)
        assertNull(result.last<CalibrationRequested>(), "Both occurrences were consumed")
        val steps = result.objects.filterIsInstance<ExecutedStep>().map { it.name }
        assertEquals(
            listOf("calibrate:cal-1", "calibrate:cal-1"), steps,
            "Two equal facts ran two full episodes",
        )
    }

    @Test
    fun `evolve without the declaration fails with guidance`() {
        val agent = AgentMetadataReader().createAgentMetadata(CalibrationAgent()) as CoreAgent
        val process = SimpleAgentProcess(
            "evolving-contract-plain",
            null,
            agent,
            ProcessOptions.DEFAULT,
            InMemoryBlackboard(),
            dummyPlatformServices(),
            DefaultPlannerFactory,
            Instant.now(),
        )
        val rejection = assertThrows<IllegalArgumentException> {
            process.evolve(CalibrationRequested("cal-1"))
        }
        assertTrue(
            "withEvolving" in rejection.message!!,
            "The rejection tells the caller what to declare: ${rejection.message}",
        )
    }

    @Test
    fun `a child of a non-evolving parent cannot evolve - no ancestor can receive the fact`() {
        val agent = AgentMetadataReader().createAgentMetadata(CalibrationAgent()) as CoreAgent
        val parent = SimpleAgentProcess(
            "evolving-contract-plain-parent",
            null,
            agent,
            ProcessOptions.DEFAULT,
            InMemoryBlackboard(),
            dummyPlatformServices(),
            DefaultPlannerFactory,
            Instant.now(),
        )
        val platform = parent.processContext.platformServices.agentPlatform
        val child = platform.createChildProcess(agent, parent)
        val rejection = assertThrows<IllegalArgumentException> {
            child.evolve(CalibrationRequested("cal-1"))
        }
        assertTrue(
            "withEvolving" in rejection.message!!,
            "The rejection tells the caller what to declare: ${rejection.message}",
        )
    }

    private fun evolvingProcess(
        agentInstance: Any,
        vararg seeds: Any,
        objective: GoalTarget? = null,
        listener: AgenticEventListener? = null,
    ): SimpleAgentProcess {
        val blackboard = InMemoryBlackboard()
        seeds.forEach { blackboard.addObject(it) }
        val agent = AgentMetadataReader().createAgentMetadata(agentInstance) as CoreAgent
        var options = objective?.let { ProcessOptions.DEFAULT.withEvolving(it) }
            ?: ProcessOptions.DEFAULT.withEvolving()
        listener?.let { options = options.withListener(it) }
        return SimpleAgentProcess(
            "evolving-contract",
            null,
            agent,
            options,
            blackboard,
            dummyPlatformServices(),
            DefaultPlannerFactory,
            Instant.now(),
        )
    }

    @Test
    fun `overlapping occurrences are admitted serially in arrival order`() {
        val process = evolvingProcess(CalibrationAgent())
        process.evolve(CalibrationRequested("cal-A"))
        process.evolve(CalibrationRequested("cal-B"))

        val result = process.run()

        assertEquals(AgentProcessStatusCode.STUCK, result.status)
        val steps = result.objects.filterIsInstance<ExecutedStep>().map { it.name }
        assertEquals(
            listOf("calibrate:cal-A", "calibrate:cal-B"), steps,
            "One occurrence is active at a time; later arrivals queue FIFO and are " +
                    "hidden until admission, so each chain binds its own request",
        )
        assertNull(result.last<CalibrationRequested>(), "Both occurrences consumed")
    }

    @Test
    fun `a two-step episode reruns the whole chain fresh - intermediates are consumed`() {
        val process = evolvingProcess(TwoStepAgent())
        process.evolve(CalibrationRequested("cal-1"))

        val parked = process.run()
        assertEquals(AgentProcessStatusCode.STUCK, parked.status, "No stale intermediate may keep the goal reachable")
        assertNull(parked.last<CalibrationKit>(), "The kit manufactured on the episode path is consumed")
        assertNull(parked.last<CalibrationRequested>())
        assertNull(parked.last<CalibrationCompleted>())

        process.evolve(CalibrationRequested("cal-2"))
        val rearmed = parked.run()

        assertEquals(AgentProcessStatusCode.STUCK, rearmed.status)
        val steps = rearmed.objects.filterIsInstance<ExecutedStep>().map { it.name }
        assertEquals(
            listOf("prepKit:cal-1", "calibrate:cal-1", "prepKit:cal-2", "calibrate:cal-2"), steps,
            "The second occurrence replans the entire chain with a fresh kit",
        )
    }

    @Test
    fun `a blocked active episode stalls its queue - the serial admission fine print`() {
        // AIMA 3e p. 405: a nonoverlapping sequence avoids all conflicts
        // "provided that each action is feasible by itself". When the active
        // episode is blocked on a missing standing enabler, the queue waits
        // behind it. Head-of-line blocking is the accepted cost of serial
        // admission
        val process = evolvingProcess(DualInputAgent())
        process.evolve(CalibrationRequested("cal-1"))
        process.evolve(CalibrationRequested("cal-2"))

        val stalled = process.run()

        assertEquals(AgentProcessStatusCode.STUCK, stalled.status, "No ZoneInfo, so the chain cannot start")
        assertTrue(stalled.objects.filterIsInstance<CalibrationRequested>().isEmpty())

        stalled.addObject(ZoneInfo("zone-9"))
        val resumed = stalled.run()

        assertEquals(AgentProcessStatusCode.STUCK, resumed.status, "Both episodes completed, then a clean park")
        assertNull(resumed.last<CalibrationRequested>(), "Both occurrences consumed in arrival order")
        assertNotNull(resumed.last<ZoneInfo>(), "The enabler is standing state and survives both episodes")
    }

    @Test
    fun `canRerun scopes to the occurrence - a non-rerunnable action runs once per child`() {
        // Execution history is process-scoped, so canRerun = false means
        // once per occurrence's child, never once per mission: occurrences
        // are independent (AIMA 3e ch 2). Within one chain it still means
        // exactly once
        val process = evolvingProcess(OneShotAgent())
        process.evolve(CalibrationRequested("cal-1"))

        val parked = process.run()
        assertEquals(AgentProcessStatusCode.STUCK, parked.status)

        process.evolve(CalibrationRequested("cal-2"))
        val after = parked.run()

        assertEquals(AgentProcessStatusCode.STUCK, after.status)
        val steps = after.objects.filterIsInstance<ExecutedStep>().map { it.name }
        assertEquals(
            listOf("calibrate:cal-1", "calibrate:cal-2"), steps,
            "Each occurrence ran its non-rerunnable action exactly once, in its own child",
        )
    }

    @Test
    fun `the chain binds its episode's request even when a plain fact of the same type is newer`() {
        // The selected occurrence is last in the fresh child. A same-typed
        // standing fact remains legitimate root work after the episode.
        val recorder = ProcessEventRecorder()
        val process = evolvingProcess(DualInputAgent(), ZoneInfo("zone-9"), listener = recorder)
        process.evolve(CalibrationRequested("cal-1"))
        process.addObject(CalibrationRequested("cal-2"))

        val result = process.run()

        assertEquals("cal-1", recorder.childProcesses(process).single().last<CalibrationCompleted>()?.id)
        val steps = result.objects.filterIsInstance<ExecutedStep>().map { it.name }
        assertEquals(
            listOf("calibrate:cal-1@zone-9", "calibrate:cal-2@zone-9"), steps,
            "The child bound its occurrence before the root handled standing work",
        )
        assertEquals("cal-2", result.last<CalibrationRequested>()?.id, "The plain fact survives, unbound and unconsumed")
        assertEquals("cal-2", result.last<CalibrationCompleted>()?.id)
    }

    @Test
    fun `evolving a standing-state type is obeyed - designation trusts the publisher`() {
        // The flip side of per-instance power: evolve the wrong kind of
        // thing and the framework obeys. The zone becomes the occurrence and
        // is consumed at completion. Pinned so it is documented behavior
        val process = evolvingProcess(DualInputAgent())
        process.evolve(ZoneInfo("zone-9"))
        process.addObject(CalibrationRequested("cal-1"))

        val result = process.run()

        val steps = result.objects.filterIsInstance<ExecutedStep>().map { it.name }
        assertEquals(listOf("calibrate:cal-1@zone-9"), steps, "The chain ran, reading the plain request as input")
        assertNull(result.last<ZoneInfo>(), "The evolved zone was the occurrence and was consumed")
        assertEquals("cal-1", result.last<CalibrationRequested>()?.id, "The plain request was input, never an occurrence")
    }

    @Test
    fun `episode completion emits lifecycle events without completing the parent process`() {
        val events = mutableListOf<AgentProcessEvent>()
        val listener = object : AgenticEventListener {
            override fun onProcessEvent(event: AgentProcessEvent) {
                events.add(event)
            }
        }
        val process = evolvingProcess(CalibrationAgent(), listener = listener)
        val occurrenceId = process.evolve(CalibrationRequested("cal-1"))

        val result = process.run()

        assertEquals(AgentProcessStatusCode.STUCK, result.status)
        val parentGoalEvents = events.filterIsInstance<GoalAchievedEvent>()
            .filter { it.agentProcess.id == result.id }
        assertTrue(parentGoalEvents.isEmpty(), "An episode is not a parent goal achievement")
        val finished = events.filterIsInstance<EpisodeFinishedEvent>()
            .single { it.agentProcess.id == result.id && it.occurrenceId == occurrenceId }
        val consumed = events.filterIsInstance<OccurrenceConsumedEvent>()
            .single { it.agentProcess.id == result.id && it.occurrenceId == occurrenceId }
        assertEquals(finished.episodeId, consumed.episodeId)
        assertTrue(events.indexOf(finished) < events.indexOf(consumed))
        assertEquals(
            0,
            events.count { it is AgentProcessFinishedEvent && it.agentProcess.id == result.id },
            "Nonterminal completion never finishes the evolving process",
        )
    }

    @Test
    fun `root mission completion emits goal and finished events`() {
        val events = mutableListOf<AgentProcessEvent>()
        val listener = object : AgenticEventListener {
            override fun onProcessEvent(event: AgentProcessEvent) {
                events.add(event)
            }
        }
        val process = evolvingProcess(
            DualInputAgent(),
            ZoneInfo("zone-9"),
            CalibrationRequested("cal-1"),
            objective = GoalTarget.output(CalibrationCompleted::class.java),
            listener = listener,
        )

        val result = process.run()

        assertEquals(AgentProcessStatusCode.COMPLETED, result.status)
        val goalEvents = events.filterIsInstance<GoalAchievedEvent>()
        assertEquals(1, goalEvents.size, "One root-mission achievement")
        assertTrue(events.any { it is AgentProcessFinishedEvent }, "Terminal completion emits a finished event")
    }

    @Test
    fun `concurrent process shares the episode contract - park and rearm through evolve`() {
        val blackboard = InMemoryBlackboard()
        val agent = AgentMetadataReader().createAgentMetadata(CalibrationAgent()) as CoreAgent
        val process = ConcurrentAgentProcess(
            "evolving-contract-concurrent",
            null,
            agent,
            ProcessOptions.DEFAULT.withEvolving(),
            blackboard,
            dummyPlatformServices(),
            DefaultPlannerFactory,
            Instant.now(),
        )
        process.evolve(CalibrationRequested("cal-1"))

        val parked = process.run()
        assertEquals(AgentProcessStatusCode.STUCK, parked.status)
        assertNull(parked.last<CalibrationRequested>())

        process.evolve(CalibrationRequested("cal-2"))
        val rearmed = parked.run()

        assertEquals(AgentProcessStatusCode.STUCK, rearmed.status)
        val steps = rearmed.objects.filterIsInstance<ExecutedStep>().map { it.name }
        assertEquals(listOf("calibrate:cal-1", "calibrate:cal-2"), steps)
    }

    @Test
    fun `a frame-achieved goal is not reopened by runtime inference`() {
        // The condition planner owns whether a prior root achievement can
        // serve a later occurrence. The runtime must not shadow outputs or
        // clear planner state to force a rerun.
        val events = mutableListOf<AgentProcessEvent>()
        val listener = object : AgenticEventListener {
            override fun onProcessEvent(event: AgentProcessEvent) {
                events += event
            }
        }
        val process = evolvingProcess(
            DualInputAgent(),
            ZoneInfo("zone-9"),
            CalibrationRequested("cal-1"),
            listener = listener,
        )

        val rootRun = process.run()
        assertEquals(AgentProcessStatusCode.STUCK, rootRun.status)
        assertEquals(
            1, rootRun.objects.filterIsInstance<ExecutedStep>().size,
            "The root planner ran the seeded work once",
        )

        val occurrence = process.evolve(CalibrationRequested("cal-2"))
        val reopened = rootRun.run()

        assertEquals(AgentProcessStatusCode.STUCK, reopened.status, "The episode completed, then a clean park")
        val steps = reopened.objects.filterIsInstance<ExecutedStep>().map { it.name }
        assertEquals(listOf("calibrate:cal-1@zone-9"), steps)
        assertTrue(
            events.filterIsInstance<OccurrenceConsumedEvent>()
                .none { it.agentProcess.id == process.id && it.occurrenceId == occurrence },
        )
        assertEquals(
            "cal-1", reopened.last<CalibrationRequested>()?.id,
            "The episode consumed its own occurrence; the root standing fact survives",
        )
    }

    @Agent(description = "Two request-driven goals sharing a prep step off standing zone info")
    inner class SharedPrepAgent {

        @Action(canRerun = true, value = 0.5)
        fun prep(zone: ZoneInfo, context: ActionContext): CalibrationKit {
            context.share(ExecutedStep("prep:${zone.name}"))
            return CalibrationKit(zone.name)
        }

        @Action(canRerun = true, value = 0.9)
        @AchievesGoal(description = "Alpha done", value = 1.0)
        fun finishAlpha(kit: CalibrationKit, request: AlphaRequested, context: ActionContext): AlphaDone {
            context.share(ExecutedStep("alpha:${request.id}"))
            return AlphaDone(request.id)
        }

        @Action(canRerun = true, value = 0.8)
        @AchievesGoal(description = "Beta done", value = 0.9)
        fun finishBeta(kit: CalibrationKit, request: BetaRequested, context: ActionContext): BetaDone {
            context.share(ExecutedStep("beta:${request.id}"))
            return BetaDone(request.id)
        }
    }

    @Test
    fun `an action serving a live episode is never gated by a dormant sibling`() {
        // Both planner missions share the prep step. Completing alpha must
        // not prevent a later beta occurrence from receiving a fresh child
        // mission containing that step.
        val process = evolvingProcess(SharedPrepAgent(), ZoneInfo("zone-9"))
        process.evolve(AlphaRequested("a-1"))

        val afterAlpha = process.run()
        assertEquals(AgentProcessStatusCode.STUCK, afterAlpha.status)
        assertNull(afterAlpha.last<AlphaRequested>(), "The alpha occurrence was consumed")

        process.evolve(BetaRequested("b-1"))
        val afterBeta = afterAlpha.run()

        assertEquals(AgentProcessStatusCode.STUCK, afterBeta.status, "Both episodes completed, then a clean park")
        val steps = afterBeta.objects.filterIsInstance<ExecutedStep>().map { it.name }
        assertEquals(
            listOf("prep:zone-9", "alpha:a-1", "prep:zone-9", "beta:b-1"), steps,
            "The beta episode received a fresh planner mission containing the shared prep",
        )
        assertNull(afterBeta.last<BetaRequested>(), "The beta occurrence was consumed")
    }

    @Agent(description = "A kickoff that evolves a contested signal before its enabling fact lands")
    inner class LateEnablementAgent {

        @Action(canRerun = true, value = 0.9)
        @AchievesGoal(description = "Kickoff done", value = 1.0)
        fun kickoff(seed: MissionReport, context: ActionContext): KickoffDone {
            context.evolve(SignalReceived("s-1"))
            context.share(Enablement("e-1"))
            return KickoffDone("k-${seed.samples}")
        }

        @Action(canRerun = true, value = 0.1)
        @AchievesGoal(description = "Signal archived", value = 0.3)
        fun archive(signal: SignalReceived, context: ActionContext): SignalArchived {
            context.share(ExecutedStep("archive:${signal.id}"))
            return SignalArchived(signal.id)
        }

        @Action(canRerun = true, value = 0.9)
        @AchievesGoal(description = "Signal triaged", value = 1.0)
        fun triage(signal: SignalReceived, e: Enablement, context: ActionContext): SignalTriaged {
            context.share(ExecutedStep("triage:${signal.id}"))
            return SignalTriaged(signal.id)
        }
    }

    @Test
    fun `contested ownership resolves at the next tick - the arrival's world must materialize first`() {
        // A mid-action evolve precedes its own action's remaining effects:
        // the enabling fact lands after the evolve call. Deciding ownership
        // at the next planning tick lets the arrival's world settle, so the
        // higher-value triage wins instead of the only goal plannable at
        // call time
        val process = evolvingProcess(LateEnablementAgent(), MissionReport(7))

        val result = process.run()

        val steps = result.objects.filterIsInstance<ExecutedStep>().map { it.name }
        assertEquals(
            listOf("triage:s-1"), steps,
            "Ownership was decided against the settled world, not the mid-action snapshot",
        )
        assertNull(result.last<SignalArchived>(), "The fallback goal never ran")
        assertNull(result.last<SignalReceived>(), "The triage episode consumed the occurrence")
    }

    @Test
    fun `evolve publishes through the process event path`() {
        val events = mutableListOf<AgentProcessEvent>()
        val listener = object : AgenticEventListener {
            override fun onProcessEvent(event: AgentProcessEvent) {
                events.add(event)
            }
        }
        val process = evolvingProcess(CalibrationAgent(), listener = listener)
        val fact = CalibrationRequested("cal-1")

        process.evolve(fact)

        assertTrue(
            events.filterIsInstance<OccurrenceAcceptedEvent>().any { it.occurrence === fact },
            "An evolved arrival has an explicit acceptance event without becoming standing state",
        )
    }

    @Test
    fun `an ambiguous named objective fails fast`() {
        // GoalTarget.Named promises exactly one goal; the root mission must
        // enforce that invariant before the planner session opens.
        val blackboard = InMemoryBlackboard()
        val agent = AgentMetadataReader().createAgentMetadata(CalibrationAgent()) as CoreAgent
        // A distinct instance sharing the name: an identical copy would
        // deduplicate in the goal set and no ambiguity would exist
        val duplicated = agent.copy(goals = agent.goals + agent.goals.first().copy(value = { 0.123 }))

        val exception = assertThrows<IllegalArgumentException> {
            SimpleAgentProcess(
                "evolving-contract-ambiguous-objective",
                null,
                duplicated,
                ProcessOptions.DEFAULT.withEvolving(GoalTarget.named(agent.goals.first().name)),
                blackboard,
                dummyPlatformServices(),
                DefaultPlannerFactory,
                Instant.now(),
            )
        }
        assertTrue(
            "exactly one" in exception.message!!,
            "A named objective must identify exactly one goal: ${exception.message}",
        )
    }

    @Agent(description = "Two candidates for one signal, both blocked at arrival")
    inner class CommittedOwnershipAgent {

        @Action(canRerun = true, value = 0.5)
        @AchievesGoal(description = "Signal archived", value = 0.5)
        fun archive(signal: SignalReceived, kit: CalibrationKit, context: ActionContext): SignalArchived {
            context.share(ExecutedStep("archive:${signal.id}"))
            return SignalArchived(signal.id)
        }

        @Action(canRerun = true, value = 0.9)
        @AchievesGoal(description = "Signal triaged", value = 1.0)
        fun triage(signal: SignalReceived, e: Enablement, context: ActionContext): SignalTriaged {
            context.share(ExecutedStep("triage:${signal.id}"))
            return SignalTriaged(signal.id)
        }
    }

    @Test
    fun `contested ownership waits for merit - least commitment at the routing boundary`() {
        // Least commitment (AIMA 3e p. 391): when no candidate can plan,
        // nothing owns the occurrence - the choice stays unbound until a
        // candidate becomes feasible, and is then decided by plan value,
        // deterministically. No declaration-order roulette
        val process = evolvingProcess(CommittedOwnershipAgent())
        process.evolve(SignalReceived("s-1"))

        val stalled = process.run()
        assertEquals(AgentProcessStatusCode.STUCK, stalled.status, "Both candidates blocked; nothing owns, nothing runs")
        assertTrue(
            stalled.objects.filterIsInstance<ExecutedStep>().isEmpty(),
            "Nothing ran while ownership stayed unbound",
        )

        stalled.addObject(Enablement("e-1"))
        val triaged = stalled.run()

        assertEquals(AgentProcessStatusCode.STUCK, triaged.status)
        val steps = triaged.objects.filterIsInstance<ExecutedStep>().map { it.name }
        assertEquals(
            listOf("triage:s-1"), steps,
            "The first feasible candidate owned and handled the occurrence, by merit",
        )
        assertNull(triaged.last<SignalReceived>(), "The occurrence was consumed by its merited owner")

        stalled.addObject(CalibrationKit("k-1"))
        val after = stalled.run()
        assertEquals(
            listOf("triage:s-1"),
            after.objects.filterIsInstance<ExecutedStep>().map { it.name },
            "The rival's later enabler changed nothing: ownership, once committed, is permanent",
        )
    }

    @Test
    fun `evolve on a non-evolving process fails fast`() {
        val blackboard = InMemoryBlackboard()
        val agent = AgentMetadataReader().createAgentMetadata(CalibrationAgent()) as CoreAgent
        val process = SimpleAgentProcess(
            "evolving-contract-default-mode",
            null,
            agent,
            ProcessOptions.DEFAULT,
            blackboard,
            dummyPlatformServices(),
            DefaultPlannerFactory,
            Instant.now(),
        )

        val exception = assertThrows<IllegalArgumentException> {
            process.evolve(CalibrationRequested("cal-1"))
        }
        assertTrue(
            "withEvolving" in exception.message!!,
            "The boundary names the missing declaration: ${exception.message}",
        )
    }

    @Test
    fun `a blank named goal target fails fast`() {
        assertThrows<IllegalArgumentException> {
            GoalTarget.named("")
        }
    }
}
