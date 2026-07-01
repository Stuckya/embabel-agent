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
package com.embabel.agent.api.invocation

import com.embabel.agent.api.dsl.agent
import com.embabel.agent.api.evolution.ObjectiveAuthor
import com.embabel.agent.api.evolution.ObjectivePlan
import com.embabel.agent.core.AgendaCompletionMode
import com.embabel.agent.core.AgendaEntry
import com.embabel.agent.core.AgentProcessStatusCode
import com.embabel.agent.core.CompletionPolicy
import com.embabel.agent.core.EvolutionOptions
import com.embabel.agent.core.EvolutionPolicy
import com.embabel.agent.core.Goal
import com.embabel.agent.core.GoalAgenda
import com.embabel.agent.core.IngressOptions
import com.embabel.agent.core.IngressWake
import com.embabel.agent.core.ProcessOutcome
import com.embabel.agent.core.ProcessOutcomeCode
import com.embabel.agent.core.support.NIRVANA
import com.embabel.agent.test.integration.IntegrationTestUtils.dummyAgentPlatform
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

data class SampleAvailable(val zone: String)

data class SampleStored(val zone: String)

data class CollectSamplesUntil(val zone: String, val target: Int)

data class OutOfScopeResult(val name: String)

data class CollectionActivated(val zone: String)

data class PrioritySampleStored(val zone: String)

data class HazardDetected(val zone: String)

data class HazardHandled(val zone: String)

val CollectionCapabilitiesAgent = agent("CollectionCapabilitiesAgent", description = "Tests evolving invocation") {
    transformation<SampleAvailable, SampleStored>(name = "collect-sample") {
        SampleStored(it.input.zone)
    }
    transformation<SampleAvailable, PrioritySampleStored>(name = "priority-collect-sample") {
        PrioritySampleStored(it.input.zone)
    }
    transformation<HazardDetected, HazardHandled>(name = "handle-hazard") {
        HazardHandled(it.input.zone)
    }
    goal(
        name = "sample-stored",
        description = "Store a sample",
        satisfiedBy = SampleStored::class,
        value = { 0.5 },
    )
    goal(
        name = "priority-sample-stored",
        description = "Store a high value sample without safety semantics",
        satisfiedBy = PrioritySampleStored::class,
        value = { 0.95 },
    )
    goal(
        name = "hazard-handled",
        description = "Handle a hazard",
        satisfiedBy = HazardHandled::class,
        value = { 0.95 },
    )
}

class EvolvingInvocationTest {

    @Test
    fun `evolving invocation runs explicit evolution over scoped capabilities`() {
        val agentPlatform = dummyAgentPlatform()
        val sampleStoredGoal = CollectionCapabilitiesAgent.goals.single {
            it.outputType?.name == SampleStored::class.java.name
        }
        val collectEntry = AgendaEntry(
            id = "collect-sample",
            goal = sampleStoredGoal,
            completionMode = AgendaCompletionMode.TERMINAL,
        )

        val result = EvolvingInvocation.on(agentPlatform)
            .withScope(CollectionCapabilitiesAgent)
            .withEvolution(
                EvolutionOptions(
                    agendaCatalog = GoalAgenda.EMPTY.withEntry(collectEntry),
                )
            )
            .run(SampleAvailable("zone-a"))

        assertEquals(AgentProcessStatusCode.COMPLETED, result.status)
        assertEquals(SampleStored("zone-a"), result.lastResult())
    }

