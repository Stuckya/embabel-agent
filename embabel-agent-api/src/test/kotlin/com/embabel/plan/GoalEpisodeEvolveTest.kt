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
import com.embabel.agent.core.Agent as CoreAgent
import com.embabel.agent.core.AgentProcessStatusCode
import com.embabel.agent.core.EpisodePolicy
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
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

data class AuditDone(val id: String)

/**
 * Publication-site designation: an occurrence is whatever arrives through
 * evolve(), so intent lives with the publisher and no driver mapping exists
 * anywhere. The chain author declares nothing; the observer who publishes
 * the fact knows whether it is a request or standing state.
 *
 * What it pins:
 * - An evolved() rule on a chain with several off-chain inputs is valid:
 *   the old inference ambiguity dissolves because designation rides the
 *   instance, not the type. The standing input is used, never consumed.
 *   The mode is declared: a bare rule stays type-subscribed and ambiguity
 *   stays a loud construction error.
 * - Designation is per instance: an evolved fact is admitted and consumed;
 *   a plain addObject fact of the same type is never an occurrence, never
 *   admitted, and survives untouched.
 * - Consumption stays identity-based: completion consumes exactly the
 *   evolved instance the episode holds.
 */
class GoalEpisodeEvolveTest {

    @Agent(description = "Calibration whose chain reads a request and standing zone info")
    inner class EvolveCalibrationAgent {

        @Action(canRerun = true, value = 0.9)
        @AchievesGoal(description = "Calibration completed", value = 1.0)
        fun calibrate(request: CalibrationRequested, zone: ZoneInfo, context: ActionContext): CalibrationCompleted {
            context.addObject(ExecutedStep("calibrate:${request.id}@${zone.name}"))
            return CalibrationCompleted(request.id)
        }
    }

    private fun create(vararg seeds: Any): SimpleAgentProcess {
        val blackboard = InMemoryBlackboard()
        seeds.forEach { blackboard.addObject(it) }
        val agent = AgentMetadataReader().createAgentMetadata(EvolveCalibrationAgent()) as CoreAgent
        return SimpleAgentProcess(
            "episode-evolve",
            null,
            agent,
            ProcessOptions.DEFAULT.withEpisodes(
                // Explicitly evolved: admits only evolve()-published
                // occurrences, so no driver mapping is needed even though
                // the chain has two off-chain inputs
                EpisodePolicy.episode(GoalTarget.output(CalibrationCompleted::class.java)).evolved()
            ),
            blackboard,
            dummyPlatformServices(),
            DefaultPlannerFactory,
            Instant.now(),
        )
    }

    @Test
    fun `an evolved request drives an ambiguous chain - the driver mapping dissolves`() {
        val process = create(ZoneInfo("zone-9"))
        process.evolve(CalibrationRequested("cal-1"))

        val result = process.run()

        assertEquals(AgentProcessStatusCode.STUCK, result.status, "One episode completed, then a clean park")
        val steps = result.objects.filterIsInstance<ExecutedStep>().map { it.name }
        assertEquals(listOf("calibrate:cal-1@zone-9"), steps, "The evolved occurrence drove the chain once")
        assertNull(result.last<CalibrationRequested>(), "The evolved instance was consumed by identity")
        assertNull(result.last<CalibrationCompleted>(), "The episode's consumable was consumed")
        assertNotNull(result.last<ZoneInfo>(), "The standing input was used, never consumed")
    }

    @Agent(description = "Calibration chain that evolves its own follow-up")
    inner class SelfChainingEvolveAgent {

        @Action(canRerun = true, value = 0.9)
        @AchievesGoal(description = "Calibration completed", value = 1.0)
        fun calibrate(request: CalibrationRequested, context: ActionContext): CalibrationCompleted {
            context.addObject(ExecutedStep("calibrate:${request.id}"))
            if (request.id == "cal-1") {
                (context.agentProcess as SimpleAgentProcess).evolve(CalibrationRequested("cal-2"))
            }
            return CalibrationCompleted(request.id)
        }
    }

