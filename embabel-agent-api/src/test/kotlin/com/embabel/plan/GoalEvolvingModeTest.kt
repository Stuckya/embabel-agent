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
import com.embabel.agent.core.support.FoundingPercept
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

data class SeedRequested(val id: String)
data class KickoffDone(val id: String)
data class DoorFellOff(val id: String)
data class DoorReattached(val id: String)
data class ZoneInfo(val name: String)
data class AuditDone(val id: String)

/**
 * Derived evolving mode: the mode is declared once with withEvolving(), and
 * every episode rule is derived from the goal graph rather than declared.
 * The declaration razor: declarations that restate the graph die (target,
 * consumes, evolved); declarations that add facts the graph lacks live
 * (the mode itself). Episodicity is an environment-level classification
 * (AIMA 3e ch 2), so it is declared at process level; which goals turn out
 * episodic is observed from occurrences, since derivation is a static
 * regression over the goal graph (AIMA 3e SS10.2.2) that cannot distinguish
 * a terminal goal reading standing state from an episodic goal awaiting
 * occurrences.
 *
 * What it pins:
 * - evolve() is the whole consumer surface: no EpisodePolicy anywhere.
 * - Activation is observation: a derived rule gates its exclusive chain
 *   only after its first occurrence arrives. Before that the goal is
 *   founding scope and behaves exactly as default mode, so a seeded
 *   process that never evolves is unchanged by withEvolving().
 * - Routing is planning: a contested arrival goes to the goal the planner
 *   values highest, the same decision default mode makes over shared
 *   input types. No construction-time rejection.
 * - Terminal evaluation precedes rearming: when founding-scope work is
 *   plannable at an episode boundary, pending occurrences stay queued, so
 *   termination never depends on value tuning against the next episode.
 * - Fail fast survives derivation: goals that can never rearm are excluded
 *   with their reason at construction, and the reason surfaces at the
 *   evolve() call site.
 *
 * This suite declares EpisodeExecution.IN_PROCESS: it pins the in-process
 * rung's mode semantics. The default child rung is pinned in
 * GoalEpisodeFrameworkDispatchTest.
 */
class GoalEvolvingModeTest {

    @Agent(description = "Calibration whose chain reads a request and standing zone info")
    inner class DerivedCalibrationAgent {

        @Action(canRerun = true, value = 0.9)
        @AchievesGoal(description = "Calibration completed", value = 1.0)
        fun calibrate(request: CalibrationRequested, zone: ZoneInfo, context: ActionContext): CalibrationCompleted {
            context.addObject(ExecutedStep("calibrate:${request.id}@${zone.name}"))
            return CalibrationCompleted(request.id)
        }
    }

    private fun create(vararg seeds: Any): SimpleAgentProcess =
        evolvingProcess(DerivedCalibrationAgent(), *seeds)

