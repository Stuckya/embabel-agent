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

import com.embabel.agent.api.common.scope.AgentScopeBuilder
import com.embabel.agent.api.evolution.ObjectiveAuthor
import com.embabel.agent.api.evolution.ObjectiveAuthorRequest
import com.embabel.agent.api.evolution.ObjectivePlan
import com.embabel.agent.core.Agent
import com.embabel.agent.core.AgentPlatform
import com.embabel.agent.core.AgentProcess
import com.embabel.agent.core.AgentScope
import com.embabel.agent.core.AgendaEntry
import com.embabel.agent.core.EvolutionOptions
import com.embabel.agent.core.Goal
import com.embabel.agent.core.GoalAgenda
import com.embabel.agent.core.ProcessOptions
import com.embabel.agent.core.support.NIRVANA
import com.embabel.agent.spi.common.Constants.EMBABEL_PROVIDER
import java.util.concurrent.CompletableFuture

/**
 * Public invocation shape for long-lived processes whose runtime goals are
 * supplied through an evolving agenda.
 */
data class EvolvingInvocation @JvmOverloads constructor(
    private val agentPlatform: AgentPlatform,
    private val processOptions: ProcessOptions = ProcessOptions(),
    private val agentScopeBuilder: AgentScopeBuilder = agentPlatform,
    private val agentName: String? = null,
    private val objectiveAuthor: ObjectiveAuthor? = null,
) : ScopedInvocation<EvolvingInvocation> {

    override fun withProcessOptions(options: ProcessOptions): EvolvingInvocation =
        copy(processOptions = options)

    override fun withScope(agentScopeBuilder: AgentScopeBuilder): EvolvingInvocation =
        copy(agentScopeBuilder = agentScopeBuilder)

    override fun withAgentName(name: String): EvolvingInvocation =
        copy(agentName = name)

    fun withEvolution(evolution: EvolutionOptions): EvolvingInvocation =
        withProcessOptions(processOptions.withEvolution(evolution))

    fun withObjectiveAuthor(objectiveAuthor: ObjectiveAuthor): EvolvingInvocation =
        copy(objectiveAuthor = objectiveAuthor)

    override fun runAsync(
        obj: Any,
        vararg objs: Any,
    ): CompletableFuture<AgentProcess> =
        agentPlatform.start(createProcess(obj, *objs))

    fun createProcess(
        obj: Any,
        vararg objs: Any,
    ): AgentProcess {
        val prepared = prepareRun(
            objective = obj,
            additionalInputs = objs.toList(),
        )
        val args = (listOf(obj) + objs.toList() + prepared.initialFacts).toTypedArray()
        return agentPlatform.createAgentProcessFrom(
            agent = prepared.agent,
            processOptions = prepared.processOptions,
            objectsToAdd = args,
        )
    }

    override fun runAsync(map: Map<String, Any>): CompletableFuture<AgentProcess> =
        agentPlatform.start(createProcess(map))

    fun createProcess(map: Map<String, Any>): AgentProcess {
        val prepared = prepareRun(
            objective = map,
            additionalInputs = emptyList(),
        )
        val agentProcess = agentPlatform.createAgentProcess(
            agent = prepared.agent,
            processOptions = prepared.processOptions,
            bindings = map,
        )
        prepared.initialFacts.forEach { agentProcess.addObject(it) }
        return agentProcess
    }

    fun createEvolvingAgent(): Agent {
        val scope = agentScopeBuilder.createAgentScope()
        return createEvolvingAgent(scope)
    }

    private fun prepareRun(
        objective: Any,
        additionalInputs: List<Any>,
    ): PreparedRun {
        val scope = agentScopeBuilder.createAgentScope()
        val objectivePlan = objectiveAuthor?.author(
            ObjectiveAuthorRequest(
                objective = objective,
                additionalInputs = additionalInputs,
                scope = scope,
                processOptions = processOptions,
            )
        )
        val canonicalEvolution = processOptions.evolution.copy(
            agendaCatalog = canonicalizeAgendaEntries(
                source = "EvolutionOptions",
                entries = processOptions.evolution.agendaCatalog.entries,
                scope = scope,
            ).agendaCatalog()
        )
        val canonicalObjectivePlan = objectivePlan?.let { canonicalizeObjectivePlan(it, scope) }
        return PreparedRun(
            agent = createEvolvingAgent(scope),
            processOptions = canonicalObjectivePlan?.applyTo(
                processOptions.withEvolution(canonicalEvolution)
            ) ?: processOptions.withEvolution(canonicalEvolution),
            initialFacts = canonicalObjectivePlan?.initialFacts ?: emptyList(),
        )
    }

    private fun createEvolvingAgent(scope: AgentScope): Agent =
        scope.createAgent(
            name = agentName ?: "${agentPlatform.name}.evolving",
            provider = EMBABEL_PROVIDER,
            description = "Platform evolving agent",
        ).copy(goals = emptySet())

    private fun canonicalizeObjectivePlan(
        objectivePlan: ObjectivePlan,
        scope: AgentScope,
    ): ObjectivePlan =
        objectivePlan.copy(
            agendaEntries = canonicalizeAgendaEntries(
                source = "ObjectivePlan ${objectivePlan.id}",
                entries = objectivePlan.agendaEntries,
                scope = scope,
            )
        )

    private fun canonicalizeAgendaEntries(
        source: String,
        entries: List<AgendaEntry>,
        scope: AgentScope,
    ): List<AgendaEntry> =
        entries.map { entry ->
            val canonicalGoal = scope.canonicalGoal(entry.goal)
                ?: throw IllegalArgumentException(
                    "$source references agenda entry ${entry.id} " +
                            "with goal ${entry.goal.name}, which is not in the active AgentScope"
                )
            entry.copy(goal = canonicalGoal)
        }

    private fun List<AgendaEntry>.agendaCatalog(): GoalAgenda =
        fold(GoalAgenda.EMPTY) { agenda, entry -> agenda.withEntry(entry) }

    private fun AgentScope.canonicalGoal(goal: Goal): Goal? {
        if (goal == NIRVANA) {
            return NIRVANA
        }
        goals.firstOrNull { it == goal }?.let { return it }
        val matchingGoals = goals.filter { it.matchesIdentity(goal) }
        return matchingGoals.singleOrNull()
    }

    private fun Goal.matchesIdentity(other: Goal): Boolean =
        name == other.name && outputType?.name == other.outputType?.name

    private data class PreparedRun(
        val agent: Agent,
        val processOptions: ProcessOptions,
        val initialFacts: List<Any>,
    )

    companion object {

        @JvmStatic
        fun on(agentPlatform: AgentPlatform): EvolvingInvocation =
            EvolvingInvocation(agentPlatform = agentPlatform)
    }
}