    @Test
    fun `evolving an unroutable type fails at the call site`() {
        // Fail fast at the boundary: an evolving process's evolvable types
        // are its enforced contract. Without the throw, a mis-deployed
        // publisher believes work was scheduled and nothing ever happens
        val process = create(ZoneInfo("zone-9"))

        val exception = assertThrows<IllegalArgumentException> {
            process.evolve(MissionReport(42))
        }
        assertTrue("MissionReport" in exception.message!!, "Names the unroutable type: ${exception.message}")
        assertTrue(
            "CalibrationRequested" in exception.message!!,
            "Lists what this process can evolve: ${exception.message}",
        )
    }

    @Test
    fun `an evolved follow-up records its causing episode - lineage at publication`() {
        val blackboard = InMemoryBlackboard()
        val agent = AgentMetadataReader().createAgentMetadata(SelfChainingEvolveAgent()) as CoreAgent
        val process = SimpleAgentProcess(
            "episode-evolve-lineage",
            null,
            agent,
            ProcessOptions.DEFAULT.withEpisodes(
                EpisodePolicy.episode(GoalTarget.output(CalibrationCompleted::class.java)).evolved()
            ),
            blackboard,
            dummyPlatformServices(),
            DefaultPlannerFactory,
            Instant.now(),
        )
        process.evolve(CalibrationRequested("cal-1"))

        process.run()

        val second = process.lastCompletedEpisode
        assertEquals("cal-2", (second?.request as? CalibrationRequested)?.id, "The follow-up completed last")
        assertEquals(
            "cal-1", (second?.causedBy?.request as? CalibrationRequested)?.id,
            "The follow-up records the episode that evolved it",
        )
        assertNull(
            second?.causedBy?.causedBy,
            "The first episode was evolved externally and has no causing episode",
        )
    }

    @Test
    fun `the chain binds its episode's request even when a plain fact of the same type is newer`() {
        // Grounding: while a chain action runs for an evolved episode, the
        // episode's request is the only visible instance of its own class,
        // so binding by type resolves the occurrence the episode holds.
        // Standard ground-action semantics (AIMA 3e SS10.1), per occurrence
        val process = create(ZoneInfo("zone-9"))
        process.evolve(CalibrationRequested("cal-1"))
        process.addObject(CalibrationRequested("cal-2"))

        val result = process.run()

        val steps = result.objects.filterIsInstance<ExecutedStep>().map { it.name }
        assertEquals(
            listOf("calibrate:cal-1@zone-9"), steps,
            "The chain executed against its episode's request, not the newest same-type fact",
        )
        assertEquals("cal-2", result.last<CalibrationRequested>()?.id, "The plain fact survives, unbound and unconsumed")
        assertNull(result.last<CalibrationCompleted>(), "The episode's own output was consumed")
    }

    @Agent(description = "Two goals whose rules would contest the same request type")
    inner class OverlappingRoutesAgent {

        @Action(canRerun = true, value = 0.9)
        @AchievesGoal(description = "Calibration completed", value = 1.0)
        fun calibrate(request: CalibrationRequested, zone: ZoneInfo, context: ActionContext): CalibrationCompleted {
            context.addObject(ExecutedStep("calibrate:${request.id}"))
            return CalibrationCompleted(request.id)
        }

        @Action(canRerun = true, value = 0.8)
        @AchievesGoal(description = "Audit done", value = 0.9)
        fun audit(request: CalibrationRequested, context: ActionContext): AuditDone {
            context.addObject(ExecutedStep("audit:${request.id}"))
            return AuditDone(request.id)
        }
    }

    @Test
    fun `an arrival type contested between rules fails fast at construction`() {
        // An evolved rule's eligible types may not overlap another rule's
        // consumed type: an arrival must route to exactly one episode
        val blackboard = InMemoryBlackboard()
        val agent = AgentMetadataReader().createAgentMetadata(OverlappingRoutesAgent()) as CoreAgent
        val exception = assertThrows<IllegalArgumentException> {
            SimpleAgentProcess(
                "episode-evolve-contested",
                null,
                agent,
                ProcessOptions.DEFAULT.withEpisodes(
                    EpisodePolicy
                        .episode(GoalTarget.output(CalibrationCompleted::class.java)).evolved()
                        .episode(GoalTarget.output(AuditDone::class.java))
                        .consumeOnCompletion(CalibrationRequested::class.java)
                ),
                blackboard,
                dummyPlatformServices(),
                DefaultPlannerFactory,
                Instant.now(),
            )
        }
        assertTrue("exactly one episode" in exception.message!!, "Names the routing rule: ${exception.message}")
        assertTrue("CalibrationRequested" in exception.message!!, "Names the contested type: ${exception.message}")
    }