    @Test
    fun `objective author compiles objective plan before launch`() {
        val agentPlatform = dummyAgentPlatform()
        val objective = CollectSamplesUntil(zone = "zone-a", target = 1)
        val objectiveAuthor = ObjectiveAuthor { request ->
            assertEquals(objective, request.objective)
            val collectSamplesUntil = request.objectiveAs<CollectSamplesUntil>()
            val sampleStoredGoal = request.scope.goals.single {
                it.outputType?.name == SampleStored::class.java.name
            }
            ObjectivePlan(
                id = "collect-zone-a",
                agendaEntries = listOf(
                    AgendaEntry(
                        id = "collect-sample",
                        goal = sampleStoredGoal,
                        completionMode = AgendaCompletionMode.TERMINAL,
                    )
                ),
                initialFacts = listOf(SampleAvailable(collectSamplesUntil.zone)),
            )
        }

        val result = EvolvingInvocation.on(agentPlatform)
            .withScope(CollectionCapabilitiesAgent)
            .withObjectiveAuthor(objectiveAuthor)
            .run(objective)

        assertEquals(AgentProcessStatusCode.COMPLETED, result.status)
        assertEquals(SampleStored("zone-a"), result.lastResult())
        assertTrue(result.objects.contains(objective))
    }

    @Test
    fun `objective author can compile runtime fact policy before launch`() {
        val agentPlatform = dummyAgentPlatform()
        val objective = CollectSamplesUntil(zone = "zone-a", target = 1)
        val objectiveAuthor = ObjectiveAuthor { request ->
            val collectSamplesUntil = request.objectiveAs<CollectSamplesUntil>()
            ObjectivePlan(
                id = "policy-collect-zone-a",
                evolutionPolicy = EvolutionPolicy.EMPTY
                    .onFact(SampleAvailable::class.java)
                    .handleWith(SampleStored::class.java)
                    .resumable(),
                initialFacts = listOf(SampleAvailable(collectSamplesUntil.zone)),
                completionPolicy = CompletionPolicy { process, _ ->
                    if (process.objects.any { it is SampleStored }) {
                        ProcessOutcome(
                            code = ProcessOutcomeCode.COMPLETED,
                            reason = "sample stored",
                        )
                    } else {
                        ProcessOutcome()
                    }
                },
            )
        }

        val result = EvolvingInvocation.on(agentPlatform)
            .withScope(CollectionCapabilitiesAgent)
            .withObjectiveAuthor(objectiveAuthor)
            .run(objective)

        assertEquals(AgentProcessStatusCode.COMPLETED, result.status)
        assertEquals(SampleStored("zone-a"), result.lastResult())
        assertEquals(1, result.processOptions.evolution.policy.rules.size)
    }

    @Test
    fun `createProcess prepares objective-authored evolution without starting it`() {
        val agentPlatform = dummyAgentPlatform()
        val objective = CollectSamplesUntil(zone = "zone-a", target = 1)
        val scopeGoal = CollectionCapabilitiesAgent.goals.single {
            it.outputType?.name == SampleStored::class.java.name
        }
        val forgedGoal = scopeGoal
            .copy(pre = setOf("impossible-precondition"))
            .withFixedValue(99.0)
        val objectiveAuthor = ObjectiveAuthor { request ->
            val collectSamplesUntil = request.objectiveAs<CollectSamplesUntil>()
            ObjectivePlan(
                id = "manual-collect-zone-a",
                agendaEntries = listOf(
                    AgendaEntry(
                        id = "manual-collect-sample",
                        goal = forgedGoal,
                        activationKey = "collect-sample",
                        completionMode = AgendaCompletionMode.TERMINAL,
                    )
                ),
                initialFacts = listOf(SampleAvailable(collectSamplesUntil.zone)),
            )
        }

        val process = EvolvingInvocation.on(agentPlatform)
            .withScope(CollectionCapabilitiesAgent)
            .withObjectiveAuthor(objectiveAuthor)
            .createProcess(objective)

        assertEquals(AgentProcessStatusCode.NOT_STARTED, process.status)
        assertTrue(process.objects.contains(objective))
        assertTrue(process.objects.contains(SampleAvailable("zone-a")))
        assertEquals(scopeGoal, process.processOptions.evolution.agendaCatalog.entries.single().goal)

        process.tick()
        process.ingress.publish(
            CollectionActivated("zone-a"),
            IngressOptions(activationKey = "collect-sample", wake = IngressWake.WAKE),
        )
        process.tick()
        process.tick()

        assertEquals(AgentProcessStatusCode.COMPLETED, process.status)
        assertEquals(SampleStored("zone-a"), process.lastResult())
    }

