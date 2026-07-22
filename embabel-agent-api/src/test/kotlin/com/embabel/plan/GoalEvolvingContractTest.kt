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
import com.embabel.agent.api.event.EpisodeCompletedEvent
import com.embabel.agent.api.event.GoalAchievedEvent
import com.embabel.agent.api.event.ObjectAddedEvent
import com.embabel.agent.core.Agent as CoreAgent
import com.embabel.agent.core.AgentProcessStatusCode
import com.embabel.agent.core.EpisodeExecution
import com.embabel.agent.core.Evolving
import com.embabel.agent.core.GoalTarget
import com.embabel.agent.core.ProcessOptions
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
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

data class SignalReceived(val id: String)
data class Enablement(val id: String)
data class SignalTriaged(val id: String)
data class SignalArchived(val id: String)

/**
 * The episode contract, pinned in the derived dialect. Every pin here was
 * proven under the declared EpisodePolicy dialect first; when that surface
 * was deleted, the behaviors that define the contract - serial admission,
 * identity consumption, grounding, retry, event ordering, the canRerun
 * boundary - were ported rather than lost. Occurrences arrive only through
 * evolve(); everything else is standing state.
 */
class GoalEvolvingContractTest {

    @Agent(description = "Single-step calibration driven by evolved occurrences")
    inner class CalibrationAgent {

        @Action(canRerun = true, value = 0.9)
        @AchievesGoal(description = "Calibration completed", value = 1.0)
        fun calibrate(request: CalibrationRequested, context: ActionContext): CalibrationCompleted {
            context.addObject(ExecutedStep("calibrate:${request.id}"))
            return CalibrationCompleted(request.id)
        }
    }

    @Agent(description = "Two-step calibration path whose only off-chain input is the request")
    inner class TwoStepAgent {

        @Action(canRerun = true, value = 0.5)
        fun prepKit(request: CalibrationRequested, context: ActionContext): CalibrationKit {
            context.addObject(ExecutedStep("prepKit:${request.id}"))
            return CalibrationKit(request.id)
        }

        @Action(canRerun = true, value = 0.9)
        @AchievesGoal(description = "Calibration completed", value = 1.0)
        fun calibrate(kit: CalibrationKit, context: ActionContext): CalibrationCompleted {
            context.addObject(ExecutedStep("calibrate:${kit.id}"))
            return CalibrationCompleted(kit.id)
        }
    }

    @Agent(description = "Calibration reading an evolved request and standing zone info")
    inner class DualInputAgent {

        @Action(canRerun = true, value = 0.9)
        @AchievesGoal(description = "Calibration completed", value = 1.0)
        fun calibrate(request: CalibrationRequested, zone: ZoneInfo, context: ActionContext): CalibrationCompleted {
            context.addObject(ExecutedStep("calibrate:${request.id}@${zone.name}"))
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
            context.addObject(ExecutedStep("attempt:$attempts"))
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
            context.addObject(ExecutedStep("calibrate:${request.id}"))
            return CalibrationCompleted(request.id)
        }
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
        var options = ProcessOptions.DEFAULT.withEvolving(Evolving(objective, EpisodeExecution.IN_PROCESS))
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
        val visible = stalled.objects.filterIsInstance<CalibrationRequested>()
        assertEquals(listOf("cal-1"), visible.map { it.id }, "Only the active request is visible; cal-2 waits hidden")

        stalled.addObject(ZoneInfo("zone-9"))
        val resumed = stalled.run()

        assertEquals(AgentProcessStatusCode.STUCK, resumed.status, "Both episodes completed, then a clean park")
        assertNull(resumed.last<CalibrationRequested>(), "Both occurrences consumed in arrival order")
        assertNotNull(resumed.last<ZoneInfo>(), "The enabler is standing state and survives both episodes")
    }

