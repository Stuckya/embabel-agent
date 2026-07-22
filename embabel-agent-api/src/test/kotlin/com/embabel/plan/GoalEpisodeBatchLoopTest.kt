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
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertNull

data class BatchRequested(val id: Int)
data class BatchCollected(val id: Int)
data class HazardDetected(val id: String)
data class HazardAssessed(val id: String)
data class HazardCleared(val id: String)
data class BatchMissionDone(val samples: Int)

/**
 * The batch loop: collect 500 samples in batches of 50, each batch one
 * episode. RepeatUntil for a group of actions is emergent, not a construct:
 * the rule admits every new occurrence, each episode runs once and is
 * consumed, and the until is an ordinary condition-gated terminal goal.
 * AIMA 3e p. 423, directly: "the loop is created by a process of
 * plan-execute-replan, rather than by an explicit loop in a plan."
 *
 * What it pins, composed in one scenario:
 * - Self-chaining: the completing action publishes the next request. Each
 *   follow-up is queued at arrival and admitted after its predecessor's
 *   request is consumed, exactly once per occurrence.
 * - The tally is standing state, advanced by addObject rather than declared
 *   as an output, so it is never attributed and survives every completion.
 *   Episodes are independent, not idempotent: the mission accumulates.
 * - A hazard mid-shift runs its own episode between batches: admission is
 *   per rule, so the hazard never queues behind pending batches, and its
 *   two-step chain out-values batch work until cleared.
 * - Group repetition is the conjunction of per-action canRerun on the
 *   chain; there is no group-level rerun declaration.
 * - The until is ordinary: the terminal goal completes the process when the
 *   tally reaches 500, ending ten episodes of batch work normally.
 */
class GoalEpisodeBatchLoopTest {

    @Agent(description = "Collects 500 samples in 50-sample batch episodes with hazard episodes interleaved")
    inner class BatchMissionAgent {

        /**
         * The batch chain is one action that drives itself: it publishes
         * the next request while its own episode is still active. The
         * follow-up queues and admits only after this occurrence completes.
         */
        @Action(canRerun = true, value = 0.5)
        @AchievesGoal(description = "Batch collected", value = 1.0)
        fun collectBatch(request: BatchRequested, tally: SampleTally, context: ActionContext): BatchCollected {
            context.addObject(ExecutedStep("batch:${request.id}"))
            val next = SampleTally(tally.count + 50)
            context.addObject(next)
            if (next.count == 100) {
                context.addObject(HazardDetected("spill-1"))
            }
            if (next.count < 500) {
                context.addObject(BatchRequested(request.id + 1))
            }
            return BatchCollected(request.id)
        }

        @Action(canRerun = true, value = 0.7)
        fun assessHazard(hazard: HazardDetected, context: ActionContext): HazardAssessed {
            context.addObject(ExecutedStep("assess:${hazard.id}"))
            return HazardAssessed(hazard.id)
        }

        @Action(canRerun = true, value = 0.8)
        @AchievesGoal(description = "Hazard cleared", value = 1.0)
        fun clearHazard(assessed: HazardAssessed, context: ActionContext): HazardCleared {
            context.addObject(ExecutedStep("clear:${assessed.id}"))
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
        blackboard.addObject(BatchRequested(1))
        blackboard.addObject(SampleTally(0))
        val reader = AgentMetadataReader()
        val agent = reader.createAgentMetadata(BatchMissionAgent()) as CoreAgent
        val process = SimpleAgentProcess(
            "episode-batch-loop",
            null,
            agent.copy(goals = agent.goals + NIRVANA),
            ProcessOptions.DEFAULT
                .withPlannerType(PlannerType.HYBRID)
                .withEpisodes(
                    EpisodePolicy
                        .episode(GoalTarget.output(BatchCollected::class.java))
                        .consumeOnCompletion(BatchRequested::class.java)
                        // Bare rule: HazardDetected is the hazard chain's only
                        // off-chain input, so consumption is inferred
                        .episode(GoalTarget.output(HazardCleared::class.java))
                ),
            blackboard,
            dummyPlatformServices(),
            DefaultPlannerFactory,
            Instant.now(),
        )

        val result = process.run()

        assertEquals(AgentProcessStatusCode.COMPLETED, result.status, "The terminal goal ends the process normally")
        assertEquals(500, result.last<BatchMissionDone>()?.samples, "The until was an ordinary terminal goal")
        assertEquals(500, result.last<SampleTally>()?.count, "Standing state accumulated across every episode")

        val steps = result.objects.filterIsInstance<ExecutedStep>().map { it.name }
        val expected = listOf("batch:1", "batch:2", "assess:spill-1", "clear:spill-1") +
                (3..10).map { "batch:$it" }
        assertEquals(
            expected, steps,
            "Ten batches ran exactly once each; the hazard episode slotted between batches 2 and 3",
        )

        assertNull(result.last<BatchRequested>(), "Every batch request was consumed by its own episode")
        assertNull(result.last<BatchCollected>(), "Every batch output was consumed as its episode's consumable")
        assertNull(result.last<HazardDetected>(), "The inferred hazard request was consumed")
        assertNull(result.last<HazardAssessed>(), "The hazard chain's intermediate was consumed")
        assertNull(result.last<HazardCleared>(), "The hazard output was consumed")
    }
}
