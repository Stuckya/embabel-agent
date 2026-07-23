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
import com.embabel.agent.core.AgentProcess
import com.embabel.agent.core.AgentProcessStatusCode
import com.embabel.agent.core.ProcessOptions
import com.embabel.agent.core.hitl.AbstractAwaitable
import com.embabel.agent.core.hitl.AwaitableResponse
import com.embabel.agent.core.hitl.ResponseImpact
import com.embabel.agent.core.hitl.waitFor
import com.embabel.agent.core.last
import com.embabel.agent.core.support.InMemoryBlackboard
import com.embabel.agent.core.support.NIRVANA
import com.embabel.agent.core.support.SimpleAgentProcess
import com.embabel.agent.spi.support.DefaultPlannerFactory
import com.embabel.agent.test.integration.IntegrationTestUtils.dummyPlatformServices
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

data class SampleTally(val count: Int)
data class MissionReport(val samples: Int)
data class CalibrationRequested(val id: String)
data class CanRequested(val id: String)
data class CanFetched(val id: String)
data class BuildRequested(val id: String)
data class BuildDone(val id: String)
data class PingState(val id: String)
data class PongState(val id: String)
data class SurveyLicense(val id: String)
interface JobRequest { val id: String }
data class FooRequest(override val id: String) : JobRequest
data class BarRequest(override val id: String) : JobRequest
data class JobHandled(val id: String)
data class SurveyDraft(val id: String)
data class SurveyFiled(val id: String)
data class CalibrationCompleted(val id: String)
data class CalibrationArchived(val id: String)
data class CalibrationKit(val id: String)
data class ExecutedStep(val name: String)

data class CalibrationRequestResponse(
    override val id: String = UUID.randomUUID().toString(),
    override val awaitableId: String,
    val calId: String,
    override val timestamp: Instant = Instant.now(),
) : AwaitableResponse {
    override fun persistent(): Boolean = false
}

class CalibrationRequestAwaitable : AbstractAwaitable<CalibrationRequested, CalibrationRequestResponse>(
    CalibrationRequested("placeholder")
) {
    override fun onResponse(
        response: CalibrationRequestResponse,
        agentProcess: AgentProcess,
    ): ResponseImpact {
        agentProcess.addObject(CalibrationRequested(response.calId))
        return ResponseImpact.UPDATED
    }
}

/**
 * Explores baseline goal-episode behavior on main, discussed in issue #1756,
 * under both the HYBRID and pure GOAP planners:
 * 1. HYBRID: a goal gated by a @Condition stops the process once the condition turns true
 *    (the goal action must out-value standing work).
 * 2. HYBRID: a fact appearing mid-run activates goal pursuit out of the box —
 *    and the first completed side episode ends the process.
 * 3. GOAP: a fact-gated goal with no declared path is STUCK immediately; the process cannot wait.
 * 4. GOAP: with a declared standing path, a mid-run side episode AND the mission can both
 *    complete — the process ends only when the best-value plan is empty (value arithmetic).
 * 5. GOAP: a goal never rearms — a second occurrence of the same fact type is ignored.
 * 6. GOAP: with the request as a declared action output, A* manufactures the occurrence
 *    on demand and completes — the contrast that shows why occurrence facts are added
 *    off the type chain in the other tests.
 * 7. HYBRID: consume/rearm can be hand-rolled today — the action hides its trigger, a
 *    janitor action hides the satisfying output, and a second occurrence runs the
 *    episode. Correctness rides on value tuning and two hide calls in the right places.
 * 8. GOAP: a STUCK process resumes via addObject + run() — the manual wake loop works
 *    today; what's missing is the platform owning it.
 * 9. GOAP: an awaiting action (waitFor promising the occurrence type) parks the process
 *    WAITING instead of STUCK, and onResponse + run() resumes it into the goal —
 *    solicited waiting exists today; the resume is still driver-owned.
 * 10. GOAP: a two-step episode is not an atomic block — per-tick replanning lets
 *     standing work interleave between the episode's steps when values favor it.
 */
class GoalEpisodeBaselineTest {

    @Agent(description = "Standing collection with a condition-gated stop goal")
    inner class CollectorAgent {

        @Action(canRerun = true, value = 0.2)
        fun collect(tally: SampleTally): SampleTally = SampleTally(tally.count + 1)

        @Condition(name = "enoughSamples")
        fun enoughSamples(tally: SampleTally): Boolean = tally.count >= 3

        // The goal action must out-value the standing action: HybridUtilityPlanner's
        // single-step lookahead only tests the highest-netValue available action
        @Action(pre = ["enoughSamples"], value = 0.9)
        @AchievesGoal(description = "Mission complete", value = 1.0)
        fun missionComplete(tally: SampleTally): MissionReport = MissionReport(tally.count)
    }

