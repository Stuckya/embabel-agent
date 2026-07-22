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
import com.embabel.agent.core.AgentProcess
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
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

data class WaveRequested(val id: Int)
data class WaveDone(val id: Int)
data class StepRequested(val id: Int)
data class StepTally(val count: Int)
data class StepsDone(val steps: Int)
data class StepDone(val id: Int)
data class MissionTally(val count: Int)
data class WavesComplete(val waves: Int)

/**
 * Igor's model, proven at full depth: a parent agent running in a loop of
 * subagents, where each subagent IS the episode. His episode definition -
 * "a sequence of actions executed by a planner on his navigation from the
 * initial action to an achievable goal" - came with the caveat that "there
 * is no guarantee a planner will walk from A to Z". Every in-process
 * mechanism (gate, grounding, attribution) polices that walk; the child
 * boundary draws it structurally. Begin-episode is the child's birth,
 * end-episode is its completion, and the planner cannot wander out of an
 * episode that is the whole world it lives in. His "restart Agent" signal
 * is respawn; RepeatUntil is the parent's plan-execute-replan loop.
 *
 * What it proves, with zero production changes:
 * - The full batch mission runs child-primary on the shipped contract: the
 *   parent's episode chain is one dispatch action, occurrences arrive
 *   through evolve, the objective anchors the end. Standing state is the
 *   declared cost: the tally must round-trip through the dispatch action,
 *   because children see snapshots, not the live board.
 * - Request pairing transfers through the snapshot for free: queued
 *   occurrences are hidden in the parent, so each child sees exactly its
 *   own request. Serial admission's hiding discipline does double duty.
 * - A stale satisfying output in the snapshot cannot vacuously complete a
 *   child: hasRun is process-scoped, so a fresh child must run its chain.
 *   The outcome-window shim exists in-process precisely because in-process
 *   episodes lack this freshness.
 * - Standing USE-resources share across children through snapshots: the
 *   paint can (AIMA 3e SS11.1) is visible to every child and consumed by
 *   none. Intermediate reuse is the flip-side cost: each child remakes its
 *   own kit, because isolation forecloses cross-episode reuse.
 * - A failed child leaves the request unconsumed and the next tick
 *   respawns a fresh child: Igor's restart signal, riding the existing
 *   retry contract across the process boundary.
 *
 * The same composed batch scenario runs on the in-process rung in
 * GoalEpisodeBatchLoopTest, the supported degenerate case. One contract,
 * both rungs, deliberately pinned twice.
 */
class GoalEpisodeChildPrimaryTest {

    @Agent(description = "Crew that collects one batch of samples")
    inner class BatchCrewAgent {

        @Action(value = 0.9)
        @AchievesGoal(description = "Samples gathered", value = 1.0)
        fun gather(request: BatchRequested, context: ActionContext): BatchCollected {
            context.addObject(ExecutedStep("child-gather:${request.id}"))
            return BatchCollected(request.id)
        }
    }

    @Agent(description = "Crew that clears one hazard")
    inner class HazardCrewAgent {

        @Action(value = 0.5)
        fun assess(hazard: HazardDetected, context: ActionContext): HazardAssessed {
            context.addObject(ExecutedStep("child-assess:${hazard.id}"))
            return HazardAssessed(hazard.id)
        }

        @Action(value = 0.9)
        @AchievesGoal(description = "Hazard cleared", value = 1.0)
        fun clear(assessed: HazardAssessed, context: ActionContext): HazardCleared {
            context.addObject(ExecutedStep("child-clear:${assessed.id}"))
            return HazardCleared(assessed.id)
        }
    }

