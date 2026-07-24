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
import kotlin.test.assertTrue
import kotlin.test.assertNull

data class BatchRequested(val id: Int)
data class BatchCollected(val id: Int)
data class HazardDetected(val id: String)
data class HazardAssessed(val id: String)
data class HazardCleared(val id: String)
data class BatchMissionDone(val samples: Int)

/**
 * The batch loop in the planner-session shape: collect 500 samples in
 * batches of 50, each request one episode. Repetition is emergent rather
 * than a separate runtime construct: each child publishes the next
 * occurrence, the selected planner routes it, and completion consumes it.
 * AIMA 3e
 * p. 423, directly: "the loop is created by a process of
 * plan-execute-replan, rather than by an explicit loop in a plan."
 *
 * What it pins, composed in one scenario:
 * - Self-chaining through evolve(): the completing action publishes the
 *   next occurrence at the evolve boundary. Each follow-up is queued at
 *   arrival and selected after its predecessor completes, exactly once per
 *   occurrence. Designation rides the API call: the tally is shared standing
 *   state and never becomes an occurrence.
 * - The tally is advanced through explicit share, so it crosses the child
 *   boundary and survives every completion.
 *   Episodes are independent, not idempotent: the mission accumulates.
 * - A hazard mid-shift is a separate occurrence. Planner values decide
 *   whether it or a pending batch runs next.
 * - Group repetition has no runtime rule: the planner sees ordinary
 *   declared actions and newly accepted occurrences.
 * - This objective-free process parks after all occurrences complete;
 *   ordinary root work can still produce the final report.
 *
 * This file pins the in-process rung, the declared opt-out. The same
 * composed scenario runs on the default rung - framework-dispatched
 * children - in GoalEpisodeFrameworkDispatchTest. One contract, both
 * rungs, deliberately pinned twice.
 */
class GoalEpisodeBatchLoopTest {

    @Agent(description = "Collects 500 samples in 50-sample batch episodes with hazard episodes interleaved")
    inner class BatchMissionAgent {

        /**
         * The batch chain is one action that drives itself: it evolves the
         * next occurrence while its own episode is still active. The
         * follow-up queues and becomes visible at the next planning tick.
         */
        @Action(canRerun = true, value = 0.5)
        @AchievesGoal(description = "Batch collected", value = 1.0)
        fun collectBatch(request: BatchRequested, tally: SampleTally, context: ActionContext): BatchCollected {
            context.share(ExecutedStep("batch:${request.id}"))
            val next = SampleTally(tally.count + 50)
            context.share(next)
            if (next.count == 100) {
                evolve(context, HazardDetected("spill-1"))
            }
            if (next.count < 500) {
                evolve(context, BatchRequested(request.id + 1))
            }
            return BatchCollected(request.id)
        }

        private fun evolve(context: ActionContext, occurrence: Any) {
            context.evolve(occurrence)
        }

        @Action(canRerun = true, value = 0.7)
        fun assessHazard(hazard: HazardDetected, context: ActionContext): HazardAssessed {
            context.share(ExecutedStep("assess:${hazard.id}"))
            return HazardAssessed(hazard.id)
        }

        @Action(canRerun = true, value = 0.8)
        @AchievesGoal(description = "Hazard cleared", value = 1.0)
        fun clearHazard(assessed: HazardAssessed, context: ActionContext): HazardCleared {
            context.share(ExecutedStep("clear:${assessed.id}"))
            return HazardCleared(assessed.id)
        }

        @Condition(name = "missionDone")
        fun missionDone(tally: SampleTally): Boolean = tally.count >= 500

        @Action(pre = ["missionDone"], value = 0.9)
        @AchievesGoal(description = "Mission complete", value = 0.5)
        fun report(tally: SampleTally): BatchMissionDone = BatchMissionDone(tally.count)
    }

    @Test
    fun `ten batch episodes chain to 500 samples with a hazard episode slotting between batches`() {
        val blackboard = InMemoryBlackboard()
        blackboard.addObject(SampleTally(0))
        val reader = AgentMetadataReader()
        val agent = reader.createAgentMetadata(BatchMissionAgent()) as CoreAgent
        val process = SimpleAgentProcess(
            "episode-batch-loop",
            null,
            agent.copy(goals = agent.goals + NIRVANA),
            ProcessOptions.DEFAULT
                .withPlannerType(PlannerType.HYBRID)
                .withEvolving(),
            blackboard,
            dummyPlatformServices(),
            DefaultPlannerFactory,
            Instant.now(),
        )
        process.evolve(BatchRequested(1))

        val result = process.run()

        assertEquals(AgentProcessStatusCode.STUCK, result.status, "The objective-free process parks when work is done")
        assertEquals(500, result.last<BatchMissionDone>()?.samples)
        assertEquals(500, result.last<SampleTally>()?.count, "Standing state accumulated across every episode")

        val steps = result.objects.filterIsInstance<ExecutedStep>().map { it.name }
        // The exact slot is value arithmetic; the contract is the shape:
        // ten batches in order once each, and the hazard as its own atomic
        // episode slotted in after its evolve
        assertEquals(
            (1..10).map { "batch:$it" }, steps.filter { it.startsWith("batch") },
            "Ten batches ran exactly once each, in order",
        )
        assertEquals(
            steps.indexOf("assess:spill-1") + 1, steps.indexOf("clear:spill-1"),
            "The hazard chain ran whole, an atomic episode",
        )
        assertTrue(
            steps.indexOf("assess:spill-1") > steps.indexOf("batch:2"),
            "The hazard slotted in after the batch that surfaced it",
        )

        assertNull(result.last<BatchRequested>(), "Every batch request was consumed by its own episode")
        assertNull(result.last<BatchCollected>(), "Batch outputs remained child-local")
        assertNull(result.last<HazardDetected>(), "The evolved hazard request was consumed")
        assertNull(result.last<HazardAssessed>(), "The hazard intermediate remained child-local")
        assertNull(result.last<HazardCleared>(), "The hazard output remained child-local")

    }
}