    @Test
    fun `a failing completing action leaves the request unconsumed for retry`() {
        val agent = FlakyAgent()
        val process = evolvingProcess(agent)
        process.evolve(CalibrationRequested("cal-1"))

        runCatching { process.run() }
        assertNotNull(process.last<CalibrationRequested>(), "A failed attempt must not consume the request")

        val retried = process.run()

        assertEquals(AgentProcessStatusCode.STUCK, retried.status)
        assertEquals(2, agent.attempts, "The second attempt succeeded")
        assertNull(retried.last<CalibrationRequested>(), "The successful completion consumed the request")
    }

    @Test
    fun `episode rerun rides existing canRerun - a non-rerunnable action does not re-fire`() {
        val process = evolvingProcess(OneShotAgent())
        process.evolve(CalibrationRequested("cal-1"))

        val parked = process.run()
        assertEquals(AgentProcessStatusCode.STUCK, parked.status)

        process.evolve(CalibrationRequested("cal-2"))
        val after = parked.run()

        // The episode rearms, but action re-execution rides the existing
        // canRerun contract: a non-rerunnable completing action stays run
        assertEquals(AgentProcessStatusCode.STUCK, after.status)
        val steps = after.objects.filterIsInstance<ExecutedStep>().map { it.name }
        assertEquals(listOf("calibrate:cal-1"), steps, "canRerun = false blocked the second occurrence")
    }