    @Agent(description = "Mission that dispatches batches and hazards to child processes")
    inner class ChildPrimaryMissionAgent {

        val children = mutableListOf<AgentProcess>()

        private val batchCrew: CoreAgent by lazy {
            AgentMetadataReader().createAgentMetadata(BatchCrewAgent()) as CoreAgent
        }

        private val hazardCrew: CoreAgent by lazy {
            AgentMetadataReader().createAgentMetadata(HazardCrewAgent()) as CoreAgent
        }

        /**
         * The episode chain is one dispatch. Standing state is the declared
         * cost of the child model: the tally advances here, in the parent,
         * from the child's returned result.
         */
        @Action(canRerun = true, value = 0.5)
        @AchievesGoal(description = "Batch collected", value = 1.0)
        fun dispatchBatch(request: BatchRequested, tally: SampleTally, context: ActionContext): BatchCollected {
            context.addObject(ExecutedStep("dispatch:${request.id}"))
            val platform = context.processContext.platformServices.agentPlatform
            val child = platform.createChildProcess(batchCrew, context.agentProcess, ProcessOptions.DEFAULT)
            children += child
            val collected = child.run().last<BatchCollected>()
                ?: error("Batch child for ${request.id} produced no BatchCollected: status=${child.status}")
            val next = SampleTally(tally.count + 50)
            context.addObject(next)
            if (next.count == 100) {
                (context.agentProcess as SimpleAgentProcess).evolve(HazardDetected("spill-1"))
            }
            if (next.count < 500) {
                (context.agentProcess as SimpleAgentProcess).evolve(BatchRequested(request.id + 1))
            }
            return collected
        }

        @Action(canRerun = true, value = 0.8)
        @AchievesGoal(description = "Hazard handled", value = 1.0)
        fun dispatchHazard(hazard: HazardDetected, context: ActionContext): HazardCleared {
            context.addObject(ExecutedStep("repair:${hazard.id}"))
            val platform = context.processContext.platformServices.agentPlatform
            val child = platform.createChildProcess(hazardCrew, context.agentProcess, ProcessOptions.DEFAULT)
            children += child
            return child.run().last<HazardCleared>()
                ?: error("Hazard child for ${hazard.id} produced no HazardCleared: status=${child.status}")
        }

        @Condition(name = "missionDone")
        fun missionDone(tally: SampleTally): Boolean = tally.count >= 500

        @Action(pre = ["missionDone"], value = 0.9)
        @AchievesGoal(description = "Mission complete", value = 0.5)
        fun report(tally: SampleTally): BatchMissionDone = BatchMissionDone(tally.count)
    }

