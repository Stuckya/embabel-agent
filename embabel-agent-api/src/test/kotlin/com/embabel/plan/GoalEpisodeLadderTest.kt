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
import kotlin.test.assertTrue

data class WeldTally(val count: Int)
data class DoorDown(val id: String)
data class GripperOn(val id: String)
data class DoorFixed(val id: String)
data class ShiftLog(val welds: Int)

/**
 * The episode ladder: AIMA 3e p. 45 observes that "many environments are
 * episodic at higher levels than the agent's individual actions" (a chess
 * tournament is episodic per game while play within a game is sequential).
 * Phase 1 applies the episodic idea at the goal level inside one process.
 * This spike applies it one level up: the episode's chain is a single parent
 * action that runs the real work in a child process via the platform's
 * existing createChildProcess.
 *
 * What it pins:
 * - The parent-level episode contract is indifferent to what the chain does.
 *   Derived rules, evolved admission, consumption, and rearm all work when
 *   the chain's one action delegates to a child process.
 * - Standing work still interleaves BETWEEN child-process episodes, because
 *   the parent planner arbitrates the dispatch like any other action.
 * - A child-process episode is atomic from the parent's view. Nothing
 *   interleaves with the child's steps. That is the documented tradeoff of
 *   moving a chain across the process boundary.
 * - The child sees a snapshot of the parent blackboard, hidden entries
 *   included, so a consumed request from an earlier episode is invisible to
 *   a later child. The child's intermediates never reach the parent.
 */
class GoalEpisodeLadderTest {

    @Agent(description = "Repair crew that fixes one fallen door")
    inner class RepairCrewAgent {

        @Action(value = 0.5)
        fun equipGripper(door: DoorDown, context: ActionContext): GripperOn {
            context.addObject(ExecutedStep("child-equip:${door.id}"))
            return GripperOn(door.id)
        }

        @Action(value = 0.9)
        @AchievesGoal(description = "Door fixed", value = 1.0)
        fun reattachDoor(gripper: GripperOn, context: ActionContext): DoorFixed {
            context.addObject(ExecutedStep("child-reattach:${gripper.id}"))
            return DoorFixed(gripper.id)
        }
    }

    @Agent(description = "Welder that dispatches door repairs to child processes")
    inner class WeldingParentAgent {

        val children = mutableListOf<AgentProcess>()

        /**
         * Discovered by this spike: the child inherits the parent's HYBRID
         * planner type and createChildProcess offers no options override, so
         * the child scope needs its own NIRVANA pairing to execute a
         * multi-step chain. Without it the child is STUCK at tick one.
         */
        private val repairCrew: CoreAgent by lazy {
            val agent = AgentMetadataReader().createAgentMetadata(RepairCrewAgent()) as CoreAgent
            agent.copy(goals = agent.goals + NIRVANA)
        }

        @Action(canRerun = true, value = 0.2)
        fun weld(tally: WeldTally, context: ActionContext): WeldTally {
            context.addObject(ExecutedStep("weld"))
            val next = WeldTally(tally.count + 1)
            if (next.count == 2) {
                (context.agentProcess as SimpleAgentProcess).evolve(DoorDown("door-7"))
            }
            if (next.count == 4) {
                (context.agentProcess as SimpleAgentProcess).evolve(DoorDown("door-8"))
            }
            return next
        }

        @Condition(name = "shiftDone")
        fun shiftDone(tally: WeldTally): Boolean = tally.count >= 6

        @Action(pre = ["shiftDone"], value = 0.9)
        @AchievesGoal(description = "Shift complete", value = 0.5)
        fun endShift(tally: WeldTally): ShiftLog = ShiftLog(tally.count)

        /**
         * The ladder step: from this planner's view the whole repair is one
         * action. The chain lives in a child process created through the
         * platform's own parent/child mechanism.
         */
        @Action(canRerun = true, value = 0.8)
        @AchievesGoal(description = "Door repair completed", value = 1.0)
        fun dispatchRepair(door: DoorDown, context: ActionContext): DoorFixed {
            context.addObject(ExecutedStep("dispatch:${door.id}"))
            val platform = context.processContext.platformServices.agentPlatform
            val child = platform.createChildProcess(repairCrew, context.agentProcess)
            children += child
            val completed = child.run()
            return completed.last<DoorFixed>()
                ?: error(
                    "Child repair for ${door.id} produced no DoorFixed: " +
                            "status=${completed.status}, objects=${completed.objects}"
                )
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

    @Test
    fun `the ladder - a child process is the episode body and the parent contract still holds`() {
        val parentAgent = WeldingParentAgent()
        val process = create(
            parentAgent,
            "episode-ladder",
            ProcessOptions.DEFAULT
                .withPlannerType(PlannerType.HYBRID)
                // Derived: the repair rule comes from the goal graph, each
                // evolved DoorDown is one occurrence, and the shift's
                // objective anchors completion at the parent level exactly
                // as it would for an in-process chain
                .withEvolving(GoalTarget.output(ShiftLog::class.java)),
            WeldTally(0),
        )

        val result = process.run()

        assertEquals(AgentProcessStatusCode.COMPLETED, result.status)
        assertEquals(6, result.last<ShiftLog>()?.welds, "The shift ran to its terminal goal")

        // Standing work interleaves BETWEEN child-process episodes: the
        // parent planner arbitrates the dispatch like any other action
        val parentSteps = result.objects.filterIsInstance<ExecutedStep>().map { it.name }
        assertEquals(
            listOf("weld", "weld", "dispatch:door-7", "weld", "weld", "dispatch:door-8", "weld", "weld"),
            parentSteps,
            "Welds continue between episodes; each repair is atomic from the parent's view",
        )

        // Isolation: the child's steps and intermediates never reach the parent
        assertTrue(parentSteps.none { it.startsWith("child-") }, "Child steps stay in the child")
        assertNull(result.last<GripperOn>(), "Child intermediates never touch the parent blackboard")

        // The parent-level episode contract held: both occurrences consumed
        assertNull(result.last<DoorDown>(), "Both requests consumed")
        assertNull(result.last<DoorFixed>(), "Both satisfying outputs consumed")

        // Each dispatch ran its own isolated child to completion with lineage
        assertEquals(2, parentAgent.children.size, "One child process per occurrence")
        parentAgent.children.forEach { child ->
            assertEquals(AgentProcessStatusCode.COMPLETED, child.status)
            assertEquals(result.id, child.parentId, "The platform recorded the parent lineage")
        }
        val childSteps = parentAgent.children.map { child ->
            child.objects.filterIsInstance<ExecutedStep>().map { it.name }.filter { it.startsWith("child-") }
        }
        assertEquals(
            listOf(
                listOf("child-equip:door-7", "child-reattach:door-7"),
                listOf("child-equip:door-8", "child-reattach:door-8"),
            ),
            childSteps,
            "Each door ran its own two-step chain in its own process; " +
                    "the second child's snapshot excluded the first consumed request",
        )
    }
}
