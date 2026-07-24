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
import com.embabel.agent.core.Agent as CoreAgent
import com.embabel.agent.core.AgentProcessStatusCode
import com.embabel.agent.core.GoalTarget
import com.embabel.agent.core.ProcessOptions
import com.embabel.agent.core.last
import com.embabel.agent.core.support.InMemoryBlackboard
import com.embabel.agent.core.support.NIRVANA
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

data class PaintRequested(val id: String)
data class PreparedSurface(val id: String)
data class SurfacePainted(val id: String)
data class PaintCan(val color: String)

data class WeldsCompleted(val count: Int)
data class GripperEquipped(val id: String)
data class SupervisorNotified(val id: String)
data class RepairCompleted(val id: String)
data class ShiftReport(val welds: Int)

data class AlphaRequested(val id: String)
data class BetaRequested(val id: String)
data class SharedKit(val id: String)
data class AlphaDone(val id: String)
data class BetaDone(val id: String)

/**
 * Episode scenarios borrowed from Russell and Norvig, Artificial Intelligence:
 * A Modern Approach (3rd edition), chapters 10 and 11, run against the planner-session
 * evolving-mode contract. These are the high-level behaviors the book expects
 * of an online agent, pinned at scenario level so they survive refactoring.
 *
 * 1. The painting problem (SS11.3.3-11.4, pp. 421-425). The book's agent runs
 *    out of paint mid-plan because its model is wrong. Embabel's model is
 *    complete and GOAP plans full paths, so the job stalls before any work
 *    starts: no wasted prep, request unconsumed. When a can arrives the chain
 *    runs whole and the can survives consumption. The can is a reusable
 *    resource in SS11.1's CONSUME/USE vocabulary: used, not consumed. A
 *    second evolved job reuses it.
 * 2. The spot-welding robot (SS11.3.3, p. 422). The book's robot handles a
 *    fallen door mid-cycle and resumes its standing work: "the robot's
 *    behavior seems purposive rather than rote". Standing welds, two evolved
 *    doors, a four-step repair chain, and the shift's committed objective
 *    anchoring the end. The ascending-values requirement on HYBRID chain
 *    steps is dialect-independent and still applies.
 * 3. A Sussman-anomaly analogue (SS10.1.3 and exercise 10.7; Sacerdoti 1975).
 *    The classic anomaly needs interleaving because subplans interfere. The
 *    episode-shaped cousin is two goals sharing an intermediate type with
 *    different requests. The selected planner, rather than the evolving
 *    runtime, resolves the interference and value contest.
 * 4. The painting problem again, under the Hybrid planner. Per-tick value
 *    selection has no complete-path guarantee, so the book's predicament is
 *    real here: prep runs, the chain stalls mid-flight, and the intermediate
 *    is stranded until paint arrives. Completion still sweeps it. A
 *    rerunnable prefix instead spins on the blocked chain until the action
 *    budget terminates the process.
 */
class GoalEpisodeAimaTest {

    @Agent(description = "Painting robot that stalls when the paint can is missing")
    inner class PaintingRobotAgent {

        @Action(canRerun = true, value = 0.5)
        fun prepSurface(request: PaintRequested, context: ActionContext): PreparedSurface {
            context.share(ExecutedStep("prep:${request.id}"))
            return PreparedSurface(request.id)
        }

        // The can is standing state: nothing in scope produces it, and only
        // the evolved request is the occurrence
        @Action(canRerun = true, value = 0.9)
        @AchievesGoal(description = "Surface painted", value = 1.0)
        fun paint(surface: PreparedSurface, can: PaintCan, context: ActionContext): SurfacePainted {
            context.share(ExecutedStep("paint:${surface.id}"))
            return SurfacePainted(surface.id)
        }
    }

