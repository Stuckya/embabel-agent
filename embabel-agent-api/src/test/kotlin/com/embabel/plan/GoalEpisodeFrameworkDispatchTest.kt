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
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Framework dispatch: the developer writes one ordinary agent and calls
 * evolve; the framework runs each admitted episode in a child process it
 * synthesizes from the derived chain. No dispatch actions, no crew
 * authoring, no platform API in developer code - the complexity is hidden.
 * Child execution is the default under withEvolving; the in-process rung
 * is the declared opt-out.
 *
 * What it pins:
 * - Hidden complexity: a plain agent's evolved episode runs in a
 *   framework-spawned child with lineage recorded. The developer never
 *   sees createChildProcess.
 * - evolve delegates up the tower: a chain action executing inside a
 *   framework child publishes its follow-up through the child's process
 *   handle, and the occurrence reaches the owning evolving parent.
 * - Standing state merges back: everything the chain wrote returns at
 *   completion, so accumulator patterns author identically on both rungs.
 * - Snapshot pairing: each child sees exactly its own occurrence, because
 *   queued arrivals hidden in the parent stay hidden in the snapshot.
 * - A stale satisfying output cannot vacuously complete a child: hasRun
 *   is process-scoped, so a fresh child must run its chain.
 * - A blocked child consumes nothing and a later tick redispatches; a
 *   failed child is contained - the parent keeps running - and the next
 *   run respawns a fresh one with the occurrence intact.
 * - Standing USE-resources share across children through snapshots,
 *   consumed by none (AIMA 3e SS11.1).
 * - A HYBRID parent's framework children plan under GOAP: full-path
 *   planning inside episodes, no pairing goal anywhere.
 * - The parent's action budget bounds the dispatch loop.
 */
class GoalEpisodeFrameworkDispatchTest {

    @Agent(description = "Plain two-step calibration - no dispatch code anywhere")
    inner class PlainCalibrationAgent {

        @Action(canRerun = true, value = 0.5)
        fun prepKit(request: CalibrationRequested, context: ActionContext): CalibrationKit {
            context.addObject(ExecutedStep("prep:${request.id}"))
            return CalibrationKit(request.id)
        }

        @Action(canRerun = true, value = 0.9)
        @AchievesGoal(description = "Calibration completed", value = 1.0)
        fun calibrate(kit: CalibrationKit, context: ActionContext): CalibrationCompleted {
            context.addObject(ExecutedStep("calibrate:${kit.id}"))
            return CalibrationCompleted(kit.id)
        }
    }

    @Agent(description = "Plain batch mission - self-chaining, accumulator, objective; no dispatch code")
    inner class PlainBatchMissionAgent {

        @Action(canRerun = true, value = 0.5)
        @AchievesGoal(description = "Batch collected", value = 1.0)
        fun collectBatch(request: BatchRequested, tally: SampleTally, context: ActionContext): BatchCollected {
            context.addObject(ExecutedStep("batch:${request.id}"))
            val next = SampleTally(tally.count + 50)
            context.addObject(next)
            if (next.count < 200) {
                (context.agentProcess as SimpleAgentProcess).evolve(BatchRequested(request.id + 1))
            }
            return BatchCollected(request.id)
        }

        @Condition(name = "missionDone")
        fun missionDone(tally: SampleTally): Boolean = tally.count >= 200

        @Action(pre = ["missionDone"], value = 0.9)
        @AchievesGoal(description = "Mission complete", value = 0.5)
        fun report(tally: SampleTally): BatchMissionDone = BatchMissionDone(tally.count)
    }

    @Agent(description = "Plain painting robot - the stall must survive the rung change")
    inner class PlainPaintingAgent {

        @Action(canRerun = true, value = 0.5)
        fun prepSurface(request: PaintRequested, context: ActionContext): PreparedSurface {
            context.addObject(ExecutedStep("prep:${request.id}"))
            return PreparedSurface(request.id)
        }

        @Action(canRerun = true, value = 0.9)
        @AchievesGoal(description = "Surface painted", value = 1.0)
        fun paint(surface: PreparedSurface, can: PaintCan, context: ActionContext): SurfacePainted {
            context.addObject(ExecutedStep("paint:${surface.id}"))
            return SurfacePainted(surface.id)
        }
    }

