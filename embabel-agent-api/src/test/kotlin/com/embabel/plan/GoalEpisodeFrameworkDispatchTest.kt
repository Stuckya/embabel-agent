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
import com.embabel.agent.core.EpisodeExecution
import com.embabel.agent.core.Evolving
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
                context.agentProcess.evolve(BatchRequested(request.id + 1))
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

    @Agent(description = "Two rules contest one request type through multi-step chains")
    inner class ContestedMissionAgent {

        @Action(canRerun = true, value = 0.5)
        fun draftSurvey(request: PaintRequested, context: ActionContext): SurveyDraft {
            context.addObject(ExecutedStep("draft:${request.id}"))
            return SurveyDraft(request.id)
        }

        @Action(canRerun = true, value = 0.9)
        @AchievesGoal(description = "Survey filed", value = 1.0)
        fun fileSurvey(draft: SurveyDraft, context: ActionContext): SurveyFiled {
            context.addObject(ExecutedStep("file:${draft.id}"))
            return SurveyFiled(draft.id)
        }

        @Action(canRerun = true, value = 0.5)
        fun prepSurface(request: PaintRequested, context: ActionContext): PreparedSurface {
            context.addObject(ExecutedStep("prep:${request.id}"))
            return PreparedSurface(request.id)
        }

        @Action(canRerun = true, value = 0.9)
        @AchievesGoal(description = "Surface painted", value = 0.5)
        fun paint(surface: PreparedSurface, context: ActionContext): SurfacePainted {
            context.addObject(ExecutedStep("paint:${surface.id}"))
            return SurfacePainted(surface.id)
        }
    }

    @Test
    fun `a contested arrival routes by the chain's own planner - no frame leakage before ownership`() {
        // Routing and dispatch must share one planning view: a HYBRID
        // parent's single-step lookahead cannot value a multi-step chain,
        // and an unowned occurrence is never admitted - so its ungated
        // chain leaks into founding-frame work. Under child execution the
        // contest is valued by the same full-path planner the child runs
        val process = dispatching(ContestedMissionAgent(), hybrid = true)
        process.evolve(PaintRequested("job-1"))

        val result = process.run()

        assertNull(result.last<PaintRequested>(), "The contested occurrence was owned, admitted, and consumed")
        val steps = result.objects.filterIsInstance<ExecutedStep>().map { it.name }
        assertEquals(
            listOf("draft:job-1", "file:job-1"), steps,
            "The higher-value survey rule won on the merits and its chain ran once, whole, in its child",
        )
        assertEquals(1, process.frameworkChildCount, "One child for the one occurrence")
    }

    @Test
    fun `an ephemeral process cannot declare child execution - the conflict fails at construction`() {
        // Child dispatch would reject the ephemeral parent only at first
        // dispatch, mid-mission and outside containment. Fail at the
        // declaration instead, naming the declared opt-out
        val agent = AgentMetadataReader().createAgentMetadata(PlainCalibrationAgent()) as CoreAgent
        val rejection = assertThrows<IllegalArgumentException> {
            SimpleAgentProcess(
                "framework-dispatch-ephemeral",
                null,
                agent,
                ProcessOptions.DEFAULT.withEphemeral(true).withEvolving(),
                InMemoryBlackboard(),
                dummyPlatformServices(),
                DefaultPlannerFactory,
                Instant.now(),
            )
        }
        assertTrue(
            rejection.message!!.contains("IN_PROCESS"),
            "The rejection names EpisodeExecution.IN_PROCESS as the opt-out: ${rejection.message}",
        )
        // Ephemeral with in-process execution spawns nothing and stays legal
        SimpleAgentProcess(
            "framework-dispatch-ephemeral-in-process",
            null,
            agent,
            ProcessOptions.DEFAULT.withEphemeral(true)
                .withEvolving(Evolving(execution = EpisodeExecution.IN_PROCESS)),
            InMemoryBlackboard(),
            dummyPlatformServices(),
            DefaultPlannerFactory,
            Instant.now(),
        )
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
        val added = mutableListOf<Any>()
        val listener = object : com.embabel.agent.api.event.AgenticEventListener {
            override fun onProcessEvent(event: com.embabel.agent.api.event.AgentProcessEvent) {
                if (event is com.embabel.agent.api.event.ObjectAddedEvent) {
                    added.add(event.value)
                }
            }
        }
        val blackboard = InMemoryBlackboard()
        val agent = AgentMetadataReader().createAgentMetadata(PlainCalibrationAgent()) as CoreAgent
        val process = SimpleAgentProcess(
            "framework-dispatch-hidden",
            null,
            agent,
            ProcessOptions.DEFAULT.withEvolving().withListener(listener),
            blackboard,
            dummyPlatformServices(),
            DefaultPlannerFactory,
            Instant.now(),
        )
        process.evolve(CalibrationRequested("cal-1"))

        val result = process.run()

        assertTrue(
            added.any { it is CalibrationCompleted },
            "Merged child outputs publish through the process event path, visible to listeners",
        )

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
    fun `a failed child is contained and re-selection respawns - the occurrence survives to a fresh child`() {
        // Failure containment is a rung upgrade: an in-process action throw
        // fails the parent run, while a failed child leaves the parent
        // running with the occurrence unconsumed. The next tick's selection
        // respawns a fresh child in the same run, bounded by the parent's
        // action budget
        val agent = PlainFlakyAgent()
        val process = dispatching(agent)
        process.evolve(CalibrationRequested("cal-1"))

        val result = process.run()

        assertEquals(AgentProcessStatusCode.STUCK, result.status, "The parent survived the child's failure")
        assertEquals(2, agent.attempts, "The contained failure was re-selected and a fresh child respawned")
        assertEquals(2, process.frameworkChildren.size, "One failed child, one successful respawn")
        assertNull(result.last<CalibrationRequested>(), "Only the successful child consumed the occurrence")
        assertEquals(
            AgentProcessStatusCode.COMPLETED, process.frameworkChildren.last().status,
            "The respawned child ran the chain to its goal",
        )
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
            context.agentProcess.evolve(BatchRequested(request.id + 1))
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

    @Agent(description = "A blocked episode beside busy frame work - dispatch must not spin")
    inner class SphexMissionAgent {

        @Action(canRerun = true, value = 0.4)
        @AchievesGoal(description = "Surface painted", value = 1.0)
        fun paint(request: PaintRequested, can: PaintCan, context: ActionContext): SurfacePainted {
            context.addObject(ExecutedStep("paint:${request.id}"))
            return SurfacePainted(request.id)
        }

        @Condition(name = "collecting")
        fun collecting(tally: SampleTally): Boolean = tally.count < 3

        @Action(pre = ["collecting"], canRerun = true, value = 0.6)
        fun weld(tally: SampleTally, context: ActionContext): SampleTally {
            context.addObject(ExecutedStep("weld"))
            return SampleTally(tally.count + 1)
        }

        @Condition(name = "missionDone")
        fun missionDone(tally: SampleTally): Boolean = tally.count >= 3

        @Action(pre = ["missionDone"], value = 0.9)
        @AchievesGoal(description = "Mission complete", value = 0.5)
        fun report(tally: SampleTally): BatchMissionDone = BatchMissionDone(tally.count)
    }

    @Agent(description = "Batches beside capped standing welds - waves must interleave")
    inner class InterleavedMissionAgent {

        // The batch chain is deliberately priced below the welds: goal 0.2
        // plus step 0.1 against weld 0.9, so preemption is value's verdict
        @Action(canRerun = true, value = 0.1)
        @AchievesGoal(description = "Batch collected", value = 0.2)
        fun collectBatch(request: BatchRequested, tally: SampleTally, context: ActionContext): BatchCollected {
            context.addObject(ExecutedStep("batch:${request.id}"))
            val next = SampleTally(tally.count + 50)
            context.addObject(next)
            if (next.count < 150) {
                context.agentProcess.evolve(BatchRequested(request.id + 1))
            }
            return BatchCollected(request.id)
        }

        // Welding becomes available only after the first batch and is
        // capped at two: interleaving must be earned by value, not fiat
        @Condition(name = "welding")
        fun welding(tally: SampleTally, missions: MissionTally): Boolean =
            tally.count >= 50 && missions.count < 2

        @Action(pre = ["welding"], canRerun = true, value = 0.9)
        fun weld(missions: MissionTally, context: ActionContext): MissionTally {
            context.addObject(ExecutedStep("weld"))
            return MissionTally(missions.count + 1)
        }

        @Condition(name = "missionDone")
        fun missionDone(tally: SampleTally): Boolean = tally.count >= 150

        @Action(pre = ["missionDone"], value = 0.9)
        @AchievesGoal(description = "Mission complete", value = 0.5)
        fun report(tally: SampleTally): BatchMissionDone = BatchMissionDone(tally.count)
    }

    @Test
    fun `standing work interleaves with framework children by value - selection is the planner's`() {
        // Episodes are atomic; the mission is not. Selection is value-owned
        // on both rungs: the welds out-value the remaining batches once
        // available, so they preempt the chain exactly as in-process
        // standing work would (AIMA 3e p. 422), then the batches resume
        val process = dispatching(
            InterleavedMissionAgent(),
            SampleTally(0),
            MissionTally(0),
            objective = GoalTarget.output(BatchMissionDone::class.java),
            hybrid = true,
        )
        process.evolve(BatchRequested(1))

        val result = process.run()

        assertEquals(AgentProcessStatusCode.COMPLETED, result.status)
        val steps = result.objects.filterIsInstance<ExecutedStep>().map { it.name }
        assertEquals(
            listOf("batch:1", "weld", "weld", "batch:2", "batch:3"), steps,
            "The higher-value welds preempted the remaining batches, then the chain resumed",
        )
        assertEquals(150, result.last<BatchMissionDone>()?.samples)
    }

    @Agent(description = "Batches with an evolved hazard - cross-rule dispatch on the default rung")
    inner class HazardousMissionAgent {

        @Action(canRerun = true, value = 0.5)
        @AchievesGoal(description = "Batch collected", value = 1.0)
        fun collectBatch(request: BatchRequested, tally: SampleTally, context: ActionContext): BatchCollected {
            context.addObject(ExecutedStep("batch:${request.id}"))
            val next = SampleTally(tally.count + 50)
            context.addObject(next)
            if (next.count == 100) {
                context.agentProcess.evolve(HazardDetected("spill-1"))
            }
            if (next.count < 150) {
                context.agentProcess.evolve(BatchRequested(request.id + 1))
            }
            return BatchCollected(request.id)
        }

        @Action(canRerun = true, value = 0.7)
        fun assess(hazard: HazardDetected, context: ActionContext): HazardAssessed {
            context.addObject(ExecutedStep("assess:${hazard.id}"))
            return HazardAssessed(hazard.id)
        }

        @Action(canRerun = true, value = 0.8)
        @AchievesGoal(description = "Hazard cleared", value = 1.0)
        fun clear(assessed: HazardAssessed, context: ActionContext): HazardCleared {
            context.addObject(ExecutedStep("clear:${assessed.id}"))
            return HazardCleared(assessed.id)
        }

        @Condition(name = "missionDone")
        fun missionDone(tally: SampleTally): Boolean = tally.count >= 150

        @Action(pre = ["missionDone"], value = 0.9)
        @AchievesGoal(description = "Mission complete", value = 0.5)
        fun report(tally: SampleTally): BatchMissionDone = BatchMissionDone(tally.count)
    }

    @Test
    fun `an evolved hazard runs as its own framework child between batches`() {
        val process = dispatching(
            HazardousMissionAgent(),
            SampleTally(0),
            objective = GoalTarget.output(BatchMissionDone::class.java),
            hybrid = true,
        )
        process.evolve(BatchRequested(1))

        val result = process.run()

        assertEquals(AgentProcessStatusCode.COMPLETED, result.status)
        assertEquals(4, process.frameworkChildren.size, "Three batch children and one hazard child")
        assertEquals(150, result.last<BatchMissionDone>()?.samples)
        val steps = result.objects.filterIsInstance<ExecutedStep>().map { it.name }
        assertTrue("assess:spill-1" in steps && "clear:spill-1" in steps, "The hazard chain ran and merged: $steps")
        assertTrue(
            steps.indexOf("clear:spill-1") < steps.indexOf("batch:3"),
            "The higher-value hazard episode dispatched before the remaining batch: $steps",
        )
        assertNull(result.last<HazardDetected>(), "The evolved hazard occurrence was consumed")
        assertNull(result.last<HazardCleared>(), "The hazard result was consumed")
    }

    @Test
    fun `evolve delegates through non-evolving levels - the tower's plumbing is transitive`() {
        val root = dispatching(PlainCalibrationAgent())
        val platform = root.processContext.platformServices.agentPlatform
        val crew = AgentMetadataReader().createAgentMetadata(PlainPaintingAgent()) as CoreAgent
        val child = platform.createChildProcess(crew, root, ProcessOptions.DEFAULT)
        val grandchild = platform.createChildProcess(crew, child, ProcessOptions.DEFAULT)

        grandchild.evolve(CalibrationRequested("cal-1"))
        val result = root.run()

        assertEquals(AgentProcessStatusCode.STUCK, result.status, "One episode completed, then a clean park")
        assertNull(result.last<CalibrationRequested>(), "The grandchild's occurrence reached the evolving root")
    }

    @Test
    fun `a blocked episode never spawns a child the parent can predict will stall`() {
        // The sphex-wasp hazard (AIMA 3e p. 425, note 5): futile repetition.
        // A busy parent ticks every action, and each tick must not spawn a
        // doomed child that spends the budget stalling. The parent checks
        // the chain's feasibility in the episode's world first - the
        // stall-before-work principle lifted to the dispatch boundary
        val blackboard = InMemoryBlackboard()
        blackboard.addObject(SampleTally(0))
        val agent = AgentMetadataReader().createAgentMetadata(SphexMissionAgent()) as CoreAgent
        val process = SimpleAgentProcess(
            "framework-dispatch-sphex",
            null,
            agent.copy(goals = agent.goals + NIRVANA),
            ProcessOptions.DEFAULT
                .withPlannerType(PlannerType.HYBRID)
                .withEvolving(GoalTarget.output(BatchMissionDone::class.java)),
            blackboard,
            dummyPlatformServices(),
            DefaultPlannerFactory,
            Instant.now(),
        )
        process.evolve(PaintRequested("job-1"))

        val result = process.run()

        assertEquals(
            AgentProcessStatusCode.COMPLETED, result.status,
            "The mission completed; doomed dispatches did not exhaust the budget",
        )
        assertEquals(3, result.last<BatchMissionDone>()?.samples, "Frame work proceeded past the blocked episode")
        assertTrue(
            process.frameworkChildren.isEmpty(),
            "No child was spawned for a chain the parent could predict would stall",
        )
        assertNotNull(result.last<PaintRequested>(), "The blocked occurrence waits intact for its enabler")
    }

    @Test
    fun `child retention is bounded - a long mission keeps a window, not a hoard`() {
        val blackboard = InMemoryBlackboard()
        val agent = AgentMetadataReader().createAgentMetadata(GreedyChainAgent()) as CoreAgent
        val process = SimpleAgentProcess(
            "framework-dispatch-retention",
            null,
            agent,
            ProcessOptions.DEFAULT
                .withBudget(com.embabel.agent.core.Budget().withActions(35))
                .withEvolving(),
            blackboard,
            dummyPlatformServices(),
            DefaultPlannerFactory,
            Instant.now(),
        )
        process.evolve(BatchRequested(1))

        val result = process.run()

        assertEquals(AgentProcessStatusCode.TERMINATED, result.status)
        assertEquals(35, process.frameworkChildCount, "Every dispatch counted")
        assertEquals(32, process.frameworkChildren.size, "Inspection sees a bounded window of recent children")
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
