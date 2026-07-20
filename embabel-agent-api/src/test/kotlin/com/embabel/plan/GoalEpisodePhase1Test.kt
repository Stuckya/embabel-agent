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
import com.embabel.agent.api.annotation.RequireNameMatch
import com.embabel.agent.api.annotation.support.AgentMetadataReader
import com.embabel.agent.api.common.ActionContext
import com.embabel.agent.api.common.PlannerType
import com.embabel.agent.api.event.AgentProcessEvent
import com.embabel.agent.api.event.AgentProcessFinishedEvent
import com.embabel.agent.api.event.EpisodeCompletedEvent
import com.embabel.agent.api.event.AgenticEventListener
import com.embabel.agent.api.event.GoalAchievedEvent
import com.embabel.agent.core.Agent as CoreAgent
import com.embabel.agent.core.AgentProcess
import com.embabel.agent.core.AgentProcessStatusCode
import com.embabel.agent.core.DynamicType
import com.embabel.agent.core.EpisodePolicy
import com.embabel.agent.core.Goal
import com.embabel.agent.core.IoBinding
import com.embabel.agent.core.GoalTarget
import com.embabel.agent.core.ProcessOptions
import com.embabel.agent.core.last
import com.embabel.agent.core.support.ConcurrentAgentProcess
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

interface SensorKit {
    val id: String
}

data class SpecificSensorKit(override val id: String) : SensorKit

interface RequestBase {
    val id: String
}

data class SpecialRequest(override val id: String) : RequestBase

interface Tally {
    val count: Int
}

data class RunningTally(override val count: Int) : Tally