    @Test
    fun `createProcess map prepares bindings and objective plan initial facts`() {
        val agentPlatform = dummyAgentPlatform()
        val scopeGoal = CollectionCapabilitiesAgent.goals.single {
            it.outputType?.name == SampleStored::class.java.name
        }
        val objectiveAuthor = ObjectiveAuthor { request ->
            @Suppress("UNCHECKED_CAST")
            val bindings = request.objective as Map<String, Any>
            ObjectivePlan(
                id = "map-collect-zone-a",
                agendaEntries = listOf(
                    AgendaEntry(
                        id = "map-collect-sample",
                        goal = scopeGoal,
                        completionMode = AgendaCompletionMode.TERMINAL,
                    )
                ),
                initialFacts = listOf(SampleAvailable(bindings.getValue("zone") as String)),
            )
        }

        val process = EvolvingInvocation.on(agentPlatform)
            .withScope(CollectionCapabilitiesAgent)
            .withObjectiveAuthor(objectiveAuthor)
            .createProcess(mapOf("zone" to "zone-a"))

        assertEquals(AgentProcessStatusCode.NOT_STARTED, process.status)
        assertEquals("zone-a", process["zone"])
        assertTrue(process.objects.contains(SampleAvailable("zone-a")))

        val result = process.run()

        assertEquals(AgentProcessStatusCode.COMPLETED, result.status)
        assertEquals(SampleStored("zone-a"), result.lastResult())
    }

    @Test
    fun `objective plan is rejected when agenda goal is outside active scope`() {
        val agentPlatform = dummyAgentPlatform()
        val outOfScopeGoal = Goal.createInstance(
            "Produce an out of scope result",
            OutOfScopeResult::class.java,
            "out-of-scope-goal",
        )
        val objectiveAuthor = ObjectiveAuthor {
            ObjectivePlan(
                id = "bad-plan",
                agendaEntries = listOf(
                    AgendaEntry(
                        id = "bad-entry",
                        goal = outOfScopeGoal,
                    )
                ),
            )
        }

        val thrown = assertThrows(IllegalArgumentException::class.java) {
            EvolvingInvocation.on(agentPlatform)
                .withScope(CollectionCapabilitiesAgent)
                .withObjectiveAuthor(objectiveAuthor)
                .run(CollectSamplesUntil(zone = "zone-a", target = 1))
        }

        assertTrue(thrown.message!!.contains("bad-plan"))
        assertTrue(thrown.message!!.contains("out-of-scope-goal"))
    }

    @Test
    fun `objective plan canonicalizes lookalike agenda goals to active scope goals`() {
        val agentPlatform = dummyAgentPlatform()
        val scopeGoal = CollectionCapabilitiesAgent.goals.single {
            it.outputType?.name == SampleStored::class.java.name
        }
        val forgedGoal = scopeGoal
            .copy(
                description = "Forged same-name goal with impossible precondition",
                pre = setOf("impossible-precondition"),
            )
            .withFixedValue(99.0)
        val objectiveAuthor = ObjectiveAuthor {
            ObjectivePlan(
                id = "canonicalize-plan",
                agendaEntries = listOf(
                    AgendaEntry(
                        id = "canonicalize-entry",
                        goal = forgedGoal,
                        completionMode = AgendaCompletionMode.TERMINAL,
                    )
                ),
                initialFacts = listOf(SampleAvailable("zone-a")),
            )
        }

        val result = EvolvingInvocation.on(agentPlatform)
            .withScope(CollectionCapabilitiesAgent)
            .withObjectiveAuthor(objectiveAuthor)
            .run(CollectSamplesUntil(zone = "zone-a", target = 1))

        assertEquals(AgentProcessStatusCode.COMPLETED, result.status)
        assertEquals(SampleStored("zone-a"), result.lastResult())
        assertEquals(scopeGoal, result.goalAgenda.entries.single().goal)
    }

