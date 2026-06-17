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
package com.embabel.agent.api.evolution

import com.embabel.agent.core.AgendaEntry
import com.embabel.agent.core.AgentScope
import com.embabel.agent.core.CompletionPolicy
import com.embabel.agent.core.EvolutionOptions
import com.embabel.agent.core.GoalAgenda
import com.embabel.agent.core.ProcessOptions

/**
 * Context provided to an [ObjectiveAuthor] before an evolving process starts.
 */
data class ObjectiveAuthorRequest(
    val objective: Any,
    val additionalInputs: List<Any>,
    val scope: AgentScope,
    val processOptions: ProcessOptions,
) {

    inline fun <reified T : Any> objectiveAs(): T =
        objective as T

    fun <T : Any> objectiveAs(type: Class<T>): T =
        type.cast(objective)
}

/**
 * Deterministic or LLM-backed seam that converts a user request or typed
 * objective into an executable objective plan.
 */
fun interface ObjectiveAuthor {

    fun author(request: ObjectiveAuthorRequest): ObjectivePlan
}

/**
 * Typed proposal for satisfying an objective over a fixed agent scope.
 */
data class ObjectivePlan @JvmOverloads constructor(
    val id: String,
    val agendaEntries: List<AgendaEntry> = emptyList(),
    val initialFacts: List<Any> = emptyList(),
    val completionPolicy: CompletionPolicy? = null,
) {

    fun applyTo(processOptions: ProcessOptions): ProcessOptions =
        processOptions.withEvolution(toEvolutionOptions(processOptions.evolution))

    fun toEvolutionOptions(base: EvolutionOptions): EvolutionOptions {
        val agendaCatalog = agendaEntries.fold(base.agendaCatalog) { agenda, entry ->
            agenda.withEntry(entry)
        }
        return base.copy(
            agendaCatalog = agendaCatalog,
            completionPolicy = completionPolicy ?: base.completionPolicy,
        )
    }

    fun agendaCatalog(): GoalAgenda =
        agendaEntries.fold(GoalAgenda.EMPTY) { agenda, entry ->
            agenda.withEntry(entry)
        }
}
