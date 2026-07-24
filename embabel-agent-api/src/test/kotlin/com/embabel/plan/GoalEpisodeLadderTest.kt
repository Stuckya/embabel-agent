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
data class WaveRequested(val id: Int)
data class WaveDone(val id: Int)
data class StepRequested(val id: Int)
data class StepTally(val count: Int)
data class StepsDone(val steps: Int)
data class StepDone(val id: Int)
data class MissionTally(val count: Int)
data class WavesComplete(val waves: Int)

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
 * - Manual dispatch with declared options composes the tower: an evolving
 *   child running its own episode loop inside an evolving parent's
 *   episode. The evolving declaration reaches a child only by explicit
 *   dispatch-site declaration, never by inheritance.
 */
class GoalEpisodeLadderTest {

    @Agent(description = "Repair crew that fixes one fallen door")
    inner class RepairCrewAgent {

        @Action(value = 0.5)
        fun equipGripper(door: DoorDown, context: ActionContext): GripperOn {
            context.share(ExecutedStep("child-equip:${door.id}"))
            return GripperOn(door.id)
        }

        @Action(value = 0.9)
        @AchievesGoal(description = "Door fixed", value = 1.0)
        fun reattachDoor(gripper: GripperOn, context: ActionContext): DoorFixed {
            context.share(ExecutedStep("child-reattach:${gripper.id}"))
            return DoorFixed(gripper.id)
        }
    }

    @Agent(description = "Welder that dispatches door repairs to child processes")
    inner class WeldingParentAgent {

        val children = mutableListOf<AgentProcess>()

        private val repairCrew: CoreAgent by lazy {
            AgentMetadataReader().createAgentMetadata(RepairCrewAgent()) as CoreAgent
        }

        @Action(canRerun = true, value = 0.2)
        fun weld(tally: WeldTally, context: ActionContext): WeldTally {
            context.share(ExecutedStep("weld"))
            val next = WeldTally(tally.count + 1)
            if (next.count == 2) {
                context.evolve(DoorDown("door-7"))
            }
            if (next.count == 4) {
                context.evolve(DoorDown("door-8"))
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
            context.share(ExecutedStep("dispatch:${door.id}"))
            val platform = context.processContext.platformServices.agentPlatform
            // Declared child options: the crew plans under GOAP regardless
            // of the parent's HYBRID declaration
            val child = platform.createChildProcess(repairCrew, context.agentProcess, ProcessOptions.DEFAULT)
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
        val recorder = ProcessEventRecorder()
        val process = create(
            parentAgent,
            "episode-ladder",
            ProcessOptions.DEFAULT
                .withPlannerType(PlannerType.HYBRID)
                // Derived: the repair rule comes from the goal graph, each
                // evolved DoorDown is one occurrence, and the shift's
                // objective anchors completion at the parent level exactly
                // as it would for an in-process chain
                .withEvolving(GoalTarget.output(ShiftLog::class.java))
                .withListener(recorder),
            WeldTally(0),
        )

        val result = process.run()

        assertEquals(AgentProcessStatusCode.COMPLETED, result.status)
        assertEquals(6, result.last<ShiftLog>()?.welds, "The shift ran to its terminal goal")

        // Standing work interleaves BETWEEN child-process episodes: the
        // parent planner arbitrates the dispatch like any other action
        val parentSteps = result.objects.filterIsInstance<ExecutedStep>().map { it.name }
        assertEquals(
            listOf(
                "weld", "weld",
                "dispatch:door-7", "child-equip:door-7", "child-reattach:door-7",
                "weld", "weld",
                "dispatch:door-8", "child-equip:door-8", "child-reattach:door-8",
                "weld", "weld",
            ),
            parentSteps,
            "Welds continue between episodes; children explicitly share their repair trace",
        )

        // Isolation: ordinary child intermediates remain local. Only the
        // trace explicitly published with ctx.share crosses the boundary.
        assertNull(result.last<GripperOn>(), "Child intermediates never touch the parent blackboard")

        // The parent-level episode contract held: both occurrences consumed
        assertNull(result.last<DoorDown>(), "Both requests consumed")
        assertNull(result.last<DoorFixed>(), "Both satisfying outputs consumed")

        // Each dispatch ran its own isolated child to completion. The
        // dispatch action itself executes inside a framework child, so the
        // manual child's recorded parent is that framework child: the tower
        // deepened by one level and lineage is transitive
        assertEquals(2, parentAgent.children.size, "One child process per occurrence")
        val frameworkIds = recorder.episodeStarts(process).map { it.childProcessId }.toSet()
        parentAgent.children.forEach { child ->
            assertEquals(AgentProcessStatusCode.COMPLETED, child.status)
            assertTrue(
                child.parentId in frameworkIds,
                "The manual child's parent is the framework child that ran the dispatch action",
            )
        }
        val childActions = parentAgent.children.map { child ->
            child.history.map { it.actionName.substringAfterLast('.') }
        }
        assertEquals(
            listOf(
                listOf("equipGripper", "reattachDoor"),
                listOf("equipGripper", "reattachDoor"),
            ),
            childActions,
            "Each door ran its own two-step chain in its own process",
        )
    }

    @Agent(description = "Crew that runs its own episode loop inside a contained episode")
    inner class SubMissionCrewAgent {

        @Action(canRerun = true, value = 0.5)
        @AchievesGoal(description = "Step collected", value = 1.0)
        fun collectStep(request: StepRequested, tally: StepTally, context: ActionContext): StepDone {
            context.share(ExecutedStep("child-step:${request.id}"))
            val next = StepTally(tally.count + 1)
            context.share(next)
            if (next.count < 2) {
                context.evolve(StepRequested(request.id + 1))
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
            context.share(ExecutedStep("wave:${wave.id}"))
            context.addObject(StepTally(0))
            val platform = context.processContext.platformServices.agentPlatform
            val child = platform.createChildProcess(
                crew,
                context.agentProcess,
                ProcessOptions.DEFAULT.withEvolving(GoalTarget.output(StepsDone::class.java)),
            )
            children += child
            child.evolve(StepRequested(1))
            val done = child.run().last<StepsDone>()
                ?: error("Sub-mission for wave ${wave.id} produced nothing: status=${child.status}")
            context.share(MissionTally(missions.count + 1))
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
        // The evolving declaration reaches the child only by explicit
        // dispatch-site declaration, never by inheritance, so levels
        // compose deliberately
        val parent = TowerParentAgent()
        val process = create(
            parent,
            "episode-ladder-tower",
            ProcessOptions.DEFAULT.withEvolving(GoalTarget.output(WavesComplete::class.java)),
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
}