    private fun dispatching(
        agentInstance: Any,
        vararg seeds: Any,
        objective: GoalTarget? = null,
        hybrid: Boolean = false,
    ): SimpleAgentProcess {
        val blackboard = InMemoryBlackboard()
        seeds.forEach { blackboard.addObject(it) }
        val agent = AgentMetadataReader().createAgentMetadata(agentInstance) as CoreAgent
        val effectiveAgent = if (hybrid) agent.copy(goals = agent.goals + NIRVANA) else agent
        var options = objective?.let { ProcessOptions.DEFAULT.withEvolving(it) }
            ?: ProcessOptions.DEFAULT.withEvolving()
        if (hybrid) {
            options = options.withPlannerType(PlannerType.HYBRID)
        }
        return SimpleAgentProcess(
            "framework-dispatch",
            null,
            effectiveAgent,
            options,
            blackboard,
            dummyPlatformServices(),
            DefaultPlannerFactory,
            Instant.now(),
        )
    }

    @Test
    fun `an evolved episode runs in a framework child - the developer never sees the platform`() {
        val process = dispatching(PlainCalibrationAgent())
        process.evolve(CalibrationRequested("cal-1"))

        val result = process.run()

        assertEquals(AgentProcessStatusCode.STUCK, result.status, "One episode completed, then a clean park")
        val children = process.frameworkChildren
        assertEquals(1, children.size, "The framework spawned exactly one child for the occurrence")
        assertEquals(result.id, children.single().parentId, "The platform recorded the parent lineage")
        assertEquals(
            AgentProcessStatusCode.COMPLETED, children.single().status,
            "The synthesized child ran the chain to its goal",
        )
        assertNull(result.last<CalibrationRequested>(), "The occurrence was consumed")
        assertNull(result.last<CalibrationKit>(), "The chain's intermediate was consumed as the episode's consumable")
        assertNull(result.last<CalibrationCompleted>(), "The satisfying output was consumed")
        val steps = result.objects.filterIsInstance<ExecutedStep>().map { it.name }
        assertEquals(
            listOf("prep:cal-1", "calibrate:cal-1"), steps,
            "The chain's standing writes merged back: authoring is rung-identical",
        )
    }

    @Test
    fun `the batch mission runs unchanged under framework dispatch - self-chaining and standing state survive`() {
        // The decisive standing-state test: the tally advances via addObject
        // inside the chain, and the follow-up occurrence is evolved from
        // inside the framework child. Merge-back returns the tally; evolve
        // delegates up the tower to the owning evolving parent
        val process = dispatching(
            PlainBatchMissionAgent(),
            SampleTally(0),
            objective = GoalTarget.output(BatchMissionDone::class.java),
        )
        process.evolve(BatchRequested(1))

        val result = process.run()

        assertEquals(AgentProcessStatusCode.COMPLETED, result.status, "The committed objective ended the mission")
        assertEquals(200, result.last<BatchMissionDone>()?.samples, "Four child episodes accumulated to the target")
        assertEquals(200, result.last<SampleTally>()?.count, "The accumulator merged back from every child")
        assertEquals(4, process.frameworkChildren.size, "One framework child per occurrence")
        assertNull(result.last<BatchRequested>(), "Every occurrence was consumed, including tower-delegated ones")
        assertNull(result.last<BatchCollected>(), "Every satisfying output was consumed")
        assertEquals(
            listOf(listOf(1), listOf(2), listOf(3), listOf(4)),
            process.frameworkChildren.map { child ->
                child.objects.filterIsInstance<BatchRequested>().map(BatchRequested::id)
            },
            "Snapshot pairing: each child saw exactly its own occurrence, never a queued one",
        )
    }

    @Test
    fun `a stale satisfying output cannot vacuously complete a framework child`() {
        // hasRun is process-scoped: a fresh child must run its chain even
        // when a stale output of the goal's type rides in from the parent
        val process = dispatching(PlainCalibrationAgent(), CalibrationCompleted("stale-victory"))
        process.evolve(CalibrationRequested("cal-1"))

        val result = process.run()

        assertEquals(AgentProcessStatusCode.STUCK, result.status)
        val steps = result.objects.filterIsInstance<ExecutedStep>().map { it.name }
        assertEquals(listOf("prep:cal-1", "calibrate:cal-1"), steps, "The child ran its chain despite the stale output")
        assertNull(result.last<CalibrationRequested>(), "The occurrence was consumed by real work")
        assertEquals(
            "stale-victory", result.last<CalibrationCompleted>()?.id,
            "The stale standing output survives: shadowed during the episode, never consumed",
        )
    }