    @Agent(description = "Painting robot whose prep runs once, stranding the surface when paint is missing")
    inner class OneShotPrepPaintingAgent {

        @Action(value = 0.5)
        fun prepSurface(request: PaintRequested, context: ActionContext): PreparedSurface {
            context.share(ExecutedStep("prep:${request.id}"))
            return PreparedSurface(request.id)
        }

        @Action(canRerun = true, value = 0.9)
        @AchievesGoal(description = "Surface painted", value = 1.0)
        fun paint(surface: PreparedSurface, can: PaintCan, context: ActionContext): SurfacePainted {
            context.share(ExecutedStep("paint:${surface.id}"))
            return SurfacePainted(surface.id)
        }
    }

    @Agent(description = "Spot-welding robot that repairs fallen doors and resumes welding")
    inner class SpotWeldingRobotAgent {

        @Action(canRerun = true, value = 0.2)
        fun weld(welds: WeldsCompleted, context: ActionContext): WeldsCompleted {
            context.share(ExecutedStep("weld"))
            val next = WeldsCompleted(welds.count + 1)
            if (next.count == 2) {
                context.evolve(DoorFellOff("door-7"))
            }
            if (next.count == 4) {
                context.evolve(DoorFellOff("door-8"))
            }
            return next
        }

        @Condition(name = "shiftDone")
        fun shiftDone(welds: WeldsCompleted): Boolean = welds.count >= 6

        @Action(pre = ["shiftDone"], value = 0.9)
        @AchievesGoal(description = "Shift complete", value = 0.5)
        fun endShift(welds: WeldsCompleted): ShiftReport = ShiftReport(welds.count)

        // Chain values ascend. Under HYBRID's per-tick value selection with
        // canRerun = true, a step's input stays visible until completion
        // consumes it, so a later step must out-value an earlier one or the
        // chain re-runs its first step forever
        @Action(canRerun = true, value = 0.6)
        fun equipGripper(door: DoorFellOff, context: ActionContext): GripperEquipped {
            context.share(ExecutedStep("equipGripper:${door.id}"))
            return GripperEquipped(door.id)
        }

        @Action(canRerun = true, value = 0.7)
        fun reattachDoor(gripper: GripperEquipped, context: ActionContext): DoorReattached {
            context.share(ExecutedStep("reattachDoor:${gripper.id}"))
            return DoorReattached(gripper.id)
        }

        @Action(canRerun = true, value = 0.8)
        fun notifySupervisor(door: DoorReattached, context: ActionContext): SupervisorNotified {
            context.share(ExecutedStep("notifySupervisor:${door.id}"))
            return SupervisorNotified(door.id)
        }

        @Action(canRerun = true, value = 0.9)
        @AchievesGoal(description = "Repair completed", value = 1.0)
        fun swapBackToWelder(notified: SupervisorNotified, context: ActionContext): RepairCompleted {
            context.share(ExecutedStep("swapBack:${notified.id}"))
            return RepairCompleted(notified.id)
        }
    }

    @Agent(description = "Two request-driven chains sharing an intermediate type")
    inner class SharedIntermediateAgent {

        @Action(canRerun = true, value = 0.5)
        fun prepAlpha(request: AlphaRequested): SharedKit = SharedKit(request.id)

        @Action(canRerun = true, value = 0.5)
        fun prepBeta(request: BetaRequested): SharedKit = SharedKit(request.id)

        @Action(canRerun = true, value = 0.9)
        @AchievesGoal(description = "Alpha done", value = 1.0)
        fun finishAlpha(kit: SharedKit): AlphaDone = AlphaDone(kit.id)

        @Action(canRerun = true, value = 0.8)
        @AchievesGoal(description = "Beta done", value = 0.9)
        fun finishBeta(kit: SharedKit): BetaDone = BetaDone(kit.id)
    }