    @Test
    fun `the chain binds its episode's request even when a plain fact of the same type is newer`() {
        // Grounding: while a chain action runs, the episode's request is the
        // only visible instance of its own class. Standard ground-action
        // semantics (AIMA 3e SS10.1), per occurrence
        val process = evolvingProcess(DualInputAgent(), ZoneInfo("zone-9"))
        process.evolve(CalibrationRequested("cal-1"))
        process.addObject(CalibrationRequested("cal-2"))

        val result = process.run()

        val steps = result.objects.filterIsInstance<ExecutedStep>().map { it.name }
        assertEquals(
            listOf("calibrate:cal-1@zone-9"), steps,
            "The chain executed against its episode's request, not the newest same-type fact",
        )
        assertEquals("cal-2", result.last<CalibrationRequested>()?.id, "The plain fact survives, unbound and unconsumed")
        assertNull(result.last<CalibrationCompleted>(), "The episode's own output was consumed")
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
    fun `episode completion emits EpisodeCompletedEvent after consumption and never a finished event`() {
        val events = mutableListOf<AgentProcessEvent>()
        var requestVisibleAtEvent: Boolean? = null
        val listener = object : AgenticEventListener {
            override fun onProcessEvent(event: AgentProcessEvent) {
                events.add(event)
                if (event is EpisodeCompletedEvent) {
                    requestVisibleAtEvent =
                        event.agentProcess.last(CalibrationRequested::class.java) != null
                }
            }
        }
        val process = evolvingProcess(CalibrationAgent(), listener = listener)
        process.evolve(CalibrationRequested("cal-1"))

        val result = process.run()

        assertEquals(AgentProcessStatusCode.STUCK, result.status)
        val goalEvents = events.filterIsInstance<GoalAchievedEvent>()
        assertEquals(1, goalEvents.size, "One episode, one goal event")
        assertTrue(goalEvents.single() is EpisodeCompletedEvent, "Episode completions are distinguishable by type")
        assertEquals(false, requestVisibleAtEvent, "Consumption must precede the event that announces it")
        assertEquals(0, events.count { it is AgentProcessFinishedEvent }, "Nonterminal completion never finishes")
    }

    @Test
    fun `founding completion emits goal and finished events`() {
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
        assertEquals(1, goalEvents.size, "One founding achievement")
        assertTrue(goalEvents.single() !is EpisodeCompletedEvent, "The founding completion is terminal, not an episode")
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
            ProcessOptions.DEFAULT.withEvolving(Evolving(execution = EpisodeExecution.IN_PROCESS)),
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
    fun `an occurrence reopens a frame-achieved goal`() {
        // The goal was achieved as state, and a fresh request makes it
        // unachieved by definition: admission clears the frame achievement
        // so the planner can serve the new occurrence
        val process = evolvingProcess(DualInputAgent(), ZoneInfo("zone-9"), CalibrationRequested("cal-1"))

        val founding = process.run()
        assertEquals(AgentProcessStatusCode.STUCK, founding.status)
        assertEquals(
            1, founding.objects.filterIsInstance<ExecutedStep>().size,
            "The founding frame ran the seeded work once",
        )

        process.evolve(CalibrationRequested("cal-2"))
        val reopened = founding.run()

        assertEquals(AgentProcessStatusCode.STUCK, reopened.status, "The episode completed, then a clean park")
        val steps = reopened.objects.filterIsInstance<ExecutedStep>().map { it.name }
        assertEquals(
            listOf("calibrate:cal-1@zone-9", "calibrate:cal-2@zone-9"), steps,
            "The occurrence reopened the goal the founding frame had achieved",
        )
        assertEquals(
            "cal-1", reopened.last<CalibrationRequested>()?.id,
            "The episode consumed its own occurrence; the founding-frame fact survives",
        )
    }

    @Agent(description = "Two request-driven goals sharing a prep step off standing zone info")
    inner class SharedPrepAgent {

        @Action(canRerun = true, value = 0.5)
        fun prep(zone: ZoneInfo, context: ActionContext): CalibrationKit {
            context.addObject(ExecutedStep("prep:${zone.name}"))
            return CalibrationKit(zone.name)
        }

        @Action(canRerun = true, value = 0.9)
        @AchievesGoal(description = "Alpha done", value = 1.0)
        fun finishAlpha(kit: CalibrationKit, request: AlphaRequested, context: ActionContext): AlphaDone {
            context.addObject(ExecutedStep("alpha:${request.id}"))
            return AlphaDone(request.id)
        }

        @Action(canRerun = true, value = 0.8)
        @AchievesGoal(description = "Beta done", value = 0.9)
        fun finishBeta(kit: CalibrationKit, request: BetaRequested, context: ActionContext): BetaDone {
            context.addObject(ExecutedStep("beta:${request.id}"))
            return BetaDone(request.id)
        }
    }

    @Test
    fun `an action serving a live episode is never gated by a dormant sibling`() {
        // Two derived rules share the prep step. After the alpha episode
        // completes, its rule is dormant and would gate the shared prep,
        // but the beta episode is live and needs it: liveness wins
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
            "The beta episode reran the shared prep despite the dormant alpha rule",
        )
        assertNull(afterBeta.last<BetaRequested>(), "The beta occurrence was consumed")
    }

    @Agent(description = "A kickoff that evolves a contested signal before its enabling fact lands")
    inner class LateEnablementAgent {

        @Action(canRerun = true, value = 0.9)
        @AchievesGoal(description = "Kickoff done", value = 1.0)
        fun kickoff(seed: MissionReport, context: ActionContext): KickoffDone {
            (context.agentProcess as SimpleAgentProcess).evolve(SignalReceived("s-1"))
            context.addObject(Enablement("e-1"))
            return KickoffDone("k-${seed.samples}")
        }

        @Action(canRerun = true, value = 0.1)
        @AchievesGoal(description = "Signal archived", value = 0.3)
        fun archive(signal: SignalReceived, context: ActionContext): SignalArchived {
            context.addObject(ExecutedStep("archive:${signal.id}"))
            return SignalArchived(signal.id)
        }

        @Action(canRerun = true, value = 0.9)
        @AchievesGoal(description = "Signal triaged", value = 1.0)
        fun triage(signal: SignalReceived, e: Enablement, context: ActionContext): SignalTriaged {
            context.addObject(ExecutedStep("triage:${signal.id}"))
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
            events.filterIsInstance<ObjectAddedEvent>().any { it.value === fact },
            "An evolved arrival is an object addition like any other, visible to listeners",
        )
    }

    @Test
    fun `an ambiguous named objective fails fast`() {
        // GoalTarget.Named promises exactly one goal; the objective must
        // enforce it even though derivation excludes duplicate names
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