data class PingTally(val count: Int)
data class PongTally(val count: Int)

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
 * 10. Overlapping occurrences follow the documented default: the latest visible
 *     occurrence is consumed first, matching default binding.
 * 11. Episode rerun rides existing canRerun: a non-rerunnable completing action
 *     does not re-fire for a second occurrence.
 * 12. A repeatable multi-step episode reruns the whole chain fresh: intermediates
 *     manufactured on the episode path are consumed at completion, so a stale
 *     intermediate cannot shortcut the next occurrence's plan.
 * 13. Consumption never touches standing state: a self-maintained accumulator
 *     feeding the chain survives episode completion.
 * 14. Chain analysis matches the planner's assignability rules: a producer
 *     returning a subtype satisfies a supertype consumer, so inference and
 *     consumption must traverse it (two tests: inference, consumption).
 * 15. An explicit consume type that is not an off-chain input of the episode's
 *     chain fails fast instead of silently consuming nothing.
 * 16. Two episodes resolving to the same declared goal fail fast: without
 *     occurrence tracking, completion could pair the wrong request.
 * 17. Consumption is scoped to the completed candidate's chain: another
 *     candidate's output type visible on the blackboard survives.
 * 18. Distinct-but-equal request occurrences are separate occurrences under
 *     identity-based hiding: each is handled and consumed exactly once.
 * 19. A non-rerunnable intermediate makes the whole episode one-shot, exactly
 *     like a non-rerunnable completing action (pin).
 * 20. A named target matching duplicate goal identities fails fast.
 * 21. The consumed request must be required on every candidate's completion
 *     path: candidates driven by different request types are rejected.
 * 22. A goal reachable through paths with different drivers cannot consume a
 *     request that only some paths observe.
 * 23. Explicit consumption must match the off-chain binding type exactly:
 *     consuming a subtype of what the action accepts is rejected.
 * 24. A request arriving under a named binding is rejected in phase 1:
 *     consumption follows default-binding semantics only.
 * 25. An output target resolving distinct goals that share a name fails fast
 *     instead of silently collapsing them.
 * 26. A visible chain product is planning bait, not an unexecuted-path
 *     casualty: the planner may route through it and consumption follows the
 *     completed plan (pin).
 * 27. Self-maintenance follows planner semantics: an accumulator whose action
 *     returns a subtype of its input is standing state and survives
 *     consumption, exactly like the exact-type accumulator.
 * 28. A self-refining producer makes the request optional on some path, so the
 *     episode is rejected by every-path validation (pin: the too-broad
 *     self-maintenance hazard cannot be configured).
 * 29. Equal satisfying outputs across sequential episodes: an output type that
 *     carries no occurrence identity must not prevent the next episode from
 *     completing after its equal predecessor was consumed.
 * 30. A goal whose satisfying output is standing state is rejected: an output
 *     that survives consumption would keep the goal satisfied forever.
 * 31. A candidate goal sharing its name with another scoped goal is rejected:
 *     completion matches by name, so the collision could consume the episode's
 *     request for an ordinary goal's completion.
 * 32. consumeOnCompletion without an episode fails fast (pin).
 * 33. consumeOnCompletion twice on one episode fails fast instead of silently
 *     overwriting the first request type.
 * 34. A blank named target fails at construction, not at resolution.
 * 35. A candidate goal without a JVM output type is rejected (pin: its
 *     required-input set is empty, so the every-path rule already fires).
 * 36. A seeded output does not pre-satisfy a reader-built episode goal:
 *     hasRun in goal preconditions demands real work, and completion sweeps
 *     every visible instance of a product type.
 * 37. A candidate goal with a dynamic (non-JVM) output type is rejected:
 *     its instances could never be hidden, so completion would spin.
 * 38. Standing state maintained by a two-action cycle survives consumption,
 *     exactly like a single-action accumulator.
 * 39. Every visible instance of a product type is consumed at completion:
 *     a stale duplicate cannot shortcut the next occurrence's plan.
 * 40. Two independent episodes in one process each complete and consume.
 * 41. Episode completion emits GoalAchievedEvent but never a process-finished
 *     event; the terminal goal emits both (pin).
 * 42. A failing completing action leaves the request unconsumed for retry.
 * 43. A completing action that publishes the next request livelocks until the
 *     action budget ends it (pin: occurrence pairing rides ordering in
 *     phase 1; publish follow-ups from a non-completing action).
 * 44. ConcurrentAgentProcess shares the episode contract: park and rearm.
 * 45. EpisodeCompletedEvent is observed only after consumption: a listener
 *     reading the blackboard at event time sees the consumed state.
 * 46. Terminal completion emits both a plain GoalAchievedEvent and a
 *     process-finished event; episodes emit neither of those.
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

    @Agent(description = "Two-step path whose intermediate is consumed through a supertype")
    inner class SubtypeChainAgent {

        @Action(canRerun = true, value = 0.5)
        fun prepKit(request: CalibrationRequested, context: ActionContext): SpecificSensorKit {
            context.addObject(ExecutedStep("prepKit:${request.id}"))
            return SpecificSensorKit(request.id)
        }

        // The planner routes SpecificSensorKit into SensorKit via assignable effects
        @Action(canRerun = true, value = 0.9)
        @AchievesGoal(description = "Calibration completed", value = 1.0)
        fun calibrate(kit: SensorKit, context: ActionContext): CalibrationCompleted {
            context.addObject(ExecutedStep("calibrate:${kit.id}"))
            return CalibrationCompleted(kit.id)
        }
    }

    @Agent(description = "Two candidate outcome goals, one gated off by a condition")
    inner class GatedCandidateAgent {

        @Condition(name = "fullEligible")
        fun fullEligible(tally: SampleTally): Boolean = tally.count >= 100

        @Action(value = 0.9)
        @AchievesGoal(description = "Quick calibration", value = 1.0)
        fun quickCal(request: CalibrationRequested, context: ActionContext): QuickCalibration {
            context.addObject(ExecutedStep("quickCal:${request.id}"))
            return QuickCalibration(request.id)
        }

        @Action(pre = ["fullEligible"], value = 0.3)
        @AchievesGoal(description = "Full calibration", value = 0.4)
        fun fullCal(request: CalibrationRequested, context: ActionContext): FullCalibration {
            context.addObject(ExecutedStep("fullCal:${request.id}"))
            return FullCalibration(request.id)
        }
    }

    @Agent(description = "Candidate goals driven by different request types")
    inner class SplitCandidateAgent {

        @Action(value = 0.9)
        @AchievesGoal(description = "Quick calibration", value = 1.0)
        fun quickCal(request: CalibrationRequested): QuickCalibration =
            QuickCalibration(request.id)

        @Action(value = 0.3)
        @AchievesGoal(description = "Full calibration", value = 0.4)
        fun fullCal(zone: ZoneInfo): FullCalibration =
            FullCalibration(zone.name)
    }

    @Agent(description = "One goal reachable through two paths with different drivers")
    inner class OrPathAgent {

        @Action(canRerun = true, value = 0.5)
        fun prepFromRequest(request: CalibrationRequested): CalibrationKit =
            CalibrationKit(request.id)

        @Action(canRerun = true, value = 0.5)
        fun prepFromZone(zone: ZoneInfo): CalibrationKit =
            CalibrationKit(zone.name)

        @Action(canRerun = true, value = 0.9)
        @AchievesGoal(description = "Calibration completed", value = 1.0)
        fun calibrate(kit: CalibrationKit): CalibrationCompleted =
            CalibrationCompleted(kit.id)
    }

    @Agent(description = "Request accepted through a supertype binding")
    inner class SupertypeRequestAgent {

        @Action(canRerun = true, value = 0.9)
        @AchievesGoal(description = "Calibration completed", value = 1.0)
        fun calibrate(request: RequestBase): CalibrationCompleted =
            CalibrationCompleted(request.id)
    }

    @Agent(description = "Request arriving under a named binding")
    inner class NamedBindingAgent {

        @Action(canRerun = true, value = 0.9)
        @AchievesGoal(description = "Calibration completed", value = 1.0)
        fun calibrate(@RequireNameMatch("special") request: CalibrationRequested): CalibrationCompleted =
            CalibrationCompleted(request.id)
    }

    @Agent(description = "Standing state maintained by a two-action ping-pong cycle")
    inner class PingPongAccumulatorAgent {

        // Lockstep conditions force alternation: an append-only blackboard
        // keeps old instances visible, so availability alone cannot alternate
        @Condition(name = "pingReady")
        fun pingReady(p: PingTally, q: PongTally): Boolean = p.count == q.count

        @Condition(name = "pongReady")
        fun pongReady(p: PingTally, q: PongTally): Boolean = q.count == p.count + 1

        @Action(pre = ["pingReady"], canRerun = true, value = 0.2)
        fun ping(p: PingTally, context: ActionContext): PongTally {
            context.addObject(ExecutedStep("ping"))
            val next = p.count + 1
            if (next == 2) {
                context.addObject(CalibrationRequested("cal-1"))
            }
            return PongTally(next)
        }

        @Action(pre = ["pongReady"], canRerun = true, value = 0.2)
        fun pong(q: PongTally, context: ActionContext): PingTally {
            context.addObject(ExecutedStep("pong"))
            return PingTally(q.count)
        }

        @Condition(name = "enoughSamples")
        fun enoughSamples(p: PingTally): Boolean = p.count >= 3

        @Action(pre = ["enoughSamples"], value = 0.9)
        @AchievesGoal(description = "Mission complete", value = 0.5)
        fun missionComplete(p: PingTally): MissionReport = MissionReport(p.count)

        @Action(canRerun = true, value = 0.9)
        @AchievesGoal(description = "Calibration completed", value = 1.0)
        fun calibrate(request: CalibrationRequested, p: PingTally, context: ActionContext): CalibrationCompleted {
            context.addObject(ExecutedStep("calibrate:${request.id}"))
            return CalibrationCompleted(request.id)
        }
    }

    @Agent(description = "Two independent request-driven episodes")
    inner class TwoEpisodeAgent {

        @Action(canRerun = true, value = 0.9)
        @AchievesGoal(description = "Calibration completed", value = 1.0)
        fun calibrate(request: CalibrationRequested, context: ActionContext): CalibrationCompleted {
            context.addObject(ExecutedStep("calibrate:${request.id}"))
            return CalibrationCompleted(request.id)
        }

        @Action(canRerun = true, value = 0.5)
        @AchievesGoal(description = "Zone audited", value = 0.8)
        fun audit(zone: ZoneInfo, context: ActionContext): CalibrationArchived {
            context.addObject(ExecutedStep("audit:${zone.name}"))
            return CalibrationArchived(zone.name)
        }
    }

    @Agent(description = "Completing action that publishes the next request")
    inner class SelfRearmingAgent {

        @Action(canRerun = true, value = 0.9)
        @AchievesGoal(description = "Calibration completed", value = 1.0)
        fun calibrate(request: CalibrationRequested, context: ActionContext): CalibrationCompleted {
            context.addObject(ExecutedStep("calibrate:${request.id}"))
            if (request.id == "cal-1") {
                context.addObject(CalibrationRequested("cal-2"))
            }
            return CalibrationCompleted(request.id)
        }
    }

    @Agent(description = "Completing action that fails on its first attempt")
    inner class FlakyCalibrationAgent {

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

    @Agent(description = "Two distinct goals driven by the same request type")
    inner class DualRequestGoalAgent {

        @Action(canRerun = true, value = 0.9)
        @AchievesGoal(description = "Calibration completed", value = 1.0)
        fun calibrate(request: CalibrationRequested): CalibrationCompleted =
            CalibrationCompleted(request.id)

        @Action(canRerun = true, value = 0.5)
        @AchievesGoal(description = "Calibration audited", value = 0.8)
        fun audit(request: CalibrationRequested): CalibrationArchived =
            CalibrationArchived(request.id)
    }

    @Agent(description = "Standing accumulator whose action returns a subtype of its input")
    inner class SubtypeAccumulatorAgent {

        @Action(canRerun = true, value = 0.2)
        fun collect(tally: Tally, context: ActionContext): RunningTally {
            context.addObject(ExecutedStep("collect"))
            val next = RunningTally(tally.count + 1)
            if (next.count == 2) {
                context.addObject(CalibrationRequested("cal-1"))
            }
            return next
        }

        @Condition(name = "enoughSamples")
        fun enoughSamples(tally: Tally): Boolean = tally.count >= 5

        @Action(pre = ["enoughSamples"], value = 0.9)
        @AchievesGoal(description = "Mission complete", value = 0.5)
        fun missionComplete(tally: Tally): MissionReport = MissionReport(tally.count)

        @Action(canRerun = true, value = 0.9)
        @AchievesGoal(description = "Calibration completed", value = 1.0)
        fun calibrate(request: CalibrationRequested, tally: Tally, context: ActionContext): CalibrationCompleted {
            context.addObject(ExecutedStep("calibrate:${request.id}"))
            return CalibrationCompleted(request.id)
        }
    }

    @Agent(description = "Goal action whose satisfying output evolves its own standing state")
    inner class SelfMaintainedOutputAgent {

        @Action(canRerun = true, value = 0.9)
        @AchievesGoal(description = "Calibration recorded", value = 1.0)
        fun recordCalibration(request: CalibrationRequested, tally: Tally, context: ActionContext): RunningTally {
            context.addObject(ExecutedStep("record:${request.id}"))
            return RunningTally(tally.count + 1)
        }
    }

    @Agent(description = "Calibration whose output carries no occurrence identity")
    inner class ConstantOutputAgent {

        @Action(canRerun = true, value = 0.9)
        @AchievesGoal(description = "Calibration completed", value = 1.0)
        fun calibrate(request: CalibrationRequested, context: ActionContext): CalibrationCompleted {
            context.addObject(ExecutedStep("calibrate:${request.id}"))
            return CalibrationCompleted("done")
        }
    }

    @Agent(description = "Calibration chain with a self-refining kit producer")
    inner class RefiningChainAgent {

        @Action(canRerun = true, value = 0.5)
        fun prepKit(request: CalibrationRequested): CalibrationKit =
            CalibrationKit(request.id)

        @Action(canRerun = true, value = 0.5)
        fun refineKit(kit: CalibrationKit): CalibrationKit =
            CalibrationKit("${kit.id}-refined")

        @Action(canRerun = true, value = 0.9)
        @AchievesGoal(description = "Calibration completed", value = 1.0)
        fun calibrate(kit: CalibrationKit): CalibrationCompleted =
            CalibrationCompleted(kit.id)
    }

    @Agent(description = "Two-step calibration whose intermediate step is one-shot")
    inner class OneShotIntermediateAgent {

        @Action(value = 0.5)
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

    private fun create(
        instance: Any,
        processId: String,
        options: ProcessOptions,
        vararg seeds: Any,
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
    ): AgentProcess =
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
        // Both chains legitimately observe the request; the duplicate consume is the error
        val exception = assertThrows<IllegalArgumentException> {
            create(
                DualRequestGoalAgent(),
                "phase1-duplicate-consumes",
                ProcessOptions.DEFAULT.withEpisodes(
                    EpisodePolicy
                        .episode(GoalTarget.output(CalibrationCompleted::class.java))
                        .consumeOnCompletion(CalibrationRequested::class.java)
                        .episode(GoalTarget.output(CalibrationArchived::class.java))
                        .consumeOnCompletion(CalibrationRequested::class.java)
                ),
            )
        }
        assertTrue("CalibrationRequested" in exception.message!!)
        assertTrue("only one episode" in exception.message!!, "The duplicate rule is what fired: ${exception.message}")
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
    fun `inference traverses assignable producers - a subtype intermediate is on-chain`() {
        // SensorKit is produced as SpecificSensorKit; the only off-chain input is the request
        val process = create(
            SubtypeChainAgent(),
            "phase1-subtype-inference",
            ProcessOptions.DEFAULT.withEpisodes(
                EpisodePolicy.episode(GoalTarget.output(CalibrationCompleted::class.java))
            ),
            CalibrationRequested("cal-1"),
        )

        val result = process.run()
        assertEquals(AgentProcessStatusCode.STUCK, result.status)
        assertNull(result.last<CalibrationRequested>(), "The inferred request was consumed")
    }

    @Test
    fun `consumption traverses assignable producers - a subtype intermediate is consumed`() {
        val process = create(
            SubtypeChainAgent(),
            "phase1-subtype-consumption",
            ProcessOptions.DEFAULT.withEpisodes(calibrationEpisode()),
            CalibrationRequested("cal-1"),
        )

        val parked = process.run()
        assertEquals(AgentProcessStatusCode.STUCK, parked.status, "No stale subtype kit may keep the goal reachable")
        assertNull(parked.last<SpecificSensorKit>(), "The kit is an episode product through its supertype")

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
    fun `an explicit consume type that is not an off-chain input of the chain fails fast`() {
        val exception = assertThrows<IllegalArgumentException> {
            create(
                GoapEpisodeOnlyAgent(),
                "phase1-unrelated-consumes",
                ProcessOptions.DEFAULT.withEpisodes(
                    EpisodePolicy
                        .episode(GoalTarget.output(CalibrationCompleted::class.java))
                        .consumeOnCompletion(ZoneInfo::class.java)
                ),
            )
        }
        assertTrue("ZoneInfo" in exception.message!!, "Error names the unrelated type: ${exception.message}")
    }

    @Test
    fun `two episodes resolving to the same declared goal fail fast`() {
        val goalName = "${AmbiguousInputAgent::class.java.name}.calibrate"
        val exception = assertThrows<IllegalArgumentException> {
            create(
                AmbiguousInputAgent(),
                "phase1-overlapping-episodes",
                ProcessOptions.DEFAULT.withEpisodes(
                    EpisodePolicy
                        .episode(GoalTarget.output(CalibrationCompleted::class.java))
                        .consumeOnCompletion(CalibrationRequested::class.java)
                        .episode(GoalTarget.named(goalName))
                        .consumeOnCompletion(ZoneInfo::class.java)
                ),
            )
        }
        assertTrue("calibrate" in exception.message!!, "Error names the shared goal: ${exception.message}")
    }

    @Test
    fun `consumption is scoped to the completed candidate - another candidates output survives`() {
        // fullCal is gated off, so the seeded decoy cannot satisfy its goal;
        // quickCal's completion must not sweep the other candidate's type
        val result = run(
            GatedCandidateAgent(),
            "phase1-candidate-scoped-consumption",
            ProcessOptions.DEFAULT.withEpisodes(
                EpisodePolicy
                    .episode(GoalTarget.output(CalibrationOutcome::class.java))
                    .consumeOnCompletion(CalibrationRequested::class.java)
            ),
            SampleTally(0),
            FullCalibration("decoy"),
            CalibrationRequested("cal-1"),
        )

        assertEquals(AgentProcessStatusCode.STUCK, result.status)
        val steps = result.objects.filterIsInstance<ExecutedStep>().map { it.name }
        assertEquals(listOf("quickCal:cal-1"), steps)
        assertNull(result.last<QuickCalibration>(), "The completed candidate's output is consumed")
        assertNotNull(result.last<FullCalibration>(), "The other candidate's type is not this completion's product")
    }

    @Test
    fun `distinct but equal request occurrences are separate occurrences - each handled once`() {
        // Identity-based hiding: consuming an occurrence hides exactly that
        // object, so an equal but distinct occurrence remains a live request
        val process = create(
            GoapEpisodeOnlyAgent(),
            "phase1-equal-occurrences",
            ProcessOptions.DEFAULT.withEpisodes(calibrationEpisode()),
            CalibrationRequested("cal-same"),
            CalibrationRequested("cal-same"),
        )

        val result = process.run()

        assertEquals(AgentProcessStatusCode.STUCK, result.status)
        val calibrated = result.objects.filterIsInstance<ExecutedStep>().map { it.name }
        assertEquals(
            listOf("calibrate:cal-same", "calibrate:cal-same"), calibrated,
            "Two occurrences, two episodes",
        )
        assertNull(result.last<CalibrationRequested>(), "Both occurrences consumed")
    }

    @Test
    fun `a non-rerunnable intermediate makes the whole episode one-shot`() {
        val process = create(
            OneShotIntermediateAgent(),
            "phase1-one-shot-intermediate",
            ProcessOptions.DEFAULT.withEpisodes(calibrationEpisode()),
            CalibrationRequested("cal-1"),
        )

        val parked = process.run()
        assertEquals(AgentProcessStatusCode.STUCK, parked.status)

        parked.addObject(CalibrationRequested("cal-2"))
        val after = parked.run()

        // Rerun rides canRerun for every action the next occurrence needs,
        // not just the completing one: a one-shot intermediate gates the chain
        assertEquals(AgentProcessStatusCode.STUCK, after.status)
        val steps = after.objects.filterIsInstance<ExecutedStep>().map { it.name }
        assertEquals(listOf("prepKit:cal-1", "calibrate:cal-1"), steps)
    }

    @Test
    fun `a named target matching duplicate goal identities fails fast`() {
        val agent = CoreAgent(
            name = "dup-goal-agent",
            provider = "test",
            description = "duplicate goal names",
            actions = emptyList(),
            goals = setOf(
                Goal(
                    name = "dupGoal",
                    description = "first",
                    satisfiedBy = CalibrationCompleted::class.java,
                ),
                Goal(
                    name = "dupGoal",
                    description = "second",
                    satisfiedBy = QuickCalibration::class.java,
                ),
            ),
        )
        val exception = assertThrows<IllegalArgumentException> {
            SimpleAgentProcess(
                "phase1-duplicate-names",
                null,
                agent,
                ProcessOptions.DEFAULT.withEpisodes(
                    EpisodePolicy
                        .episode(GoalTarget.named("dupGoal"))
                        .consumeOnCompletion(CalibrationRequested::class.java)
                ),
                InMemoryBlackboard(),
                dummyPlatformServices(),
                DefaultPlannerFactory,
                Instant.now(),
            )
        }
        assertTrue("2 declared goals" in exception.message!!, "Error states the ambiguity: ${exception.message}")
    }

    @Test
    fun `candidates driven by different request types cannot share one episode`() {
        val exception = assertThrows<IllegalArgumentException> {
            create(
                SplitCandidateAgent(),
                "phase1-split-candidates",
                ProcessOptions.DEFAULT.withEpisodes(
                    EpisodePolicy
                        .episode(GoalTarget.output(CalibrationOutcome::class.java))
                        .consumeOnCompletion(CalibrationRequested::class.java)
                ),
            )
        }
        assertTrue("CalibrationRequested" in exception.message!!, "Error names the request: ${exception.message}")
    }

    @Test
    fun `a consumed request observed by only some paths to the goal is rejected`() {
        // prepFromZone can manufacture the kit without any request, so completion
        // could not pair the request with the work that actually ran
        val exception = assertThrows<IllegalArgumentException> {
            create(
                OrPathAgent(),
                "phase1-or-paths",
                ProcessOptions.DEFAULT.withEpisodes(calibrationEpisode()),
            )
        }
        assertTrue("CalibrationRequested" in exception.message!!, "Error names the request: ${exception.message}")
    }

    @Test
    fun `explicit consumption must match the off-chain binding type exactly`() {
        // The action accepts RequestBase; the planner may bind any implementation,
        // so consuming only SpecialRequest could leave the real driver visible
        val exception = assertThrows<IllegalArgumentException> {
            create(
                SupertypeRequestAgent(),
                "phase1-subtype-consumes",
                ProcessOptions.DEFAULT.withEpisodes(
                    EpisodePolicy
                        .episode(GoalTarget.output(CalibrationCompleted::class.java))
                        .consumeOnCompletion(SpecialRequest::class.java)
                ),
            )
        }
        assertTrue("SpecialRequest" in exception.message!!, "Error names the consumed type: ${exception.message}")
    }

    @Test
    fun `a request arriving under a named binding is rejected`() {
        val exception = assertThrows<IllegalArgumentException> {
            create(
                NamedBindingAgent(),
                "phase1-named-binding",
                ProcessOptions.DEFAULT.withEpisodes(calibrationEpisode()),
            )
        }
        assertTrue("special" in exception.message!!, "Error names the binding: ${exception.message}")
    }

    @Test
    fun `an output target resolving distinct goals sharing a name fails fast`() {
        val agent = CoreAgent(
            name = "dup-output-agent",
            provider = "test",
            description = "distinct goals sharing a name",
            actions = emptyList(),
            goals = setOf(
                Goal(
                    name = "dupGoal",
                    description = "first",
                    satisfiedBy = CalibrationCompleted::class.java,
                ),
                Goal(
                    name = "dupGoal",
                    description = "second",
                    satisfiedBy = CalibrationCompleted::class.java,
                ),
            ),
        )
        val exception = assertThrows<IllegalArgumentException> {
            SimpleAgentProcess(
                "phase1-dup-output-names",
                null,
                agent,
                ProcessOptions.DEFAULT.withEpisodes(
                    EpisodePolicy
                        .episode(GoalTarget.output(CalibrationCompleted::class.java))
                        .consumeOnCompletion(CalibrationRequested::class.java)
                ),
                InMemoryBlackboard(),
                dummyPlatformServices(),
                DefaultPlannerFactory,
                Instant.now(),
            )
        }
        assertTrue("dupGoal" in exception.message!!, "Error names the collision: ${exception.message}")
    }

    @Test
    fun `a visible chain product is planning bait - consumption follows the completed plan`() {
        // The decoy kit is not an unexecuted-path casualty: the planner routes
        // through it, the goal completes off it, and consumption follows the plan
        val process = create(
            RepeatableTwoStepAgent(),
            "phase1-product-bait",
            ProcessOptions.DEFAULT.withEpisodes(calibrationEpisode()),
            CalibrationRequested("cal-1"),
            CalibrationKit("decoy"),
        )

        val result = process.run()

        assertEquals(AgentProcessStatusCode.STUCK, result.status)
        val steps = result.objects.filterIsInstance<ExecutedStep>().map { it.name }
        assertEquals(listOf("calibrate:decoy"), steps, "The planner used the visible kit; prep was unnecessary")
        assertNull(result.last<CalibrationKit>(), "The kit that satisfied the plan is consumed")
        assertNull(result.last<CalibrationRequested>(), "The configured request is the consumed occurrence")
    }

    @Test
    fun `a subtype-returning accumulator is standing state and survives consumption`() {
        val result = run(
            SubtypeAccumulatorAgent(),
            "phase1-subtype-accumulator",
            ProcessOptions.DEFAULT
                .withPlannerType(PlannerType.HYBRID)
                .withEpisodes(calibrationEpisode()),
            RunningTally(0),
        )

        assertEquals(AgentProcessStatusCode.COMPLETED, result.status)
        assertEquals(5, result.last<MissionReport>()?.samples)
        val collects = result.objects.filterIsInstance<ExecutedStep>().count { it.name == "collect" }
        // If consumption hid the latest tally, collection would roll back one
        // step after the episode and need an extra collect to reach the mission
        assertEquals(5, collects, "The accumulator must not roll back at episode completion")
    }

    @Test
    fun `equal satisfying outputs across sequential episodes do not block the next completion`() {
        // The output deliberately carries no occurrence identity: consuming
        // episode 1's CalibrationCompleted("done") must not make episode 2's
        // equal output invisible at the moment it is produced
        val process = create(
            ConstantOutputAgent(),
            "phase1-equal-outputs",
            ProcessOptions.DEFAULT.withEpisodes(calibrationEpisode()),
            CalibrationRequested("cal-1"),
        )

        val parked = process.run()
        assertEquals(AgentProcessStatusCode.STUCK, parked.status)

        parked.addObject(CalibrationRequested("cal-2"))
        val rearmed = parked.run()

        assertEquals(AgentProcessStatusCode.STUCK, rearmed.status, "The second episode completes and parks")
        val steps = rearmed.objects.filterIsInstance<ExecutedStep>().map { it.name }
        assertEquals(listOf("calibrate:cal-1", "calibrate:cal-2"), steps, "Each occurrence ran exactly once")
        assertNull(rearmed.last<CalibrationRequested>(), "cal-2 was consumed by a real completion")
    }

    @Test
    fun `a goal whose satisfying output is standing state is rejected`() {
        // The output survives consumption as standing state, so the goal could
        // never become unsatisfied: completion would loop on an empty plan
        val exception = assertThrows<IllegalArgumentException> {
            create(
                SelfMaintainedOutputAgent(),
                "phase1-self-maintained-output",
                ProcessOptions.DEFAULT.withEpisodes(
                    EpisodePolicy
                        .episode(GoalTarget.output(RunningTally::class.java))
                        .consumeOnCompletion(CalibrationRequested::class.java)
                ),
                RunningTally(0),
            )
        }
        assertTrue("RunningTally" in exception.message!!, "Error names the standing type: ${exception.message}")
    }

    @Test
    fun `a candidate goal sharing its name with another scoped goal is rejected`() {
        val agent = CoreAgent(
            name = "shared-name-agent",
            provider = "test",
            description = "candidate and ordinary goal share a name",
            actions = emptyList(),
            goals = setOf(
                Goal(
                    name = "sharedGoal",
                    description = "episode candidate",
                    satisfiedBy = CalibrationCompleted::class.java,
                ),
                Goal(
                    name = "sharedGoal",
                    description = "ordinary goal outside the target",
                    satisfiedBy = MissionReport::class.java,
                ),
            ),
        )
        val exception = assertThrows<IllegalArgumentException> {
            SimpleAgentProcess(
                "phase1-shared-goal-name",
                null,
                agent,
                ProcessOptions.DEFAULT.withEpisodes(
                    EpisodePolicy
                        .episode(GoalTarget.output(CalibrationCompleted::class.java))
                        .consumeOnCompletion(CalibrationRequested::class.java)
                ),
                InMemoryBlackboard(),
                dummyPlatformServices(),
                DefaultPlannerFactory,
                Instant.now(),
            )
        }
        assertTrue(
            "shares its name" in exception.message!!,
            "The name collision is what fired, not a later rule: ${exception.message}",
        )
    }

    @Test
    fun `consumeOnCompletion without an episode fails fast`() {
        val exception = assertThrows<IllegalArgumentException> {
            EpisodePolicy.NONE.consumeOnCompletion(CalibrationRequested::class.java)
        }
        assertTrue("episode" in exception.message!!)
    }

    @Test
    fun `consumeOnCompletion twice on one episode fails fast`() {
        val exception = assertThrows<IllegalArgumentException> {
            EpisodePolicy
                .episode(GoalTarget.output(CalibrationCompleted::class.java))
                .consumeOnCompletion(CalibrationRequested::class.java)
                .consumeOnCompletion(ZoneInfo::class.java)
        }
        assertTrue("already" in exception.message!!, "The overwrite is rejected, not silent: ${exception.message}")
    }

    @Test
    fun `a blank named target fails at construction`() {
        val exception = assertThrows<IllegalArgumentException> {
            GoalTarget.named("  ")
        }
        assertTrue("name" in exception.message!!)
    }

    @Test
    fun `a candidate goal without a JVM output type is rejected`() {
        // Nothing could ever be consumed for this goal, so a completed episode
        // would stay satisfied and loop
        val agent = CoreAgent(
            name = "no-output-agent",
            provider = "test",
            description = "goal with no output type",
            actions = emptyList(),
            goals = setOf(
                Goal(
                    name = "noOutputGoal",
                    description = "condition-gated only",
                ),
            ),
        )
        val exception = assertThrows<IllegalArgumentException> {
            SimpleAgentProcess(
                "phase1-no-output-type",
                null,
                agent,
                ProcessOptions.DEFAULT.withEpisodes(
                    EpisodePolicy
                        .episode(GoalTarget.named("noOutputGoal"))
                        .consumeOnCompletion(CalibrationRequested::class.java)
                ),
                InMemoryBlackboard(),
                dummyPlatformServices(),
                DefaultPlannerFactory,
                Instant.now(),
            )
        }
        assertTrue("noOutputGoal" in exception.message!!, "Error names the goal: ${exception.message}")
    }

    @Test
    fun `a seeded output does not pre-satisfy a reader-built episode goal`() {
        // Reader-built goals include hasRun of the achieving action in their
        // preconditions, so a seeded output cannot complete the episode without
        // real work. The episode runs, consumes its own fresh output by
        // identity, and the stale decoy merely lingers
        val process = create(
            GoapEpisodeOnlyAgent(),
            "phase1-pre-satisfied",
            ProcessOptions.DEFAULT.withEpisodes(calibrationEpisode()),
            CalibrationCompleted("stale"),
            CalibrationRequested("cal-1"),
        )

        val result = process.run()

        assertEquals(AgentProcessStatusCode.STUCK, result.status)
        val steps = result.objects.filterIsInstance<ExecutedStep>().map { it.name }
        assertEquals(listOf("calibrate:cal-1"), steps, "Real work ran despite the seeded output")
        assertNull(result.last<CalibrationRequested>(), "The request was consumed by a real completion")
        assertNull(result.last<CalibrationCompleted>(), "Every visible instance of a product type is consumed")
    }

    @Test
    fun `a self-refining producer makes the request optional and the episode is rejected`() {
        // refineKit can sustain the kit without a fresh request, so the request
        // is not required on every completion path; the runaway this would
        // allow at completion is unconfigurable
        val exception = assertThrows<IllegalArgumentException> {
            create(
                RefiningChainAgent(),
                "phase1-self-refining",
                ProcessOptions.DEFAULT.withEpisodes(calibrationEpisode()),
            )
        }
        assertTrue("every completion path" in exception.message!!, exception.message!!)
    }

    @Test
    fun `a candidate goal with a dynamic output type is rejected`() {
        // Instances of a dynamic type can never be hidden, so a completed
        // episode would stay satisfied and spin
        val agent = CoreAgent(
            name = "dynamic-output-agent",
            provider = "test",
            description = "goal with dynamic output type",
            actions = emptyList(),
            goals = setOf(
                Goal(
                    name = "dynamicGoal",
                    description = "dynamic output",
                    inputs = setOf(IoBinding(type = CalibrationRequested::class.java)),
                    outputType = DynamicType("sensor.Reading"),
                ),
            ),
        )
        val exception = assertThrows<IllegalArgumentException> {
            SimpleAgentProcess(
                "phase1-dynamic-output",
                null,
                agent,
                ProcessOptions.DEFAULT.withEpisodes(
                    EpisodePolicy
                        .episode(GoalTarget.named("dynamicGoal"))
                        .consumeOnCompletion(CalibrationRequested::class.java)
                ),
                InMemoryBlackboard(),
                dummyPlatformServices(),
                DefaultPlannerFactory,
                Instant.now(),
            )
        }
        assertTrue("dynamicGoal" in exception.message!!, "Error names the goal: ${exception.message}")
    }

    @Test
    fun `standing state maintained by a two-action cycle survives consumption`() {
        val result = run(
            PingPongAccumulatorAgent(),
            "phase1-ping-pong",
            ProcessOptions.DEFAULT
                .withPlannerType(PlannerType.HYBRID)
                .withEpisodes(calibrationEpisode()),
            PingTally(0),
            PongTally(0),
        )

        val allSteps = result.objects.filterIsInstance<ExecutedStep>().map { it.name }
        assertEquals(
            AgentProcessStatusCode.COMPLETED, result.status,
            "steps=${allSteps.take(20)} total=${allSteps.size} tally=${result.last<PingTally>()} pong=${result.last<PongTally>()}",
        )
        assertEquals(3, result.last<MissionReport>()?.samples)
        val pings = result.objects.filterIsInstance<ExecutedStep>().count { it.name == "ping" }
        // If consumption swept the ping-pong tallies as products, the cycle
        // would roll back after the episode and need extra pings to recover
        assertEquals(3, pings, "The two-action accumulator must not roll back at episode completion")
    }

    @Test
    fun `every visible instance of a product type is consumed at completion`() {
        val process = create(
            RepeatableTwoStepAgent(),
            "phase1-exhaustive-products",
            ProcessOptions.DEFAULT.withEpisodes(calibrationEpisode()),
            CalibrationRequested("cal-1"),
            CalibrationKit("stale-A"),
            CalibrationKit("stale-B"),
        )

        val parked = process.run()
        assertEquals(AgentProcessStatusCode.STUCK, parked.status)
        assertNull(parked.last<CalibrationKit>(), "No stale duplicate survives to shortcut the next plan")

        parked.addObject(CalibrationRequested("cal-2"))
        val rearmed = parked.run()

        assertEquals(AgentProcessStatusCode.STUCK, rearmed.status)
        val steps = rearmed.objects.filterIsInstance<ExecutedStep>().map { it.name }
        assertEquals(
            listOf("calibrate:stale-B", "prepKit:cal-2", "calibrate:cal-2"), steps,
            "The second occurrence rebuilds the chain instead of reusing the surviving stale kit",
        )
    }

    @Test
    fun `two independent episodes in one process each complete and consume`() {
        val result = run(
            TwoEpisodeAgent(),
            "phase1-two-episodes",
            ProcessOptions.DEFAULT.withEpisodes(
                EpisodePolicy
                    .episode(GoalTarget.output(CalibrationCompleted::class.java))
                    .consumeOnCompletion(CalibrationRequested::class.java)
                    .episode(GoalTarget.output(CalibrationArchived::class.java))
                    .consumeOnCompletion(ZoneInfo::class.java)
            ),
            CalibrationRequested("cal-1"),
            ZoneInfo("zone-1"),
        )

        assertEquals(AgentProcessStatusCode.STUCK, result.status)
        val steps = result.objects.filterIsInstance<ExecutedStep>().map { it.name }
        assertEquals(listOf("calibrate:cal-1", "audit:zone-1"), steps)
        assertNull(result.last<CalibrationRequested>())
        assertNull(result.last<ZoneInfo>())
        assertNull(result.last<CalibrationCompleted>())
        assertNull(result.last<CalibrationArchived>())
    }

    @Test
    fun `episode completion emits GoalAchievedEvent but never a process-finished event`() {
        val events = mutableListOf<Any>()
        val listener = object : AgenticEventListener {
            override fun onProcessEvent(event: AgentProcessEvent) {
                events.add(event)
            }
        }
        // withListener must reach process events: ProcessContext composes
        // processOptions.listeners, and the process publishes through it
        val result = run(
            GoapEpisodeOnlyAgent(),
            "phase1-episode-events",
            ProcessOptions.DEFAULT
                .withEpisodes(calibrationEpisode())
                .withListener(listener),
            CalibrationRequested("cal-1"),
        )

        assertEquals(AgentProcessStatusCode.STUCK, result.status)
        val goalEvents = events.filterIsInstance<GoalAchievedEvent>()
        val finished = events.count { it is AgentProcessFinishedEvent }
        assertEquals(1, goalEvents.size, "One episode, one goal event")
        assertTrue(
            goalEvents.single() is EpisodeCompletedEvent,
            "Episode completions are distinguishable by type, matching the platform's event idiom",
        )
        assertEquals(0, finished, "A nonterminal completion must not emit a process-finished event")
    }

    @Test
    fun `EpisodeCompletedEvent is observed after consumption`() {
        // The event contract says consumption occurred and the process
        // continues, so a listener reading the blackboard at event time
        // must see the consumed state
        var requestVisibleAtEvent: Boolean? = null
        val listener = object : AgenticEventListener {
            override fun onProcessEvent(event: AgentProcessEvent) {
                if (event is EpisodeCompletedEvent) {
                    requestVisibleAtEvent =
                        event.agentProcess.last(CalibrationRequested::class.java) != null
                }
            }
        }
        val result = run(
            GoapEpisodeOnlyAgent(),
            "phase1-event-ordering",
            ProcessOptions.DEFAULT
                .withEpisodes(calibrationEpisode())
                .withListener(listener),
            CalibrationRequested("cal-1"),
        )

        assertEquals(AgentProcessStatusCode.STUCK, result.status)
        assertEquals(false, requestVisibleAtEvent, "Consumption must precede the event that announces it")
    }

    @Test
    fun `terminal completion emits both goal and finished events`() {
        val events = mutableListOf<Any>()
        val listener = object : AgenticEventListener {
            override fun onProcessEvent(event: AgentProcessEvent) {
                events.add(event)
            }
        }
        val result = run(
            EpisodeLifecycleAgent(),
            "phase1-terminal-events",
            ProcessOptions.DEFAULT
                .withPlannerType(PlannerType.HYBRID)
                .withEpisodes(calibrationEpisode())
                .withListener(listener),
            SampleTally(0),
        )

        assertEquals(AgentProcessStatusCode.COMPLETED, result.status)
        val goalEvents = events.filterIsInstance<GoalAchievedEvent>()
        assertEquals(2, goalEvents.count { it is EpisodeCompletedEvent }, "Two episodes completed")
        assertEquals(1, goalEvents.count { it !is EpisodeCompletedEvent }, "One terminal achievement")
        assertTrue(
            events.any { it is AgentProcessFinishedEvent },
            "Terminal completion emits a process-finished event",
        )
    }

    @Test
    fun `a failing completing action leaves the request unconsumed for retry`() {
        val agent = FlakyCalibrationAgent()
        val process = create(
            agent,
            "phase1-flaky-action",
            ProcessOptions.DEFAULT.withEpisodes(calibrationEpisode()),
            CalibrationRequested("cal-1"),
        )

        runCatching { process.run() }
        assertNotNull(process.last<CalibrationRequested>(), "A failed attempt must not consume the request")

        val retried = process.run()

        assertEquals(AgentProcessStatusCode.STUCK, retried.status)
        assertEquals(2, agent.attempts, "The second attempt succeeded")
        assertNull(retried.last<CalibrationRequested>(), "The successful completion consumed the request")
    }

    @Test
    fun `a completing action that publishes the next request livelocks`() {
        // Documented phase-1 limitation: consumption takes the latest visible
        // occurrence, and a request published by the completing action itself
        // is newer than the driver. The follow-up is consumed in the driver's
        // place, the driver re-runs and republishes, and only the action
        // budget ends the loop. Publish follow-up requests from an action that
        // does not complete the episode; phase-2 ingress does not have this
        // shape because external publications are not steps of the plan.
        val process = create(
            SelfRearmingAgent(),
            "phase1-self-rearming",
            ProcessOptions.DEFAULT.withEpisodes(calibrationEpisode()),
            CalibrationRequested("cal-1"),
        )

        val result = process.run()

        assertEquals(AgentProcessStatusCode.TERMINATED, result.status, "The action budget ends the loop")
        val steps = result.objects.filterIsInstance<ExecutedStep>().map { it.name }
        assertTrue(
            steps.size > 2 && steps.all { it == "calibrate:cal-1" },
            "The driver repeats while its follow-ups are consumed in its place: took ${steps.size} steps",
        )
    }

    @Test
    fun `concurrent process shares the episode contract - park and rearm`() {
        val blackboard = InMemoryBlackboard()
        blackboard.addObject(CalibrationRequested("cal-1"))
        val reader = AgentMetadataReader()
        val agent = reader.createAgentMetadata(GoapEpisodeOnlyAgent()) as CoreAgent
        val process = ConcurrentAgentProcess(
            "phase1-concurrent-episode",
            null,
            agent,
            ProcessOptions.DEFAULT.withEpisodes(calibrationEpisode()),
            blackboard,
            dummyPlatformServices(),
            DefaultPlannerFactory,
            Instant.now(),
        )

        val parked = process.run()
        assertEquals(AgentProcessStatusCode.STUCK, parked.status)
        assertNull(parked.last<CalibrationRequested>())

        parked.addObject(CalibrationRequested("cal-2"))
        val rearmed = parked.run()

        assertEquals(AgentProcessStatusCode.STUCK, rearmed.status)
        val calibrated = rearmed.objects.filterIsInstance<ExecutedStep>().map { it.name }
        assertEquals(listOf("calibrate:cal-1", "calibrate:cal-2"), calibrated)
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