    private fun create(
        instance: Any,
        processId: String,
        options: ProcessOptions,
        vararg seeds: Any,
        recorder: ProcessEventRecorder? = null,
    ): SimpleAgentProcess {
        val blackboard = InMemoryBlackboard()
        seeds.forEach { blackboard.addObject(it) }
        val reader = AgentMetadataReader()
        val agent = reader.createAgentMetadata(instance) as CoreAgent
        val effectiveAgent = if (options.plannerType == PlannerType.HYBRID) {
            agent.copy(goals = agent.goals + NIRVANA)
        } else {
            agent
        }
        return SimpleAgentProcess(
            processId,
            null,
            effectiveAgent,
            recorder?.let(options::withListener) ?: options,
            blackboard,
            dummyPlatformServices(),
            DefaultPlannerFactory,
            Instant.now(),
        )
    }

    @Test
    fun `the painting problem - a mid-chain stall parks with the request unconsumed and resumes when paint arrives`() {
        val process = create(
            PaintingRobotAgent(),
            "aima-painting-problem",
            ProcessOptions.DEFAULT.withEvolving(),
        )
        process.evolve(PaintRequested("job-1"))

        val stalled = process.run()

        // The book's agent stalls mid-plan because its model wrongly says
        // paint is available (SS11.3.3's missing precondition). Embabel's
        // model is complete, and GOAP plans full paths, so it refuses to
        // start a chain it cannot finish. The stall happens before any
        // work: no wasted prep, request untouched
        assertEquals(AgentProcessStatusCode.STUCK, stalled.status)
        assertNull(stalled.last<PreparedSurface>(), "GOAP does not start a chain it cannot finish")
        assertTrue(
            stalled.objects.filterIsInstance<ExecutedStep>().isEmpty(),
            "No work ran before the stall",
        )

        stalled.addObject(PaintCan("red"))
        val painted = stalled.run()

        assertEquals(AgentProcessStatusCode.STUCK, painted.status)
        assertNull(painted.last<PreparedSurface>(), "Completion consumed the intermediate")
        assertNull(painted.last<SurfacePainted>(), "Completion consumed the satisfying output")
        assertNotNull(painted.last<PaintCan>(), "The can is used, not consumed: AIMA's reusable resource")

        process.evolve(PaintRequested("job-2"))
        val secondJob = painted.run()

        assertEquals(AgentProcessStatusCode.STUCK, secondJob.status)
        val steps = secondJob.objects.filterIsInstance<ExecutedStep>().map { it.name }
        assertEquals(
            listOf("prep:job-1", "paint:job-1", "prep:job-2", "paint:job-2"), steps,
            "The second evolved job reuses the same can through a fresh chain",
        )
    }

    @Test
    fun `the painting problem under hybrid - the child rung forecloses the stranded intermediate`() {
        // In the parent's walk, value selection without a complete-path
        // guarantee would run prep on its own netValue and strand the
        // intermediate when the paint runs out - the book's painting
        // predicament. The child rung forecloses it structurally: a chain
        // is valued by the full-path plan its child would run, so a
        // blocked chain never starts (p. 425 note 5)
        val process = create(
            OneShotPrepPaintingAgent(),
            "aima-painting-hybrid",
            ProcessOptions.DEFAULT
                .withPlannerType(PlannerType.HYBRID)
                .withEvolving(),
        )
        process.evolve(PaintRequested("job-1"))

        val stalled = process.run()

        assertEquals(AgentProcessStatusCode.STUCK, stalled.status)
        assertNull(stalled.last<PreparedSurface>(), "No doomed child, no stranded prep")

        stalled.addObject(PaintCan("red"))
        val painted = stalled.run()

        assertNull(painted.last<PreparedSurface>(), "Completion consumed the chain's own intermediate")
        assertNull(painted.last<SurfacePainted>(), "Completion consumed the satisfying output")
        assertNotNull(painted.last<PaintCan>(), "The can is still used, not consumed")
    }