    @Agent(description = "Plain calibration whose first attempt fails")
    inner class PlainFlakyAgent {

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

    @Test
    fun `a failed child is contained and a later run respawns - the occurrence survives the failure`() {
        // Failure containment is a rung upgrade: an in-process action throw
        // fails the parent run, while a failed child leaves the parent
        // running with the occurrence unconsumed for a fresh child
        val agent = PlainFlakyAgent()
        val process = dispatching(agent)
        process.evolve(CalibrationRequested("cal-1"))

        val afterFailure = process.run()

        assertEquals(AgentProcessStatusCode.STUCK, afterFailure.status, "The parent survived the child's failure")
        assertNotNull(afterFailure.last<CalibrationRequested>(), "A failed child must not consume the occurrence")
        assertEquals(1, process.frameworkChildren.size, "One child was spawned and failed")

        val retried = afterFailure.run()

        assertEquals(AgentProcessStatusCode.STUCK, retried.status)
        assertEquals(2, agent.attempts, "A fresh child was respawned")
        assertNull(retried.last<CalibrationRequested>(), "The respawned child's completion consumed the occurrence")
    }

    @Test
    fun `a blocked child consumes nothing and redispatches when the world changes`() {
        val process = dispatching(PlainPaintingAgent())
        process.evolve(PaintRequested("job-1"))

        val stalled = process.run()

        assertEquals(AgentProcessStatusCode.STUCK, stalled.status, "No can, so the child stalls before work")
        assertNotNull(stalled.last<PaintRequested>(), "A blocked episode must not consume its request")
        assertTrue(
            stalled.objects.filterIsInstance<ExecutedStep>().isEmpty(),
            "GOAP in the child refuses to start a chain it cannot finish: no stranded prep",
        )

        stalled.addObject(PaintCan("red"))
        val painted = stalled.run()

        assertEquals(AgentProcessStatusCode.STUCK, painted.status)
        assertNull(painted.last<PaintRequested>(), "The redispatched child completed and consumed")
        assertNotNull(painted.last<PaintCan>(), "The can is used, not consumed")
        val steps = painted.objects.filterIsInstance<ExecutedStep>().map { it.name }
        assertEquals(listOf("prep:job-1", "paint:job-1"), steps, "The chain ran whole in the successful child")

        process.evolve(PaintRequested("job-2"))
        val secondJob = painted.run()

        assertNotNull(secondJob.last<PaintCan>(), "The can survives every child: a shared USE-resource")
        val allSteps = secondJob.objects.filterIsInstance<ExecutedStep>().map { it.name }
        assertEquals(
            listOf("prep:job-1", "paint:job-1", "prep:job-2", "paint:job-2"), allSteps,
            "The second child reused the standing can through its snapshot",
        )
    }

    @Agent(description = "A chain that always evolves its follow-up - deliberately unbounded")
    inner class GreedyChainAgent {

        @Action(canRerun = true, value = 0.9)
        @AchievesGoal(description = "Link forged", value = 1.0)
        fun forgeLink(request: BatchRequested, context: ActionContext): BatchCollected {
            (context.agentProcess as SimpleAgentProcess).evolve(BatchRequested(request.id + 1))
            return BatchCollected(request.id)
        }
    }

    @Test
    fun `the parent budget bounds framework dispatches - an unbounded chain terminates instead of spawning forever`() {
        // A chain that always evolves its follow-up would spawn children
        // forever: dispatches spend the parent's action budget exactly as
        // in-process spins do
        val blackboard = InMemoryBlackboard()
        val agent = AgentMetadataReader().createAgentMetadata(GreedyChainAgent()) as CoreAgent
        val process = SimpleAgentProcess(
            "framework-dispatch-budget",
            null,
            agent,
            ProcessOptions.DEFAULT
                .withBudget(com.embabel.agent.core.Budget().withActions(5))
                .withEvolving(),
            blackboard,
            dummyPlatformServices(),
            DefaultPlannerFactory,
            Instant.now(),
        )
        process.evolve(BatchRequested(1))

        val result = process.run()

        assertEquals(
            AgentProcessStatusCode.TERMINATED, result.status,
            "The action budget bounded the dispatch loop",
        )
        assertEquals(5, process.frameworkChildren.size, "One child per budgeted action, then the brake")
        assertNotNull(result.last<BatchRequested>(), "The unbounded chain's next occurrence stayed unconsumed")
    }

    @Test
    fun `a HYBRID parent's framework children plan under GOAP - no pairing goal anywhere`() {
        val process = dispatching(
            PlainBatchMissionAgent(),
            SampleTally(0),
            objective = GoalTarget.output(BatchMissionDone::class.java),
            hybrid = true,
        )
        process.evolve(BatchRequested(1))

        val result = process.run()

        assertEquals(AgentProcessStatusCode.COMPLETED, result.status)
        assertEquals(200, result.last<BatchMissionDone>()?.samples)
        val children = process.frameworkChildren
        assertEquals(4, children.size)
        children.forEach {
            assertEquals(AgentProcessStatusCode.COMPLETED, it.status, "Every GOAP child completed whole")
        }
    }
}