    @Agent(description = "Standing collection; a mid-run fact activates a calibration goal")
    inner class MidRunFactAgent {

        @Action(canRerun = true, value = 0.2)
        fun collect(tally: SampleTally, context: ActionContext): SampleTally {
            val next = SampleTally(tally.count + 1)
            if (next.count == 2) {
                // The fact arrives mid-run, as an action side effect
                context.addObject(CalibrationRequested("cal-1"))
            }
            return next
        }

        @Action(value = 0.9)
        @AchievesGoal(description = "Calibration completed", value = 1.0)
        fun calibrate(request: CalibrationRequested): CalibrationCompleted =
            CalibrationCompleted(request.id)
    }

    @Agent(description = "Pure GOAP with only a fact-gated goal and an off-path collect action")
    inner class GoapEventOnlyAgent {

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

    @Agent(description = "Pure GOAP standing mission path plus a mid-run fact-gated goal")
    inner class GoapMissionAgent {

        // post declares the path so A* can route collect -> missionComplete
        @Action(canRerun = true, value = 0.2, post = ["enoughSamples"])
        fun collect(tally: SampleTally, context: ActionContext): SampleTally {
            val next = SampleTally(tally.count + 1)
            if (next.count == 2) {
                context.addObject(CalibrationRequested("cal-1"))
            }
            return next
        }

        @Condition(name = "enoughSamples")
        fun enoughSamples(tally: SampleTally): Boolean = tally.count >= 3

        @Action(pre = ["enoughSamples"], value = 0.9)
        @AchievesGoal(description = "Mission complete", value = 0.5)
        fun missionComplete(tally: SampleTally): MissionReport = MissionReport(tally.count)

        @Action(value = 0.9)
        @AchievesGoal(description = "Calibration completed", value = 1.0)
        fun calibrate(request: CalibrationRequested): CalibrationCompleted =
            CalibrationCompleted(request.id)
    }

    private fun run(
        instance: Any,
        processId: String,
        plannerType: PlannerType,
    ): com.embabel.agent.core.AgentProcess {
        val blackboard = InMemoryBlackboard()
        blackboard.addObject(SampleTally(0))

        val reader = AgentMetadataReader()
        val agent = reader.createAgentMetadata(instance) as com.embabel.agent.core.Agent
        // HYBRID pairs the real goal(s) with the unsatisfiable NIRVANA goal,
        // which drives value-based standing work. Pure GOAP has no NIRVANA.
        val effectiveAgent = if (plannerType == PlannerType.HYBRID) {
            agent.copy(goals = agent.goals + NIRVANA)
        } else {
            agent
        }

        val agentProcess = SimpleAgentProcess(
            processId,
            null,
            effectiveAgent,
            ProcessOptions.DEFAULT.withPlannerType(plannerType),
            blackboard,
            dummyPlatformServices(),
            DefaultPlannerFactory,
            Instant.now(),
        )
        return agentProcess.run()
    }

    private fun runHybrid(instance: Any, processId: String) =
        run(instance, processId, PlannerType.HYBRID)

    @Test
    fun `condition gated goal stops HYBRID process after standing work`() {
        val result = runHybrid(CollectorAgent(), "hybrid-condition-gated-stop")

        assertEquals(AgentProcessStatusCode.COMPLETED, result.status)
        val tally = result.last<SampleTally>()
        assertNotNull(tally)
        assertEquals(3, tally.count, "Should have collected until enoughSamples turned true")
        assertNotNull(result.last<MissionReport>(), "Goal action should have run")
    }

    @Test
    fun `fact appearing mid-run activates goal pursuit out of the box but first completion ends the process`() {
        val result = runHybrid(MidRunFactAgent(), "hybrid-mid-run-fact")

        // Igor's point: the new fact activates goal pursuit with no extra machinery
        assertEquals(AgentProcessStatusCode.COMPLETED, result.status)
        assertNotNull(result.last<CalibrationCompleted>(), "Calibration goal should have been pursued and achieved")

        // The gap: the first completed episode ends the process. Standing collection
        // stopped at 2 samples, though nothing said the mission was over.
        val tally = result.last<SampleTally>()
        assertNotNull(tally)
        assertEquals(2, tally.count, "Standing work stopped when the side goal completed")
    }

    @Test
    fun `pure GOAP cannot wait - a fact gated goal with no declared path is stuck immediately`() {
        val result = run(GoapEventOnlyAgent(), "goap-event-only", PlannerType.GOAP)

        assertEquals(AgentProcessStatusCode.STUCK, result.status)
        val tally = result.last<SampleTally>()
        assertNotNull(tally)
        assertEquals(0, tally.count, "Off-path collect never ran, so the activating fact never appeared")
    }

    @Test
    fun `pure GOAP standing path with mid-run fact - both episodes complete via value arithmetic`() {
        val result = run(GoapMissionAgent(), "goap-mission", PlannerType.GOAP)

        // Both episodes completed: the mission plan's netValue (goal 0.5 + action values)
        // kept beating the satisfied calibration goal's empty plan (1.0), so the process
        // survived the side episode. Completion timing is emergent value arithmetic:
        // the process ends only when the best-value plan is an empty one.
        assertEquals(AgentProcessStatusCode.COMPLETED, result.status)
        assertEquals("cal-1", result.last<CalibrationCompleted>()?.id)
        assertEquals(3, result.last<SampleTally>()?.count)
        assertEquals(3, result.last<MissionReport>()?.samples)
    }

    @Agent(description = "Pure GOAP mission with recurring calibration requests")
    inner class GoapRearmAgent {

        @Action(canRerun = true, value = 0.2, post = ["enoughSamples"])
        fun collect(tally: SampleTally, context: ActionContext): SampleTally {
            val next = SampleTally(tally.count + 1)
            if (next.count == 2) {
                context.addObject(CalibrationRequested("cal-1"))
            }
            if (next.count == 4) {
                // A second occurrence of the same fact type, later in the run
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
        fun calibrate(request: CalibrationRequested): CalibrationCompleted =
            CalibrationCompleted(request.id)
    }

    @Agent(description = "Pure GOAP where the calibration request is on the type chain")
    inner class GoapOnChainAgent {

        // The request is a declared action output: A* can now plan through it
        @Action(value = 0.2)
        fun raiseCalibrationRequest(tally: SampleTally): CalibrationRequested =
            CalibrationRequested("cal-chained")

        @Action(value = 0.9)
        @AchievesGoal(description = "Calibration completed", value = 1.0)
        fun calibrate(request: CalibrationRequested): CalibrationCompleted =
            CalibrationCompleted(request.id)
    }

    @Test
    fun `pure GOAP with the request on the type chain - the planner manufactures the occurrence on demand`() {
        val result = run(GoapOnChainAgent(), "goap-on-chain", PlannerType.GOAP)

        // Contrast with the STUCK test: same goal, but because the request is a
        // declared output, A* treats it as producible on demand and routes through
        // it. This is why occurrence facts are added off-chain in the other tests:
        // an observation is not something the planner should be able to manufacture.
        assertEquals(AgentProcessStatusCode.COMPLETED, result.status)
        assertEquals("cal-chained", result.last<CalibrationCompleted>()?.id)
    }

    @Test
    fun `pure GOAP goal never rearms - a second occurrence of the same fact is ignored`() {
        val result = run(GoapRearmAgent(), "goap-rearm", PlannerType.GOAP)

        assertEquals(AgentProcessStatusCode.COMPLETED, result.status)
        assertEquals(5, result.last<SampleTally>()?.count)
        // cal-2 arrived at count 4, but the calibration goal was already satisfied by
        // cal-1's episode, so it plans no work: the goal fires at most once per process,
        // even with canRerun = true on the calibrate action
        assertEquals("cal-2", result.last<CalibrationRequested>()?.id, "Second request is on the BB")
        assertEquals("cal-1", result.last<CalibrationCompleted>()?.id, "But only the first was ever handled")
    }

    @Agent(description = "HYBRID with hand-rolled consume/rearm via hide and a janitor action")
    inner class HandRolledLifecycleAgent {

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

        // Consume half 1: the action hides its own triggering input
        @Action(canRerun = true, value = 0.9)
        @AchievesGoal(description = "Calibration completed", value = 1.0)
        fun calibrate(request: CalibrationRequested, context: ActionContext): CalibrationCompleted {
            context.hide(request)
            return CalibrationCompleted(request.id)
        }

        // Consume half 2: a janitor action hides the satisfying output after the
        // goal is achieved. Its value must win the tick or the stale output
        // satisfies the next occurrence without work.
        @Action(canRerun = true, value = 1.2)
        fun archiveCalibration(done: CalibrationCompleted, context: ActionContext): CalibrationArchived {
            context.hide(done)
            return CalibrationArchived(done.id)
        }
    }

    @Test
    fun `hand-rolled consume and rearm under HYBRID - a second occurrence is handled when facts are hidden`() {
        val result = run(HandRolledLifecycleAgent(), "hybrid-hand-rolled-lifecycle", PlannerType.HYBRID)

        assertEquals(AgentProcessStatusCode.COMPLETED, result.status)
        assertEquals(5, result.last<SampleTally>()?.count, "Mission ran to its terminal goal")
        val archived = result.objects.filterIsInstance<CalibrationArchived>().map { it.id }
        assertEquals(listOf("cal-1", "cal-2"), archived, "Both occurrences ran the episode")
    }

    @Test
    fun `a STUCK process resumes when a fact arrives and run is called again`() {
        val stuck = run(GoapEventOnlyAgent(), "goap-stuck-resume", PlannerType.GOAP)
        assertEquals(AgentProcessStatusCode.STUCK, stuck.status)

        stuck.addObject(CalibrationRequested("cal-late"))
        val resumed = stuck.run()

        assertEquals(AgentProcessStatusCode.COMPLETED, resumed.status)
        assertEquals("cal-late", resumed.last<CalibrationCompleted>()?.id, "Manual wake: addObject + run() works today")
    }

    @Agent(description = "Pure GOAP where a two-step episode interleaves with standing work")
    inner class GoapInterleaveAgent {

        // Values arranged so the mission plan outranks the calibration
        // remainder mid-episode: prep runs, then collects, then calibrate
        @Action(canRerun = true, value = 0.2, post = ["enoughSamples"])
        fun collect(tally: SampleTally, context: ActionContext): SampleTally {
            context.share(ExecutedStep("collect"))
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
        fun missionComplete(tally: SampleTally, context: ActionContext): MissionReport {
            context.share(ExecutedStep("missionComplete"))
            return MissionReport(tally.count)
        }

        @Action(value = 0.9)
        fun prepKit(request: CalibrationRequested, context: ActionContext): CalibrationKit {
            context.share(ExecutedStep("prepKit"))
            return CalibrationKit(request.id)
        }

        @Action(value = 0.55)
        @AchievesGoal(description = "Calibration completed", value = 1.0)
        fun calibrate(kit: CalibrationKit, context: ActionContext): CalibrationCompleted {
            context.share(ExecutedStep("calibrate"))
            return CalibrationCompleted(kit.id)
        }
    }

    @Test
    fun `episode steps are not an atomic block - standing work interleaves between them`() {
        val result = run(GoapInterleaveAgent(), "goap-interleave", PlannerType.GOAP)

        assertEquals(AgentProcessStatusCode.COMPLETED, result.status)
        val steps = result.objects.filterIsInstance<ExecutedStep>().map { it.name }
        val prepAt = steps.indexOf("prepKit")
        val calibrateAt = steps.indexOf("calibrate")
        val collectsBetween = steps.subList(prepAt + 1, calibrateAt).count { it == "collect" }
        assertEquals(
            true, collectsBetween > 0,
            "Expected standing collects between the episode's two steps, got: $steps"
        )
    }

    @Agent(description = "Pure GOAP that awaits a calibration request via waitFor")
    inner class GoapAwaitingAgent {

        // The promise pattern: declares it produces the request so A* routes
        // through it, but execution parks WAITING instead of manufacturing
        @Action
        fun awaitCalibrationRequest(tally: SampleTally, context: ActionContext): CalibrationRequested =
            waitFor(CalibrationRequestAwaitable())

        @Action(value = 0.9)
        @AchievesGoal(description = "Calibration completed", value = 1.0)
        fun calibrate(request: CalibrationRequested): CalibrationCompleted =
            CalibrationCompleted(request.id)
    }

    @Test
    fun `pure GOAP with an awaiting action parks WAITING instead of STUCK and resumes on response`() {
        val waiting = run(GoapAwaitingAgent(), "goap-awaitable", PlannerType.GOAP)

        // Contrast with the STUCK test: same fact-gated goal, but an awaiting
        // action on the path turns "no plan" into solicited waiting
        assertEquals(AgentProcessStatusCode.WAITING, waiting.status)

        // Resume as the WaitForMvcIntegrationTest controller does: onResponse then run()
        val awaitable = waiting.last<CalibrationRequestAwaitable>()
        assertNotNull(awaitable, "Awaitable should be stored on the blackboard")
        awaitable.onResponse(
            CalibrationRequestResponse(awaitableId = awaitable.id, calId = "cal-async"),
            waiting,
        )
        val resumed = waiting.run()

        assertEquals(AgentProcessStatusCode.COMPLETED, resumed.status)
        assertEquals("cal-async", resumed.last<CalibrationCompleted>()?.id)
    }
}