    private fun create(
        instance: Any,
        processId: String,
        options: ProcessOptions,
        vararg seeds: Any,
    ): SimpleAgentProcess {
        val blackboard = InMemoryBlackboard()
        seeds.forEach { blackboard.addObject(it) }
        val agent = AgentMetadataReader().createAgentMetadata(instance) as CoreAgent
        val effectiveAgent = if (options.plannerType == PlannerType.HYBRID) {
            agent.copy(goals = agent.goals + NIRVANA)
        } else {
            agent
        }
        return SimpleAgentProcess(
            processId,
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
    fun `the batch mission runs child-primary - a loop of subagents on the shipped contract`() {
        val mission = ChildPrimaryMissionAgent()
        val process = create(
            mission,
            "child-primary-batch",
            ProcessOptions.DEFAULT
                .withPlannerType(PlannerType.HYBRID)
                .withEvolving(Evolving(GoalTarget.output(BatchMissionDone::class.java), EpisodeExecution.IN_PROCESS)),
            SampleTally(0),
        )
        process.evolve(BatchRequested(1))

        val result = process.run()

        assertEquals(AgentProcessStatusCode.COMPLETED, result.status, "The committed objective ended the mission")
        assertEquals(500, result.last<BatchMissionDone>()?.samples, "Ten child batches accumulated to the target")
        assertEquals(500, result.last<SampleTally>()?.count, "Standing state round-tripped through dispatch")

        val parentSteps = result.objects.filterIsInstance<ExecutedStep>().map { it.name }
        val expected = listOf("dispatch:1", "dispatch:2", "repair:spill-1") + (3..10).map { "dispatch:$it" }
        assertEquals(
            expected, parentSteps,
            "Ten batch children ran once each; the hazard child slotted between batches 2 and 3",
        )
        assertTrue(parentSteps.none { it.startsWith("child-") }, "Child steps stay in the child")

        assertEquals(11, mission.children.size, "One child process per occurrence")
        mission.children.forEach { child ->
            assertEquals(AgentProcessStatusCode.COMPLETED, child.status)
            assertEquals(result.id, child.parentId, "The platform recorded the parent lineage")
        }

        assertNull(result.last<BatchRequested>(), "Every batch occurrence was consumed by its own episode")
        assertNull(result.last<BatchCollected>(), "Every returned result was consumed as its episode's consumable")
        assertNull(result.last<HazardDetected>(), "The evolved hazard occurrence was consumed")
        assertNull(result.last<HazardAssessed>(), "The hazard child's intermediate never reached the parent")
        assertNull(result.last<HazardCleared>(), "The hazard result was consumed")
    }

    @Test
    fun `queued occurrences stay hidden from children - snapshot pairing rides serial admission`() {
        // Each batch child must see exactly its own request. The parent's
        // queue hides later occurrences, and the child snapshot preserves
        // hidden entries as hidden, so pairing transfers structurally
        val mission = ChildPrimaryMissionAgent()
        val process = create(
            mission,
            "child-primary-pairing",
            ProcessOptions.DEFAULT
                .withPlannerType(PlannerType.HYBRID)
                .withEvolving(Evolving(GoalTarget.output(BatchMissionDone::class.java), EpisodeExecution.IN_PROCESS)),
            SampleTally(400),
        )
        process.evolve(BatchRequested(9))

        val result = process.run()

        assertEquals(AgentProcessStatusCode.COMPLETED, result.status)
        val childRequests = mission.children.map { child ->
            child.objects.filterIsInstance<ExecutedStep>().single { it.name.startsWith("child-gather") }.name
        }
        assertEquals(
            listOf("child-gather:9", "child-gather:10"), childRequests,
            "Each child bound exactly its own occurrence, never a queued one",
        )
    }

    @Agent(description = "Crew that calibrates one request")
    inner class CalibrationCrewAgent {

        @Action(value = 0.9)
        @AchievesGoal(description = "Calibration performed", value = 1.0)
        fun calibrate(request: CalibrationRequested, context: ActionContext): CalibrationCompleted {
            context.addObject(ExecutedStep("child-calibrate:${request.id}"))
            return CalibrationCompleted(request.id)
        }
    }

    @Agent(description = "Dispatcher whose snapshot carries a stale satisfying output")
    inner class StaleSnapshotDispatcherAgent {

        val children = mutableListOf<AgentProcess>()

        private val crew: CoreAgent by lazy {
            AgentMetadataReader().createAgentMetadata(CalibrationCrewAgent()) as CoreAgent
        }

        @Action(canRerun = true, value = 0.9)
        @AchievesGoal(description = "Calibration completed", value = 1.0)
        fun dispatchCalibration(request: CalibrationRequested, context: ActionContext): CalibrationCompleted {
            context.addObject(ExecutedStep("dispatch:${request.id}"))
            val platform = context.processContext.platformServices.agentPlatform
            val child = platform.createChildProcess(crew, context.agentProcess)
            children += child
            return child.run().last<CalibrationCompleted>()
                ?: error("Calibration child for ${request.id} produced nothing: status=${child.status}")
        }
    }

    @Test
    fun `a stale satisfying output cannot vacuously complete a child - hasRun freshness is structural`() {
        // The in-process outcome window exists because a standing satisfying
        // output plus sticky hasRun completes an episode without work. A
        // child is born with fresh hasRun, so even with the stale output
        // visible in its snapshot, its chain must actually run
        val dispatcher = StaleSnapshotDispatcherAgent()
        val process = create(
            dispatcher,
            "child-primary-stale-snapshot",
            ProcessOptions.DEFAULT.withEvolving(Evolving(execution = EpisodeExecution.IN_PROCESS)),
            CalibrationCompleted("stale-victory"),
        )
        process.evolve(CalibrationRequested("cal-1"))

        val result = process.run()

        assertEquals(AgentProcessStatusCode.STUCK, result.status, "One episode completed, then a clean park")
        // The snapshot carries the parent's own steps into the child, so
        // child-side assertions filter for child-authored work
        val childSteps = dispatcher.children.single().objects
            .filterIsInstance<ExecutedStep>().map { it.name }.filter { it.startsWith("child-") }
        assertEquals(
            listOf("child-calibrate:cal-1"), childSteps,
            "The child ran its chain despite the stale output riding in on the snapshot",
        )
        assertNull(result.last<CalibrationRequested>(), "The occurrence was consumed by real work")
    }

    @Agent(description = "Painting dispatcher whose children share one standing can")
    inner class PaintingDispatcherAgent {

        val children = mutableListOf<AgentProcess>()

        private val crew: CoreAgent by lazy {
            AgentMetadataReader().createAgentMetadata(PaintingCrewAgent()) as CoreAgent
        }

        @Action(canRerun = true, value = 0.9)
        @AchievesGoal(description = "Surface painted", value = 1.0)
        fun dispatchPaintJob(request: PaintRequested, context: ActionContext): SurfacePainted {
            context.addObject(ExecutedStep("dispatch:${request.id}"))
            val platform = context.processContext.platformServices.agentPlatform
            val child = platform.createChildProcess(crew, context.agentProcess)
            children += child
            return child.run().last<SurfacePainted>()
                ?: error("Paint child for ${request.id} produced nothing: status=${child.status}")
        }
    }

    @Agent(description = "Crew that preps and paints one surface using the standing can")
    inner class PaintingCrewAgent {

        @Action(value = 0.5)
        fun prep(request: PaintRequested, context: ActionContext): PreparedSurface {
            context.addObject(ExecutedStep("child-prep:${request.id}"))
            return PreparedSurface(request.id)
        }

        @Action(value = 0.9)
        @AchievesGoal(description = "Surface painted", value = 1.0)
        fun paint(surface: PreparedSurface, can: PaintCan, context: ActionContext): SurfacePainted {
            context.addObject(ExecutedStep("child-paint:${surface.id}"))
            return SurfacePainted(surface.id)
        }
    }

    @Test
    fun `the paint can is used by every child and consumed by none - USE-resources share through snapshots`() {
        // AIMA 3e SS11.1: the can is a reusable resource. Child isolation
        // does not foreclose standing USE-resources, because snapshots carry
        // them in; it forecloses intermediate reuse, because each child
        // remakes its own prepared surface
        val dispatcher = PaintingDispatcherAgent()
        val process = create(
            dispatcher,
            "child-primary-paint-can",
            ProcessOptions.DEFAULT.withEvolving(Evolving(execution = EpisodeExecution.IN_PROCESS)),
            PaintCan("red"),
        )
        process.evolve(PaintRequested("job-1"))

        val afterFirst = process.run()
        assertEquals(AgentProcessStatusCode.STUCK, afterFirst.status)

        process.evolve(PaintRequested("job-2"))
        val afterSecond = afterFirst.run()

        assertEquals(AgentProcessStatusCode.STUCK, afterSecond.status)
        assertNotNull(afterSecond.last<PaintCan>(), "The can survives every child: used, never consumed")
        assertEquals(2, dispatcher.children.size, "One child per job")
        dispatcher.children.zip(listOf("job-1", "job-2")).forEach { (child, job) ->
            val steps = child.objects.filterIsInstance<ExecutedStep>().map { it.name }.filter { it.startsWith("child-") }
            assertEquals(
                listOf("child-prep:$job", "child-paint:$job"), steps,
                "Each child ran its full chain with the shared can and its own fresh prep",
            )
        }
        assertNull(afterSecond.last<PaintRequested>(), "Both occurrences consumed")
        assertNull(afterSecond.last<SurfacePainted>(), "Both results consumed")
    }

    @Agent(description = "Dispatcher whose crew fails its first attempt")
    inner class FlakyDispatchAgent {

        val children = mutableListOf<AgentProcess>()
        var attempts = 0

        private val crew: CoreAgent by lazy {
            AgentMetadataReader().createAgentMetadata(FlakyCrewAgent()) as CoreAgent
        }

        @Action(canRerun = true, value = 0.9)
        @AchievesGoal(description = "Calibration completed", value = 1.0)
        fun dispatchCalibration(request: CalibrationRequested, context: ActionContext): CalibrationCompleted {
            attempts++
            context.addObject(ExecutedStep("dispatch-attempt:$attempts"))
            val platform = context.processContext.platformServices.agentPlatform
            val child = platform.createChildProcess(crew, context.agentProcess)
            children += child
            val completed = child.run()
            if (attempts == 1) {
                error("child for ${request.id} failed: status=${completed.status}")
            }
            return completed.last<CalibrationCompleted>()
                ?: error("Calibration child for ${request.id} produced nothing: status=${completed.status}")
        }
    }

    @Agent(description = "Crew standing in for a flaky calibration")
    inner class FlakyCrewAgent {

        @Action(value = 0.9)
        @AchievesGoal(description = "Calibration performed", value = 1.0)
        fun calibrate(request: CalibrationRequested, context: ActionContext): CalibrationCompleted {
            context.addObject(ExecutedStep("child-calibrate:${request.id}"))
            return CalibrationCompleted(request.id)
        }
    }

    @Agent(description = "Dispatcher that declares its child's options instead of grafting workarounds")
    inner class DeclaredOptionsDispatcherAgent {

        val children = mutableListOf<AgentProcess>()

        // No NIRVANA graft: the child's planner is declared at the dispatch
        // site, so a HYBRID parent no longer forces its planner on the crew
        private val crew: CoreAgent by lazy {
            AgentMetadataReader().createAgentMetadata(HazardCrewAgent()) as CoreAgent
        }

        @Action(canRerun = true, value = 0.8)
        @AchievesGoal(description = "Hazard handled", value = 1.0)
        fun dispatchHazard(hazard: HazardDetected, context: ActionContext): HazardCleared {
            context.addObject(ExecutedStep("repair:${hazard.id}"))
            val platform = context.processContext.platformServices.agentPlatform
            val child = platform.createChildProcess(crew, context.agentProcess, ProcessOptions.DEFAULT)
            children += child
            return child.run().last<HazardCleared>()
                ?: error("Hazard child for ${hazard.id} produced nothing: status=${child.status}")
        }
    }

    @Test
    fun `a dispatch declares its child's options - the pairing graft is gone`() {
        // The dispatch site knows facts the platform cannot derive: the
        // child's planner, budget, and mode. Declared options replace the
        // NIRVANA-graft workaround this suite needed three times
        val dispatcher = DeclaredOptionsDispatcherAgent()
        val process = create(
            dispatcher,
            "child-options-declared",
            ProcessOptions.DEFAULT
                .withPlannerType(PlannerType.HYBRID)
                .withEvolving(Evolving(execution = EpisodeExecution.IN_PROCESS)),
        )
        process.evolve(HazardDetected("spill-9"))

        val result = process.run()

        assertEquals(AgentProcessStatusCode.STUCK, result.status, "One episode completed, then a clean park")
        val child = dispatcher.children.single()
        assertEquals(AgentProcessStatusCode.COMPLETED, child.status, "The declared GOAP child ran its whole chain")
        val childSteps = child.objects.filterIsInstance<ExecutedStep>().map { it.name }.filter { it.startsWith("child-") }
        assertEquals(listOf("child-assess:spill-9", "child-clear:spill-9"), childSteps)
        assertNull(result.last<HazardDetected>(), "The occurrence was consumed")
    }

    @Agent(description = "Crew that runs its own episode loop inside a contained episode")
    inner class SubMissionCrewAgent {

        @Action(canRerun = true, value = 0.5)
        @AchievesGoal(description = "Step collected", value = 1.0)
        fun collectStep(request: StepRequested, tally: StepTally, context: ActionContext): StepDone {
            context.addObject(ExecutedStep("child-step:${request.id}"))
            val next = StepTally(tally.count + 1)
            context.addObject(next)
            if (next.count < 2) {
                (context.agentProcess as SimpleAgentProcess).evolve(StepRequested(request.id + 1))
            }
            return StepDone(request.id)
        }

        @Condition(name = "stepsDone")
        fun stepsDone(tally: StepTally): Boolean = tally.count >= 2

        @Action(pre = ["stepsDone"], value = 0.9)
        @AchievesGoal(description = "Steps complete", value = 0.5)
        fun wrapUp(tally: StepTally): StepsDone = StepsDone(tally.count)
    }

    @Agent(description = "Parent whose contained episodes are themselves evolving loops")
    inner class TowerParentAgent {

        val children = mutableListOf<AgentProcess>()

        private val crew: CoreAgent by lazy {
            AgentMetadataReader().createAgentMetadata(SubMissionCrewAgent()) as CoreAgent
        }

        @Action(canRerun = true, value = 0.5)
        @AchievesGoal(description = "Wave done", value = 1.0)
        fun dispatchWave(wave: WaveRequested, missions: MissionTally, context: ActionContext): WaveDone {
            context.addObject(ExecutedStep("wave:${wave.id}"))
            context.addObject(StepTally(0))
            val platform = context.processContext.platformServices.agentPlatform
            val child = platform.createChildProcess(
                crew,
                context.agentProcess,
                ProcessOptions.DEFAULT.withEvolving(Evolving(GoalTarget.output(StepsDone::class.java), EpisodeExecution.IN_PROCESS)),
            )
            children += child
            (child as SimpleAgentProcess).evolve(StepRequested(1))
            val done = child.run().last<StepsDone>()
                ?: error("Sub-mission for wave ${wave.id} produced nothing: status=${child.status}")
            context.addObject(MissionTally(missions.count + 1))
            return WaveDone(done.steps)
        }

        @Condition(name = "allWaves")
        fun allWaves(missions: MissionTally): Boolean = missions.count >= 1

        @Action(pre = ["allWaves"], value = 0.9)
        @AchievesGoal(description = "Waves complete", value = 0.4)
        fun finish(missions: MissionTally): WavesComplete = WavesComplete(missions.count)
    }

    @Test
    fun `an evolving child composes inside an evolving parent - the tower is unbounded`() {
        // Rung three: a contained episode running its own episode loop. The
        // evolving declaration reaches the child only by explicit dispatch-site
        // declaration, never by inheritance, so levels compose deliberately.
        // Without declared options this is impossible: the platform strips
        // evolving from every child
        val parent = TowerParentAgent()
        val process = create(
            parent,
            "child-options-tower",
            ProcessOptions.DEFAULT.withEvolving(Evolving(GoalTarget.output(WavesComplete::class.java), EpisodeExecution.IN_PROCESS)),
            MissionTally(0),
        )
        process.evolve(WaveRequested(1))

        val result = process.run()

        assertEquals(AgentProcessStatusCode.COMPLETED, result.status, "The parent mission ended at its objective")
        assertEquals(1, result.last<WavesComplete>()?.waves, "One wave episode served the mission")

        val child = parent.children.single()
        assertEquals(AgentProcessStatusCode.COMPLETED, child.status, "The contained loop ended at its own objective")
        val childSteps = child.objects.filterIsInstance<ExecutedStep>().map { it.name }.filter { it.startsWith("child-") }
        assertEquals(
            listOf("child-step:1", "child-step:2"), childSteps,
            "The child ran its own serial episodes, self-chained through its own evolve",
        )
        assertNull(result.last<WaveRequested>(), "The parent's occurrence was consumed by the parent's episode")
    }

    @Test
    fun `a failed dispatch leaves the request unconsumed and the next tick respawns - the restart signal is respawn`() {
        val dispatcher = FlakyDispatchAgent()
        val process = create(
            dispatcher,
            "child-primary-respawn",
            ProcessOptions.DEFAULT.withEvolving(Evolving(execution = EpisodeExecution.IN_PROCESS)),
        )
        process.evolve(CalibrationRequested("cal-1"))

        runCatching { process.run() }
        assertNotNull(process.last<CalibrationRequested>(), "A failed child must not consume the occurrence")

        val retried = process.run()

        assertEquals(AgentProcessStatusCode.STUCK, retried.status)
        assertEquals(2, dispatcher.attempts, "The next tick respawned a fresh child")
        assertEquals(2, dispatcher.children.size, "Igor's restart signal: a new child, not a resumed one")
        assertNull(retried.last<CalibrationRequested>(), "The respawned child's completion consumed the occurrence")
    }
}
