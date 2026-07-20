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
import com.embabel.agent.core.AgentProcessStatusCode
import com.embabel.agent.core.Episode
import com.embabel.agent.core.EpisodePolicy
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

data class ZoneInfo(val name: String)

interface CalibrationOutcome {
    val id: String
}

data class QuickCalibration(override val id: String) : CalibrationOutcome
data class FullCalibration(override val id: String) : CalibrationOutcome

/**
 * Phase-1 episode lifecycle for issue #1756, driving the contract test-first:
 * 1. HYBRID: an episode policy replaces the hand-rolled lifecycle of the baseline —
 *    no janitor action, no hide calls, no archive type. Both occurrences run,
 *    the request and satisfying output are hidden on each completion, and the
 *    ordinary mission goal still completes the process.
 * 2. GOAP: episode completion parks the process instead of completing it, and
 *    the manual wake (addObject + run) rearms the same declared goal.
 * 3. An empty episode policy preserves today's first-completion-ends-process behavior.
 * 4. A target with no producing scoped goal fails fast at construction, naming
 *    the configured target and the available goals.
 * 5. A scoped target currently blocked by missing facts is not a configuration error.
 * 6. An output target can resolve several candidate goals; ordinary selection
 *    picks one and completion consumes once.
 * 7. A bare episode infers the consumed request when the goal path has exactly
 *    one off-chain input, across a multi-step path.
 * 8. Inference fails fast when the path has more than one off-chain input.
 * 9. Two episodes consuming the same request type fail fast.
 * 10. interruptsCurrentAction is rejected in phase 1.
 * 11. Overlapping occurrences follow the documented default: the latest visible
 *     occurrence is consumed first, matching default binding.
 * 12. Episode rerun rides existing canRerun: a non-rerunnable completing action
 *     does not re-fire for a second occurrence.
 * 13. A repeatable multi-step episode reruns the whole chain fresh: intermediates
 *     manufactured on the episode path are consumed at completion, so a stale
 *     intermediate cannot shortcut the next occurrence's plan.
 * 14. Consumption never touches standing state: a self-maintained accumulator
 *     feeding the chain survives episode completion.
 */
class GoalEpisodePhase1Test {

    @Agent(description = "Standing collection with calibration episodes and a terminal mission goal")
    inner class EpisodeLifecycleAgent {

        @Action(canRerun = true, value = 0.2)
        fun collect(tally: SampleTally, context: ActionContext): SampleTally {
            val next = SampleTally(tally.count + 1)
            if (next.count == 2) {
                context.addObject(CalibrationRequested("cal-1"))
            }
            if (next.count == 4) {
                context.addObject(CalibrationRequested("cal-2"))
            }
            return next
        }

        @Condition(name = "enoughSamples")
        fun enoughSamples(tally: SampleTally): Boolean = tally.count >= 5

        @Action(pre = ["enoughSamples"], value = 0.9)
        @AchievesGoal(description = "Mission complete", value = 0.5)
        fun missionComplete(tally: SampleTally): MissionReport = MissionReport(tally.count)

        @Action(canRerun = true, value = 0.9)
        @AchievesGoal(description = "Calibration completed", value = 1.0)
        fun calibrate(request: CalibrationRequested, context: ActionContext): CalibrationCompleted {
            context.addObject(ExecutedStep("calibrate:${request.id}"))
            return CalibrationCompleted(request.id)
        }
    }

    @Agent(description = "Pure GOAP calibration episodes only, requests arrive externally")
    inner class GoapEpisodeOnlyAgent {

        @Action(canRerun = true, value = 0.9)
        @AchievesGoal(description = "Calibration completed", value = 1.0)
        fun calibrate(request: CalibrationRequested, context: ActionContext): CalibrationCompleted {
            context.addObject(ExecutedStep("calibrate:${request.id}"))
            return CalibrationCompleted(request.id)
        }
    }

    @Agent(description = "Pure GOAP calibration episode with a non-rerunnable completing action")
    inner class NonRerunnableEpisodeAgent {

        @Action(value = 0.9)
        @AchievesGoal(description = "Calibration completed", value = 1.0)
        fun calibrate(request: CalibrationRequested, context: ActionContext): CalibrationCompleted {
            context.addObject(ExecutedStep("calibrate:${request.id}"))
            return CalibrationCompleted(request.id)
        }
    }