    @Test
    fun `evolving a type-subscribed request admits like any arrival`() {
        // Harmless by design: for a type-subscribed rule every instance is
        // an occurrence anyway, so the designation adds nothing
        val blackboard = InMemoryBlackboard()
        blackboard.addObject(ZoneInfo("zone-9"))
        val agent = AgentMetadataReader().createAgentMetadata(EvolveCalibrationAgent()) as CoreAgent
        val process = SimpleAgentProcess(
            "episode-evolve-subscribed",
            null,
            agent,
            ProcessOptions.DEFAULT.withEpisodes(
                EpisodePolicy
                    .episode(GoalTarget.output(CalibrationCompleted::class.java))
                    .consumeOnCompletion(CalibrationRequested::class.java)
            ),
            blackboard,
            dummyPlatformServices(),
            DefaultPlannerFactory,
            Instant.now(),
        )

        process.evolve(CalibrationRequested("cal-1"))
        val result = process.run()

        val steps = result.objects.filterIsInstance<ExecutedStep>().map { it.name }
        assertEquals(listOf("calibrate:cal-1@zone-9"), steps, "The evolved instance admitted through the type path")
        assertNull(result.last<CalibrationRequested>(), "And was consumed as the occurrence")
    }

    @Test
    fun `evolving a standing-state type is obeyed - designation trusts the publisher`() {
        // The flip side of per-instance power: evolve the wrong kind of
        // thing and the framework obeys. The zone becomes the occurrence
        // and is consumed at completion. Standing state, destroyed by a
        // correct execution of declared semantics: pinned so it is
        // documented behavior, not a surprise
        val process = create()
        process.evolve(ZoneInfo("zone-9"))
        process.addObject(CalibrationRequested("cal-1"))

        val result = process.run()

        val steps = result.objects.filterIsInstance<ExecutedStep>().map { it.name }
        assertEquals(listOf("calibrate:cal-1@zone-9"), steps, "The chain ran, reading the plain request as input")
        assertNull(result.last<ZoneInfo>(), "The evolved zone was the occurrence and was consumed")
        assertEquals("cal-1", result.last<CalibrationRequested>()?.id, "The plain request was input, never an occurrence")
    }

    @Test
    fun `a plain fact of the same type is never an occurrence - designation rides the instance`() {
        val process = create(ZoneInfo("zone-9"))
        process.evolve(CalibrationRequested("cal-1"))

        val afterFirst = process.run()
        assertEquals(listOf("calibrate:cal-1@zone-9"),
            afterFirst.objects.filterIsInstance<ExecutedStep>().map { it.name })

        // Same type, plain publication: a fact, not a request
        afterFirst.addObject(CalibrationRequested("cal-2"))
        val afterPlainFact = afterFirst.run()

        val steps = afterPlainFact.objects.filterIsInstance<ExecutedStep>().map { it.name }
        assertEquals(1, steps.size, "A plain fact admits no episode; nothing ran")
        assertEquals("cal-2", afterPlainFact.last<CalibrationRequested>()?.id, "The plain fact survives untouched")

        // Evolved publication of the same type: an occurrence
        process.evolve(CalibrationRequested("cal-3"))
        val afterEvolved = afterPlainFact.run()

        val finalSteps = afterEvolved.objects.filterIsInstance<ExecutedStep>().map { it.name }
        assertEquals(
            listOf("calibrate:cal-1@zone-9", "calibrate:cal-3@zone-9"), finalSteps,
            "The evolved instance drove a second episode; the plain fact still did not",
        )
        assertEquals("cal-2", afterEvolved.last<CalibrationRequested>()?.id,
            "The plain cal-2 is still visible: designation is per instance, not per type")
    }
}
