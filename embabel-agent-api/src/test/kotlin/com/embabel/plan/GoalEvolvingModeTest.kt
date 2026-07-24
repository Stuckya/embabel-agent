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
import com.embabel.agent.api.annotation.support.AgentMetadataReader
import com.embabel.agent.api.common.ActionContext
import com.embabel.agent.api.common.PlannerType
import com.embabel.agent.api.event.OccurrenceAcceptedEvent
import com.embabel.agent.core.Agent as CoreAgent
import com.embabel.agent.core.AgentProcessStatusCode
import com.embabel.agent.core.Evolving
import com.embabel.agent.core.GoalTarget
import com.embabel.agent.core.ProcessOptions
import com.embabel.agent.core.last
import com.embabel.agent.core.support.InMemoryBlackboard
import com.embabel.agent.core.support.SimpleAgentProcess
import com.embabel.agent.spi.support.DefaultPlannerFactory
import com.embabel.agent.test.integration.IntegrationTestUtils.dummyPlatformServices
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.Instant
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

data class SeedRequested(val id: String)
data class KickoffDone(val id: String)
data class DoorFellOff(val id: String)
data class DoorReattached(val id: String)
data class ZoneInfo(val name: String)
data class AuditDone(val id: String)

/**
 * Contract tests for the planner-session form of Evolving Mode.
 *
 * These tests deliberately avoid any assertion about graph-derived episode
 * rules. The runtime owns occurrence lifecycle and child execution; the
 * selected planner owns routing, missions, obstruction, and completion.
 */
class GoalEvolvingModeTest {

    @Agent(description = "Calibration whose chain reads an occurrence and standing zone state")
    inner class CalibrationAgent {

        @Action(canRerun = true, value = 0.9)
        @AchievesGoal(description = "Calibration completed", value = 1.0)
        fun calibrate(
            request: CalibrationRequested,
            zone: ZoneInfo,
            context: ActionContext,
        ): CalibrationCompleted {
            context.share(ExecutedStep("calibrate:${request.id}@${zone.name}"))
            return CalibrationCompleted(request.id)
        }
    }

    @Test
    fun `a named objective matching no declared goal fails at construction`() {
        val agent = AgentMetadataReader().createAgentMetadata(CalibrationAgent()) as CoreAgent
        assertThrows<IllegalArgumentException> {
            SimpleAgentProcess(
                "evolving-mode-no-such-objective",
                null,
                agent,
                ProcessOptions.DEFAULT.withEvolving(GoalTarget.named("no-such-goal")),
                InMemoryBlackboard(),
                dummyPlatformServices(),
                DefaultPlannerFactory,
                Instant.now(),
            )
        }
    }

    @Test
    fun `withEvolving declares a root mission without a planner type`() {
        val objective = GoalTarget.output(CalibrationCompleted::class.java)
        assertEquals(Evolving(objective), ProcessOptions.DEFAULT.withEvolving(objective).evolving)
        assertTrue("EVOLVING" !in PlannerType.entries.map { it.name })
    }

    @Test
    fun `an unroutable occurrence is accepted and parked by the planner`() {
        val recorder = ProcessEventRecorder()
        val process = evolvingProcess(recorder = recorder)

        process.evolve(AuditDone("audit-1"))
        val result = process.run()

        assertEquals(AgentProcessStatusCode.STUCK, result.status)
        assertEquals(0, recorder.episodeStarts(process).size)
    }

    @Test
    fun `ordinary standing facts remain root work rather than episodes`() {
        val recorder = ProcessEventRecorder()
        val process = evolvingProcess(
            ZoneInfo("zone-9"),
            CalibrationRequested("standing"),
            recorder = recorder,
        )

        val result = process.run()

        assertEquals(AgentProcessStatusCode.STUCK, result.status)
        assertEquals(0, recorder.episodeStarts(process).size)
        assertEquals(
            listOf("calibrate:standing@zone-9"),
            result.objects.filterIsInstance<ExecutedStep>().map { it.name },
        )
    }

    @Test
    fun `episode completion does not complete the root mission`() {
        val recorder = ProcessEventRecorder()
        val process = evolvingProcess(
            ZoneInfo("zone-9"),
            objective = GoalTarget.output(CalibrationCompleted::class.java),
            recorder = recorder,
        )
        process.evolve(CalibrationRequested("episode-1"))

        val result = process.run()

        assertEquals(AgentProcessStatusCode.STUCK, result.status)
        assertNull(result.last<CalibrationCompleted>(), "Child output remains child-local")
        assertEquals(
            "episode-1",
            recorder.childProcesses(process).single().last<CalibrationCompleted>()?.id,
        )
    }

    @Test
    fun `only planner CompleteProcess completes an evolving root`() {
        val process = evolvingProcess(
            ZoneInfo("zone-9"),
            CalibrationRequested("root-work"),
            objective = GoalTarget.output(CalibrationCompleted::class.java),
        )

        val result = process.run()

        assertEquals(AgentProcessStatusCode.COMPLETED, result.status)
        assertEquals("root-work", result.last<CalibrationCompleted>()?.id)
        assertThrows<IllegalArgumentException> {
            process.evolve(CalibrationRequested("too-late"))
        }
    }

    @Test
    fun `equal publications remain distinct occurrences`() {
        val recorder = ProcessEventRecorder()
        val process = evolvingProcess(recorder = recorder)
        val fact = AuditDone("same")

        val first = process.evolve(fact)
        val second = process.evolve(fact)

        assertTrue(first != second)
        assertEquals(AgentProcessStatusCode.STUCK, process.run().status)
        assertEquals(0, recorder.episodeStarts(process).size)
    }

    @Test
    fun `external evolve calls are accepted atomically from concurrent publishers`() {
        val recorder = ProcessEventRecorder()
        val process = evolvingProcess(recorder = recorder)
        val executor = Executors.newFixedThreadPool(4)
        try {
            val publications = (1..32).map { id ->
                executor.submit<com.embabel.agent.core.OccurrenceId> {
                    process.evolve(AuditDone("audit-$id"))
                }
            }
            val occurrences = publications.map { it.get(10, TimeUnit.SECONDS) }

            assertEquals(32, occurrences.toSet().size)

            val result = process.run()

            assertEquals(AgentProcessStatusCode.STUCK, result.status)
            val accepted = recorder.events.filterIsInstance<OccurrenceAcceptedEvent>()
            assertEquals(occurrences.toSet(), accepted.map { it.occurrenceId }.toSet())
            assertTrue(accepted.all { it.publishedBy == null })
            assertEquals(0, recorder.episodeStarts(process).size)
        } finally {
            executor.shutdownNow()
        }
    }

    private fun evolvingProcess(
        vararg seeds: Any,
        objective: GoalTarget? = null,
        recorder: ProcessEventRecorder? = null,
    ): SimpleAgentProcess {
        val blackboard = InMemoryBlackboard()
        seeds.forEach(blackboard::addObject)
        val agent = AgentMetadataReader().createAgentMetadata(CalibrationAgent()) as CoreAgent
        var options = objective?.let(ProcessOptions.DEFAULT::withEvolving)
            ?: ProcessOptions.DEFAULT.withEvolving()
        recorder?.let { options = options.withListener(it) }
        return SimpleAgentProcess(
            "evolving-mode",
            null,
            agent,
            options,
            blackboard,
            dummyPlatformServices(),
            DefaultPlannerFactory,
            Instant.now(),
        )
    }
}