    @Agent(description = "Standing collection where a mid-run fact activates a calibration goal")
    inner class FirstCompletionEndsAgent {

        @Action(canRerun = true, value = 0.2)
        fun collect(tally: SampleTally, context: ActionContext): SampleTally {
            val next = SampleTally(tally.count + 1)
            if (next.count == 2) {
                context.addObject(CalibrationRequested("cal-1"))
            }
            return next
        }

        @Action(value = 0.9)
        @AchievesGoal(description = "Calibration completed", value = 1.0)
        fun calibrate(request: CalibrationRequested): CalibrationCompleted =
            CalibrationCompleted(request.id)
    }

    @Agent(description = "Two candidate calibration goals satisfied by assignable outcome types")
    inner class MultiCandidateAgent {

        @Condition(name = "quickEligible")
        fun quickEligible(tally: SampleTally): Boolean = tally.count < 100

        @Action(pre = ["quickEligible"], value = 0.9)
        @AchievesGoal(description = "Quick calibration", value = 1.0)
        fun quickCal(request: CalibrationRequested, context: ActionContext): QuickCalibration {
            context.addObject(ExecutedStep("quickCal:${request.id}"))
            return QuickCalibration(request.id)
        }

        @Action(value = 0.3)
        @AchievesGoal(description = "Full calibration", value = 0.4)
        fun fullCal(request: CalibrationRequested, context: ActionContext): FullCalibration {
            context.addObject(ExecutedStep("fullCal:${request.id}"))
            return FullCalibration(request.id)
        }
    }

    @Agent(description = "Two-step calibration path whose only off-chain input is the request")
    inner class TwoStepEpisodeAgent {

        @Action(canRerun = true, value = 0.5)
        fun prepKit(request: CalibrationRequested, context: ActionContext): CalibrationKit {
            context.addObject(ExecutedStep("prepKit:${request.id}"))
            return CalibrationKit(request.id)
        }

        // canRerun = false keeps this episode deliberately one-shot;
        // the repeatable variant is RepeatableTwoStepAgent
        @Action(value = 0.9)
        @AchievesGoal(description = "Calibration completed", value = 1.0)
        fun calibrate(kit: CalibrationKit, context: ActionContext): CalibrationCompleted {
            context.addObject(ExecutedStep("calibrate:${kit.id}"))
            return CalibrationCompleted(kit.id)
        }
    }