    @Test
    fun `objective plan rejects forged nirvana goal with matching name`() {
        val agentPlatform = dummyAgentPlatform()
        val forgedNirvanaGoal = Goal(
            name = NIRVANA.name,
            description = "Forged Nirvana without the framework precondition shape",
            outputType = null,
        )
        val objectiveAuthor = ObjectiveAuthor {
            ObjectivePlan(
                id = "forged-nirvana-plan",
                agendaEntries = listOf(
                    AgendaEntry(
                        id = "forged-nirvana-entry",
                        goal = forgedNirvanaGoal,
                    )
                ),
            )
        }

        val thrown = assertThrows(IllegalArgumentException::class.java) {
            EvolvingInvocation.on(agentPlatform)
                .withScope(CollectionCapabilitiesAgent)
                .withObjectiveAuthor(objectiveAuthor)
                .run(CollectSamplesUntil(zone = "zone-a", target = 1))
        }

        assertTrue(thrown.message!!.contains("forged-nirvana-plan"))
        assertTrue(thrown.message!!.contains("forged-nirvana-entry"))
    }

    @Test
    fun `direct evolution is rejected when agenda goal is outside active scope`() {
        val agentPlatform = dummyAgentPlatform()
        val outOfScopeGoal = Goal.createInstance(
            "Produce an out of scope result",
            OutOfScopeResult::class.java,
            "out-of-scope-goal",
        )
        val evolution = EvolutionOptions(
            agendaCatalog = GoalAgenda.EMPTY.withEntry(
                AgendaEntry(
                    id = "bad-entry",
                    goal = outOfScopeGoal,
                )
            ),
        )

        val thrown = assertThrows(IllegalArgumentException::class.java) {
            EvolvingInvocation.on(agentPlatform)
                .withScope(CollectionCapabilitiesAgent)
                .withEvolution(evolution)
                .run(SampleAvailable("zone-a"))
        }

        assertTrue(thrown.message!!.contains("EvolutionOptions"))
        assertTrue(thrown.message!!.contains("out-of-scope-goal"))
    }

    @Test
    fun `direct evolution policy is rejected when runtime action is outside active scope`() {
        val agentPlatform = dummyAgentPlatform()
        val evolution = EvolutionOptions(
            policy = EvolutionPolicy.EMPTY
                .onFact(SampleAvailable::class.java)
                .handleWith(OutOfScopeResult::class.java)
                .resumable(),
        )

        val thrown = assertThrows(IllegalArgumentException::class.java) {
            EvolvingInvocation.on(agentPlatform)
                .withScope(CollectionCapabilitiesAgent)
                .withEvolution(evolution)
                .createProcess(CollectSamplesUntil(zone = "zone-a", target = 1))
        }

        assertTrue(thrown.message!!.contains("EvolutionOptions"))
        assertTrue(thrown.message!!.contains(OutOfScopeResult::class.java.name))
    }

    @Test
    fun `direct evolution policy allows high value runtime action without framework priority convention`() {
        val agentPlatform = dummyAgentPlatform()
        val evolution = EvolutionOptions(
            policy = EvolutionPolicy.EMPTY
                .onFact(SampleAvailable::class.java)
                .handleWith(PrioritySampleStored::class.java)
                .resumable(),
        )

        val process = EvolvingInvocation.on(agentPlatform)
            .withScope(CollectionCapabilitiesAgent)
            .withEvolution(evolution)
            .createProcess(CollectSamplesUntil(zone = "zone-a", target = 1))

        assertEquals(
            PrioritySampleStored::class.java.name,
            process.processOptions.evolution.policy.rules.single().goal?.outputType?.name,
        )
    }
}