    @Test
    fun `the painting problem under hybrid exposes the planner's native limitation`() {
        // HYBRID's NIRVANA walk can repeat a rerunnable prefix until the
        // child's own budget. Evolving Mode contains that behavior in one
        // child and reports the outcome; it does not reconstruct the graph
        // to predict or repair the planner's decision.
        val recorder = ProcessEventRecorder()
        val process = create(
            PaintingRobotAgent(),
            "aima-painting-hybrid-spin",
            ProcessOptions.DEFAULT
                .withPlannerType(PlannerType.HYBRID)
                .withEvolving(),
            recorder = recorder,
        )
        process.evolve(PaintRequested("job-1"))

        val result = process.run()

        assertEquals(AgentProcessStatusCode.STUCK, result.status, "A blocked chain parks, never terminates the budget")
        val preps = result.objects.filterIsInstance<ExecutedStep>().count { it.name == "prep:job-1" }
        assertEquals(50, preps, "The selected planner retained its native NIRVANA behavior")
        assertEquals(
            1, recorder.episodeStarts(process).size,
            "One observed attempt, contained by its boundary - the block is recorded, never re-spun",
        )
    }

    @Test
    fun `the spot-welding robot - purposive not rote`() {
        val process = create(
            SpotWeldingRobotAgent(),
            "aima-spot-welding",
            ProcessOptions.DEFAULT
                .withPlannerType(PlannerType.HYBRID)
                // Each DoorFellOff is one occurrence. The planner supplies
                // its child mission, while the shift's committed objective
                // anchors root completion.
                .withEvolving(GoalTarget.output(ShiftReport::class.java)),
            WeldsCompleted(0),
        )

        val result = process.run()

        assertEquals(AgentProcessStatusCode.COMPLETED, result.status)
        assertEquals(6, result.last<ShiftReport>()?.welds, "The shift ran to its committed objective")

        val steps = result.objects.filterIsInstance<ExecutedStep>().map { it.name }
        for (door in listOf("door-7", "door-8")) {
            assertEquals(
                listOf("equipGripper:$door", "reattachDoor:$door", "notifySupervisor:$door", "swapBack:$door"),
                steps.filter { it.endsWith(door) },
                "Each fallen door ran the full four-step repair exactly once",
            )
        }
        assertEquals(6, steps.count { it == "weld" }, "Standing welds resumed after each repair")

        // Both occurrences are consumed; all four repair products stay child-local.
        assertNull(result.last<DoorFellOff>())
        assertNull(result.last<GripperEquipped>())
        assertNull(result.last<DoorReattached>())
        assertNull(result.last<SupervisorNotified>())
        assertNull(result.last<RepairCompleted>())
        assertNotNull(result.last<WeldsCompleted>(), "The weld tally is standing state and survives")
    }

    @Test
    fun `the sussman analogue remains ordinary root planning without an occurrence`() {
        // With no evolve call, the selected planner handles the standing
        // request as ordinary root work.
        val process = create(
            SharedIntermediateAgent(),
            "aima-sussman-frame",
            ProcessOptions.DEFAULT.withEvolving(GoalTarget.output(AlphaDone::class.java)),
            AlphaRequested("a-1"),
        )

        val result = process.run()

        assertEquals(AgentProcessStatusCode.COMPLETED, result.status, "The root planner completed its declared mission")
        assertNotNull(result.last<AlphaDone>(), "Root achievement remains standing state")
    }

    @Test
    fun `the sussman analogue - goals sharing an intermediate contest each arrival, and value decides`() {
        // The planner sees that either request can reach either goal through
        // the shared kit. It owns the value contest and supplies the winning
        // child mission; the evolving runtime does not recreate that logic.
        val process = create(
            SharedIntermediateAgent(),
            "aima-sussman-analogue",
            ProcessOptions.DEFAULT.withEvolving(),
        )
        process.evolve(BetaRequested("b-1"))

        val result = process.run()

        assertNull(result.last<BetaRequested>(), "The arrival was owned and consumed")
        assertNull(result.last<AlphaDone>(), "The winning child output remained child-local")
        assertEquals(AgentProcessStatusCode.STUCK, result.status, "One episode completed, then a clean park")
    }
}