    @Agent(description = "Repeatable two-step calibration path with no hand-rolled cleanup")
    inner class RepeatableTwoStepAgent {

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

    @Agent(description = "Standing accumulator feeds the calibration chain and a terminal mission goal")
    inner class AccumulatorChainAgent {

        @Action(canRerun = true, value = 0.2)
        fun collect(tally: SampleTally, context: ActionContext): SampleTally {
            val next = SampleTally(tally.count + 1)
            if (next.count == 2) {
                context.addObject(CalibrationRequested("cal-1"))
            }
            return next
        }

        @Condition(name = "enoughSamples")
        fun enoughSamples(tally: SampleTally): Boolean = tally.count >= 5

        @Action(pre = ["enoughSamples"], value = 0.9)
        @AchievesGoal(description = "Mission complete", value = 0.5)
        fun missionComplete(tally: SampleTally): MissionReport = MissionReport(tally.count)

        // The tally is on the goal path here: SampleTally is a chain input
        // produced by a scoped action. It is standing state, not an episode product
        @Action(canRerun = true, value = 0.9)
        @AchievesGoal(description = "Calibration completed", value = 1.0)
        fun calibrate(request: CalibrationRequested, tally: SampleTally, context: ActionContext): CalibrationCompleted {
            context.addObject(ExecutedStep("calibrate:${request.id}@${tally.count}"))
            return CalibrationCompleted(request.id)
        }
    }

    @Agent(description = "Calibration path with two off-chain inputs")
    inner class AmbiguousInputAgent {

        @Action(canRerun = true, value = 0.9)
        @AchievesGoal(description = "Calibration completed", value = 1.0)
        fun calibrate(request: CalibrationRequested, zone: ZoneInfo): CalibrationCompleted =
            CalibrationCompleted("${request.id}@${zone.name}")
    }

    private fun create(
        instance: Any,
        processId: String,
        options: ProcessOptions,
        vararg seeds: Any,
    ): SimpleAgentProcess {
        val blackboard = InMemoryBlackboard()
        seeds.forEach { blackboard.addObject(it) }

        val reader = AgentMetadataReader()
        val agent = reader.createAgentMetadata(instance) as com.embabel.agent.core.Agent
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

    private fun run(
        instance: Any,
        processId: String,
        options: ProcessOptions,
        vararg seeds: Any,
    ): com.embabel.agent.core.AgentProcess =
        create(instance, processId, options, *seeds).run()

    private fun calibrationEpisode(): EpisodePolicy =
        EpisodePolicy
            .episode(GoalTarget.output(CalibrationCompleted::class.java))
            .consumeOnCompletion(CalibrationRequested::class.java)

    @Test
    fun `episode policy replaces the hand-rolled lifecycle - no janitor, no hide calls, no value tuning`() {
        val result = run(
            EpisodeLifecycleAgent(),
            "phase1-hybrid-lifecycle",
            ProcessOptions.DEFAULT
                .withPlannerType(PlannerType.HYBRID)
                .withEpisodes(calibrationEpisode()),
            SampleTally(0),
        )

        assertEquals(AgentProcessStatusCode.COMPLETED, result.status)
        assertEquals(5, result.last<SampleTally>()?.count, "Standing work survived both episodes")
        assertEquals(5, result.last<MissionReport>()?.samples, "Ordinary terminal goal still completes the process")

        val calibrated = result.objects.filterIsInstance<ExecutedStep>()
            .map { it.name }.filter { it.startsWith("calibrate:") }
        assertEquals(listOf("calibrate:cal-1", "calibrate:cal-2"), calibrated, "Both occurrences ran the episode")

        // Consumption: the exact requests and satisfying outputs are hidden
        assertNull(result.last<CalibrationRequested>(), "Consumed requests are no longer visible")
        assertNull(result.last<CalibrationCompleted>(), "Satisfying outputs are no longer visible")
    }

    @Test
    fun `episode completion parks pure GOAP instead of completing - manual wake rearms the goal`() {
        val process = create(
            GoapEpisodeOnlyAgent(),
            "phase1-goap-park-rearm",
            ProcessOptions.DEFAULT.withEpisodes(calibrationEpisode()),
            CalibrationRequested("cal-1"),
        )

        val parked = process.run()
        // The episode completed, so the process must not COMPLETE; with no further
        // request and no other goal there is no plan, so it parks. Phase 2 owns the wake.
        assertEquals(AgentProcessStatusCode.STUCK, parked.status)
        assertNull(parked.last<CalibrationRequested>())
        assertNull(parked.last<CalibrationCompleted>(), "Earlier output cannot pre-satisfy the next occurrence")

        parked.addObject(CalibrationRequested("cal-2"))
        val rearmed = parked.run()

        assertEquals(AgentProcessStatusCode.STUCK, rearmed.status)
        val calibrated = rearmed.objects.filterIsInstance<ExecutedStep>().map { it.name }
        assertEquals(
            listOf("calibrate:cal-1", "calibrate:cal-2"), calibrated,
            "The same declared goal ran once per occurrence",
        )
    }

    @Test
    fun `empty episode policy preserves todays first-completion-ends-process behavior`() {
        val result = run(
            FirstCompletionEndsAgent(),
            "phase1-empty-policy",
            ProcessOptions.DEFAULT
                .withPlannerType(PlannerType.HYBRID)
                .withEpisodes(EpisodePolicy.NONE),
            SampleTally(0),
        )

        assertEquals(AgentProcessStatusCode.COMPLETED, result.status)
        assertEquals(2, result.last<SampleTally>()?.count, "Baseline behavior: first completion ended the process")
        assertNotNull(result.last<CalibrationCompleted>(), "Output remains visible without an episode policy")
    }

    @Test
    fun `an episode target with no scoped producer fails fast and names target and available goals`() {
        val exception = assertThrows<IllegalArgumentException> {
            create(
                GoapEpisodeOnlyAgent(),
                "phase1-no-producer",
                ProcessOptions.DEFAULT.withEpisodes(
                    EpisodePolicy
                        .episode(GoalTarget.output(MissionReport::class.java))
                        .consumeOnCompletion(CalibrationRequested::class.java)
                ),
            )
        }
        assertTrue("MissionReport" in exception.message!!, "Error names the configured target: ${exception.message}")
        assertTrue("calibrate" in exception.message!!, "Error lists the available goals: ${exception.message}")

        val namedException = assertThrows<IllegalArgumentException> {
            create(
                GoapEpisodeOnlyAgent(),
                "phase1-no-named-goal",
                ProcessOptions.DEFAULT.withEpisodes(
                    EpisodePolicy
                        .episode(GoalTarget.named("noSuchGoal"))
                        .consumeOnCompletion(CalibrationRequested::class.java)
                ),
            )
        }
        assertTrue("noSuchGoal" in namedException.message!!)
    }

    @Test
    fun `a scoped target currently blocked by missing facts is not a configuration error`() {
        // No CalibrationRequested seeded: the goal is unreachable right now,
        // but that is a planner concern, not invalid configuration
        val process = create(
            GoapEpisodeOnlyAgent(),
            "phase1-blocked-target",
            ProcessOptions.DEFAULT.withEpisodes(calibrationEpisode()),
        )
        assertEquals(AgentProcessStatusCode.STUCK, process.run().status)
    }

    @Test
    fun `an output target resolves multiple candidate goals and completion consumes once`() {
        val result = run(
            MultiCandidateAgent(),
            "phase1-multi-candidate",
            ProcessOptions.DEFAULT.withEpisodes(
                EpisodePolicy
                    .episode(GoalTarget.output(CalibrationOutcome::class.java))
                    .consumeOnCompletion(CalibrationRequested::class.java)
            ),
            SampleTally(0),
            CalibrationRequested("cal-1"),
        )

        assertEquals(AgentProcessStatusCode.STUCK, result.status, "Episode completion parks rather than completes")
        val steps = result.objects.filterIsInstance<ExecutedStep>().map { it.name }
        assertEquals(listOf("quickCal:cal-1"), steps, "Ordinary selection picked one achievable candidate")
        assertNull(result.last<CalibrationRequested>(), "The single completion consumed the request")
        assertNull(result.last<QuickCalibration>(), "And the satisfying output")
    }

    @Test
    fun `a bare episode infers the consumed request across a multi-step path`() {
        val result = run(
            TwoStepEpisodeAgent(),
            "phase1-inference",
            ProcessOptions.DEFAULT.withEpisodes(
                EpisodePolicy.episode(GoalTarget.output(CalibrationCompleted::class.java))
            ),
            CalibrationRequested("cal-1"),
        )

        assertEquals(AgentProcessStatusCode.STUCK, result.status)
        val steps = result.objects.filterIsInstance<ExecutedStep>().map { it.name }
        assertEquals(listOf("prepKit:cal-1", "calibrate:cal-1"), steps)
        // CalibrationKit is produced by prepKit, so the only off-chain input is the request
        assertNull(result.last<CalibrationRequested>(), "Inferred request was consumed")
        assertNull(result.last<CalibrationCompleted>())
    }

    @Test
    fun `inference fails fast when the goal path has more than one off-chain input`() {
        val exception = assertThrows<IllegalArgumentException> {
            create(
                AmbiguousInputAgent(),
                "phase1-ambiguous-inference",
                ProcessOptions.DEFAULT.withEpisodes(
                    EpisodePolicy.episode(GoalTarget.output(CalibrationCompleted::class.java))
                ),
            )
        }
        assertTrue("CalibrationRequested" in exception.message!!, "Error lists the candidates: ${exception.message}")
        assertTrue("ZoneInfo" in exception.message!!, "Error lists the candidates: ${exception.message}")
    }

    @Test
    fun `two episodes consuming the same request type fail fast`() {
        val exception = assertThrows<IllegalArgumentException> {
            create(
                EpisodeLifecycleAgent(),
                "phase1-duplicate-consumes",
                ProcessOptions.DEFAULT.withEpisodes(
                    EpisodePolicy
                        .episode(GoalTarget.output(CalibrationCompleted::class.java))
                        .consumeOnCompletion(CalibrationRequested::class.java)
                        .episode(GoalTarget.output(MissionReport::class.java))
                        .consumeOnCompletion(CalibrationRequested::class.java)
                ),
            )
        }
        assertTrue("CalibrationRequested" in exception.message!!)
    }

    @Test
    fun `interruptsCurrentAction is rejected in phase 1`() {
        val exception = assertThrows<IllegalArgumentException> {
            create(
                GoapEpisodeOnlyAgent(),
                "phase1-no-interruption",
                ProcessOptions.DEFAULT.withEpisodes(
                    EpisodePolicy(
                        listOf(
                            Episode(
                                target = GoalTarget.output(CalibrationCompleted::class.java),
                                consumes = CalibrationRequested::class.java,
                                interruptsCurrentAction = true,
                            )
                        )
                    )
                ),
            )
        }
        assertTrue("interruptsCurrentAction" in exception.message!!)
    }

    @Test
    fun `overlapping occurrences are consumed latest-first, matching default binding`() {
        val process = create(
            GoapEpisodeOnlyAgent(),
            "phase1-overlapping-occurrences",
            ProcessOptions.DEFAULT.withEpisodes(calibrationEpisode()),
            CalibrationRequested("cal-A"),
            CalibrationRequested("cal-B"),
        )

        val result = process.run()

        assertEquals(AgentProcessStatusCode.STUCK, result.status)
        val calibrated = result.objects.filterIsInstance<ExecutedStep>().map { it.name }
        assertEquals(
            listOf("calibrate:cal-B", "calibrate:cal-A"), calibrated,
            "Default binding hands the action the latest occurrence; consumption must match it",
        )
        assertNull(result.last<CalibrationRequested>(), "Both occurrences consumed")
    }

    @Test
    fun `a repeatable two-step episode reruns the whole chain fresh - intermediates are consumed`() {
        val process = create(
            RepeatableTwoStepAgent(),
            "phase1-repeatable-two-step",
            ProcessOptions.DEFAULT.withEpisodes(calibrationEpisode()),
            CalibrationRequested("cal-1"),
        )

        val parked = process.run()
        assertEquals(AgentProcessStatusCode.STUCK, parked.status, "No stale intermediate may keep the goal reachable")
        assertNull(parked.last<CalibrationKit>(), "The kit manufactured on the episode path is consumed")
        assertNull(parked.last<CalibrationRequested>())
        assertNull(parked.last<CalibrationCompleted>())

        parked.addObject(CalibrationRequested("cal-2"))
        val rearmed = parked.run()

        assertEquals(AgentProcessStatusCode.STUCK, rearmed.status)
        val steps = rearmed.objects.filterIsInstance<ExecutedStep>().map { it.name }
        assertEquals(
            listOf("prepKit:cal-1", "calibrate:cal-1", "prepKit:cal-2", "calibrate:cal-2"), steps,
            "The second occurrence replans the entire chain with a fresh kit",
        )
    }

    @Test
    fun `standing accumulator state on the chain survives episode completion`() {
        val result = run(
            AccumulatorChainAgent(),
            "phase1-accumulator-survives",
            ProcessOptions.DEFAULT
                .withPlannerType(PlannerType.HYBRID)
                .withEpisodes(calibrationEpisode()),
            SampleTally(0),
        )

        // If consumption swept SampleTally along with the chain, collection would
        // lose its state after the episode and the mission could never complete
        assertEquals(AgentProcessStatusCode.COMPLETED, result.status)
        assertEquals(5, result.last<MissionReport>()?.samples, "Standing work continued to the terminal goal")
        val steps = result.objects.filterIsInstance<ExecutedStep>().map { it.name }
        assertEquals(listOf("calibrate:cal-1@2"), steps, "One episode ran, off the live tally")
        assertNull(result.last<CalibrationRequested>())
        assertNull(result.last<CalibrationCompleted>())
    }

    @Test
    fun `episode rerun rides existing canRerun - a non-rerunnable action does not re-fire`() {
        val process = create(
            NonRerunnableEpisodeAgent(),
            "phase1-non-rerunnable",
            ProcessOptions.DEFAULT.withEpisodes(calibrationEpisode()),
            CalibrationRequested("cal-1"),
        )

        val parked = process.run()
        assertEquals(AgentProcessStatusCode.STUCK, parked.status)

        parked.addObject(CalibrationRequested("cal-2"))
        val after = parked.run()

        // Phase-1 decision: the episode rearms, but action re-execution rides the
        // existing canRerun contract. A non-rerunnable completing action stays run.
        assertEquals(AgentProcessStatusCode.STUCK, after.status)
        val calibrated = after.objects.filterIsInstance<ExecutedStep>().map { it.name }
        assertEquals(listOf("calibrate:cal-1"), calibrated, "canRerun = false blocked the second occurrence")
    }
}