    private fun evolvingProcess(
        agentInstance: Any,
        vararg seeds: Any,
        objective: GoalTarget? = null,
    ): SimpleAgentProcess {
        val blackboard = InMemoryBlackboard()
        seeds.forEach { blackboard.addObject(it) }
        val agent = AgentMetadataReader().createAgentMetadata(agentInstance) as CoreAgent
        val options = ProcessOptions.DEFAULT.withEvolving(Evolving(objective, EpisodeExecution.IN_PROCESS))
        return SimpleAgentProcess(
            "evolving-test",
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
    fun `evolve drives a derived episode - no policy declared anywhere`() {
        val process = create(ZoneInfo("zone-9"))
        process.evolve(CalibrationRequested("cal-1"))

        val result = process.run()

        assertEquals(AgentProcessStatusCode.STUCK, result.status, "One episode completed, then a clean park")
        val steps = result.objects.filterIsInstance<ExecutedStep>().map { it.name }
        assertEquals(listOf("calibrate:cal-1@zone-9"), steps, "The evolved occurrence drove the derived chain once")
        assertNull(result.last<CalibrationRequested>(), "The evolved instance was consumed by identity")
        assertNull(result.last<CalibrationCompleted>(), "The episode's consumable was consumed")
        assertNotNull(result.last<ZoneInfo>(), "The standing input was used, never consumed")
    }

    @Test
    fun `a plain fact of an activated type is standing state - designation survives derivation`() {
        val process = create(ZoneInfo("zone-9"))
        process.evolve(CalibrationRequested("cal-1"))

        val afterEpisode = process.run()
        assertEquals(
            listOf("calibrate:cal-1@zone-9"),
            afterEpisode.objects.filterIsInstance<ExecutedStep>().map { it.name },
        )

        // The first occurrence activated the derived rule, so its exclusive
        // chain now gates: a plain fact of the same type is never admitted
        afterEpisode.addObject(CalibrationRequested("cal-2"))
        val afterPlainFact = afterEpisode.run()

        val steps = afterPlainFact.objects.filterIsInstance<ExecutedStep>().map { it.name }
        assertEquals(1, steps.size, "A plain fact admits no episode; nothing ran")
        assertEquals("cal-2", afterPlainFact.last<CalibrationRequested>()?.id, "The plain fact survives untouched")
    }

    @Test
    fun `a seeded first run is the founding episode - its objective completes the process`() {
        // The seeds are the founding percept: the episode begins when the
        // agent receives a percept (AIMA 3e ch 2, p. 43). With a declared
        // objective, the founding episode is TERMINAL: its goal completing
        // completes the process, and the founding episode is the last one
        val process = evolvingProcess(
            DerivedCalibrationAgent(),
            ZoneInfo("zone-9"),
            CalibrationRequested("cal-1"),
            objective = GoalTarget.output(CalibrationCompleted::class.java),
        )

        val result = process.run()

        assertEquals(AgentProcessStatusCode.COMPLETED, result.status, "The founding objective completed the process")
        val steps = result.objects.filterIsInstance<ExecutedStep>().map { it.name }
        assertEquals(listOf("calibrate:cal-1@zone-9"), steps, "The founding chain ran once, wrapped")
        assertNotNull(result.last<CalibrationRequested>(), "Founding work is terminal: nothing was consumed")
        assertNotNull(result.last<CalibrationCompleted>(), "The satisfying output survives the terminal completion")

        val founding = process.lastCompletedEpisode
        val percept = founding?.request as? FoundingPercept
        assertNotNull(percept, "The founding episode completed last, holding the process's founding percept")
        assertEquals(
            "cal-1", percept.seeds.filterIsInstance<CalibrationRequested>().single().id,
            "The seeds are the founding percept",
        )
    }

    @Test
    fun `no objective means intentionally infinite - the process never completes itself`() {
        // A recurring process with no bookend is intentionally infinite: the
        // founding chain still runs, but no goal completion ends the process
        val process = create(ZoneInfo("zone-9"), CalibrationRequested("cal-1"))

        val result = process.run()

        assertEquals(
            AgentProcessStatusCode.STUCK, result.status,
            "The work ran and the process parked: nothing is authorized to complete it",
        )
        val steps = result.objects.filterIsInstance<ExecutedStep>().map { it.name }
        assertEquals(listOf("calibrate:cal-1@zone-9"), steps, "Founding-frame work still runs")
        assertNotNull(result.last<CalibrationCompleted>(), "The achievement stands as ordinary state")
    }

    @Test
    fun `an unroutable evolve throws naming the derived evolvable types`() {
        val process = create(ZoneInfo("zone-9"))

        val exception = assertThrows<IllegalArgumentException> {
            process.evolve(MissionReport(42))
        }
        assertTrue("MissionReport" in exception.message!!, "Names the unroutable type: ${exception.message}")
        assertTrue(
            "CalibrationRequested" in exception.message!!,
            "Lists the derived evolvable types: ${exception.message}",
        )
    }

    @Agent(description = "Two goals whose derived rules contest the same request type")
    inner class ContestedRoutesAgent {

        @Action(canRerun = true, value = 0.9)
        @AchievesGoal(description = "Calibration completed", value = 1.0)
        fun calibrate(request: CalibrationRequested, zone: ZoneInfo, context: ActionContext): CalibrationCompleted {
            context.addObject(ExecutedStep("calibrate:${request.id}"))
            return CalibrationCompleted(request.id)
        }

        @Action(canRerun = true, value = 0.1)
        @AchievesGoal(description = "Audit done", value = 0.4)
        fun audit(request: CalibrationRequested, context: ActionContext): AuditDone {
            context.addObject(ExecutedStep("audit:${request.id}"))
            return AuditDone(request.id)
        }
    }

    @Test
    fun `a contested arrival routes to the best-value goal - routing is planning`() {
        // The old dialect rejected this agent at construction. Derived mode
        // admits it: the arrival is owned by the goal the planner would
        // serve, the same value decision default mode makes over shared
        // input types, decided once at the arrival boundary
        val process = evolvingProcess(ContestedRoutesAgent(), ZoneInfo("zone-9"))
        process.evolve(CalibrationRequested("cal-1"))

        val result = process.run()

        val steps = result.objects.filterIsInstance<ExecutedStep>().map { it.name }
        assertEquals(listOf("calibrate:cal-1"), steps, "The higher-value goal owned the contested arrival")
        assertNull(result.last<AuditDone>(), "The losing candidate never ran")
        assertNull(result.last<CalibrationRequested>(), "The arrival was consumed by the owning episode")
    }

    @Agent(description = "Batch loop whose terminal goal must beat a queued occurrence without value tuning")
    inner class AdversarialBatchAgent {

        /**
         * Evolves the next occurrence even as the tally reaches 500, so an
         * eleventh request is always queued when the mission is done. The
         * batch goal out-values the terminal goal, so only a structural
         * boundary check can end the mission at ten batches.
         */
        @Action(canRerun = true, value = 0.5)
        @AchievesGoal(description = "Batch collected", value = 1.0)
        fun collectBatch(request: BatchRequested, tally: SampleTally, context: ActionContext): BatchCollected {
            context.addObject(ExecutedStep("batch:${request.id}"))
            val next = SampleTally(tally.count + 50)
            context.addObject(next)
            if (next.count <= 500) {
                (context.agentProcess as SimpleAgentProcess).evolve(BatchRequested(request.id + 1))
            }
            return BatchCollected(request.id)
        }

        @Condition(name = "missionDone")
        fun missionDone(tally: SampleTally): Boolean = tally.count >= 500

        @Action(pre = ["missionDone"], value = 0.9)
        @AchievesGoal(description = "Mission complete", value = 0.5)
        fun report(tally: SampleTally): BatchMissionDone = BatchMissionDone(tally.count)
    }

    @Test
    fun `terminal evaluation precedes rearming - an eleventh batch never runs`() {
        val process = evolvingProcess(
            AdversarialBatchAgent(),
            SampleTally(0),
            objective = GoalTarget.output(BatchMissionDone::class.java),
        )
        process.evolve(BatchRequested(1))

        val result = process.run()

        assertEquals(AgentProcessStatusCode.COMPLETED, result.status, "The founding objective ended the process")
        val steps = result.objects.filterIsInstance<ExecutedStep>().map { it.name }
        assertEquals(
            (1..10).map { "batch:$it" }, steps,
            "Ten batches ran; the queued eleventh was never admitted despite out-valuing the terminal goal",
        )
        assertEquals(500, result.last<SampleTally>()?.count, "The tally stopped at the mission target")
        assertEquals(500, result.last<BatchMissionDone>()?.samples, "The terminal goal ran on the post-consumption world")

        val founding = process.lastCompletedEpisode
        val percept = founding?.request as? FoundingPercept
        assertNotNull(percept, "The founding episode completes last: the mission itself was the outermost episode")
        assertEquals(
            0, percept.seeds.filterIsInstance<SampleTally>().single().count,
            "The founding percept holds the process's initial observations",
        )
    }

    @Agent(description = "A founding chain that evolves its follow-up work")
    inner class FoundingChainAgent {

        @Action(canRerun = true, value = 0.9)
        @AchievesGoal(description = "Kickoff done", value = 1.0)
        fun kickoff(seed: MissionReport, context: ActionContext): KickoffDone {
            context.addObject(ExecutedStep("kickoff:${seed.samples}"))
            (context.agentProcess as SimpleAgentProcess).evolve(CalibrationRequested("cal-1"))
            return KickoffDone("k-${seed.samples}")
        }

        @Action(canRerun = true, value = 0.5)
        @AchievesGoal(description = "Calibration completed", value = 0.8)
        fun calibrate(request: CalibrationRequested, context: ActionContext): CalibrationCompleted {
            context.addObject(ExecutedStep("calibrate:${request.id}"))
            return CalibrationCompleted(request.id)
        }
    }

    @Test
    fun `work the founding chain evolves records the founding episode as its cause`() {
        // Lineage roots at the process-episode, not at null: an evolve()
        // published by founding-frame work was caused by the mission itself.
        // An evolve() from outside any action remains uncaused: an external
        // percept, pinned elsewhere
        val process = evolvingProcess(FoundingChainAgent(), MissionReport(7))

        val result = process.run()

        val steps = result.objects.filterIsInstance<ExecutedStep>().map { it.name }
        assertEquals(
            listOf("kickoff:7", "calibrate:cal-1"), steps,
            "Kickoff ran in the founding frame and its evolved follow-up ran as an episode",
        )
        val episode = process.lastCompletedEpisode
        assertEquals("cal-1", (episode?.request as? CalibrationRequested)?.id, "The evolved occurrence completed")
        assertNotNull(
            episode?.causedBy?.request as? FoundingPercept,
            "The occurrence records the founding episode as its cause",
        )
    }

    @Agent(description = "Batches with a door that falls off mid-mission - the robot resumes its work")
    inner class SpotWeldingAgent {

        @Action(canRerun = true, value = 0.5)
        @AchievesGoal(description = "Batch collected", value = 1.0)
        fun collectBatch(request: BatchRequested, tally: SampleTally, context: ActionContext): BatchCollected {
            context.addObject(ExecutedStep("batch:${request.id}"))
            val next = SampleTally(tally.count + 50)
            context.addObject(next)
            if (next.count == 100) {
                context.addObject(DoorFellOff("door-7"))
            }
            if (next.count < 150) {
                (context.agentProcess as SimpleAgentProcess).evolve(BatchRequested(request.id + 1))
            }
            return BatchCollected(request.id)
        }

        /**
         * The door out-values everything, so only anchored completion keeps
         * its achievement from ending the mission.
         */
        @Action(canRerun = true, value = 0.9)
        @AchievesGoal(description = "Door safe", value = 2.0)
        fun reattachDoor(door: DoorFellOff, context: ActionContext): DoorReattached {
            context.addObject(ExecutedStep("reattach:${door.id}"))
            return DoorReattached(door.id)
        }

        @Condition(name = "missionDone")
        fun missionDone(tally: SampleTally): Boolean = tally.count >= 150

        @Action(pre = ["missionDone"], value = 0.9)
        @AchievesGoal(description = "Mission complete", value = 0.5)
        fun report(tally: SampleTally): BatchMissionDone = BatchMissionDone(tally.count)
    }

    @Test
    fun `an incidental achievement does not end the mission - the robot resumes its work`() {
        // The spot-welding robot (AIMA 3e p. 422): the door falls off, the
        // robot reattaches it and resumes its work. Completion is anchored
        // to the founding objective, so the door's high-value achievement is
        // recorded and the mission continues to its own end
        val process = evolvingProcess(
            SpotWeldingAgent(),
            SampleTally(0),
            objective = GoalTarget.output(BatchMissionDone::class.java),
        )
        process.evolve(BatchRequested(1))

        val result = process.run()

        assertEquals(AgentProcessStatusCode.COMPLETED, result.status, "The mission ended at its own objective")
        val steps = result.objects.filterIsInstance<ExecutedStep>().map { it.name }
        assertEquals(
            listOf("batch:1", "batch:2", "reattach:door-7", "batch:3"), steps,
            "The door was handled between batches and the mission resumed",
        )
        assertEquals(150, result.last<BatchMissionDone>()?.samples, "The mission ran to its own end")
        assertNotNull(result.last<DoorReattached>(), "The door achievement stands as ordinary state, never consumed")
    }

    @Test
    fun `evolving toward the committed objective fails fast - the mission is not a game`() {
        // The objective is the tournament, never one of its games: it is
        // excluded from derivation, so an occurrence evolved toward it has
        // nowhere to route and the boundary names why. Without this, the
        // objective would become episodic, consume-and-rearm forever, and
        // the mission could never end. The welder's tally is eligible only
        // for the objective, so the exclusion is the whole routing story
        val process = evolvingProcess(
            GreedyWelderAgent(),
            SampleTally(0),
            objective = GoalTarget.output(BatchMissionDone::class.java),
        )

        val exception = assertThrows<IllegalArgumentException> {
            process.evolve(SampleTally(999))
        }
        assertTrue("SampleTally" in exception.message!!, "Names the unroutable type: ${exception.message}")
        assertTrue(
            "objective" in exception.message!!,
            "Carries the objective-exclusion reason: ${exception.message}",
        )
    }

    @Agent(description = "Standing welds that out-value the terminal report")
    inner class GreedyWelderAgent {

        @Action(canRerun = true, value = 0.9)
        fun weld(tally: SampleTally, context: ActionContext): SampleTally {
            context.addObject(ExecutedStep("weld"))
            return SampleTally(tally.count + 1)
        }

        @Condition(name = "missionDone")
        fun missionDone(tally: SampleTally): Boolean = tally.count >= 3

        @Action(pre = ["missionDone"], value = 0.1)
        @AchievesGoal(description = "Mission complete", value = 0.1)
        fun report(tally: SampleTally): BatchMissionDone = BatchMissionDone(tally.count)
    }

    @Test
    fun `standing frame work can defer the ending - the deferral guarantee covers the queue, not goal monitoring`() {
        // Documented behavior, not a defect: deferred admission guarantees
        // pending occurrences never outbid the terminal plan, but standing
        // frame work competing on value is goal monitoring (AIMA 3e p. 423)
        // and can keep deferring the ending until the budget intervenes.
        // Whether a plannable objective should structurally preempt standing
        // work is an open design question, pinned here so the current answer
        // is a choice, not an accident
        val blackboard = InMemoryBlackboard()
        blackboard.addObject(SampleTally(0))
        val agent = AgentMetadataReader().createAgentMetadata(GreedyWelderAgent()) as CoreAgent
        val process = SimpleAgentProcess(
            "evolving-greedy-welder",
            null,
            agent.copy(goals = agent.goals + NIRVANA),
            ProcessOptions.DEFAULT
                .withPlannerType(PlannerType.HYBRID)
                .withEvolving(Evolving(GoalTarget.output(BatchMissionDone::class.java), EpisodeExecution.IN_PROCESS)),
            blackboard,
            dummyPlatformServices(),
            DefaultPlannerFactory,
            Instant.now(),
        )

        val result = process.run()

        assertEquals(
            AgentProcessStatusCode.TERMINATED, result.status,
            "High-value standing work deferred the ending until the action budget intervened",
        )
        assertNull(result.last<BatchMissionDone>(), "The terminal plan never won a tick on value")
        assertTrue(
            (result.last<SampleTally>()?.count ?: 0) > 3,
            "Welding continued past mission-done: goal monitoring, working as declared",
        )
    }

    @Agent(description = "A goal whose satisfying output is the standing state it reads")
    inner class AccumulatorOnlyAgent {

        @Action(canRerun = true, value = 0.9)
        @AchievesGoal(description = "Tally advanced", value = 1.0)
        fun accumulate(seed: SeedRequested, tally: SampleTally): SampleTally =
            SampleTally(tally.count + 1)
    }

    @Test
    fun `a goal that can never rearm is excluded with its reason, not rejected`() {
        // Derivation is not rejection: an agent whose goal cannot support
        // episodes still constructs, because it never asked for them. The
        // reason is recorded and surfaces exactly where it matters: at the
        // evolve() call site that needed the goal to be evolvable
        val process = evolvingProcess(AccumulatorOnlyAgent(), SampleTally(0))

        val exception = assertThrows<IllegalArgumentException> {
            process.evolve(SeedRequested("s1"))
        }
        assertTrue("SeedRequested" in exception.message!!, "Names the unroutable type: ${exception.message}")
        assertTrue(
            "standing state" in exception.message!!,
            "Carries the derivation exclusion reason: ${exception.message}",
        )
    }

}
