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
package com.embabel.agent.core

import com.embabel.plan.common.condition.ConditionGoal
import java.time.Duration
import java.time.Instant

/**
 * Immutable overlay of goals that can be projected into planning without mutating [Agent.goals].
 */
data class GoalAgenda @JvmOverloads constructor(
    val entries: List<AgendaEntry> = emptyList(),
) {

    fun withEntry(entry: AgendaEntry): GoalAgenda =
        copy(entries = entries.filterNot { it.id == entry.id } + entry)

    fun withoutEntry(id: String): GoalAgenda =
        copy(entries = entries.filterNot { it.id == id })

    @JvmOverloads
    fun expire(now: Instant = Instant.now()): GoalAgenda =
        copy(entries = entries.filterNot { it.isExpired(now) })

    companion object {

        @JvmField
        val EMPTY = GoalAgenda()
    }
}

data class AgendaEntry @JvmOverloads constructor(
    val id: String,
    val goal: Goal,
    val bindings: Map<String, Any> = emptyMap(),
    val source: Any? = null,
    val lane: AgendaLane = AgendaLane.ECONOMIC,
    val completionMode: AgendaCompletionMode = AgendaCompletionMode.TERMINAL,
    val activationKey: String? = null,
    val ttl: Duration? = null,
    val createdAt: Instant = Instant.now(),
    val completionPredicate: AgendaCompletionPredicate? = null,
) {

    val expiresAt: Instant?
        get() = ttl?.let { createdAt.plus(it) }

    fun isExpired(now: Instant): Boolean =
        expiresAt?.let { !it.isAfter(now) } == true
}

/**
 * Planning projection for an active agenda entry.
 *
 * The wrapped entry makes runtime bindings and source context visible from
 * [AgentProcess.goal] while preserving the original agent goal unchanged.
 * The public goal name remains semantic; agenda identity is carried by [entry].
 */
data class AgendaPlanningGoal(
    val entry: AgendaEntry,
) : ConditionGoal {

    override val name: String = entry.goal.name

    override val preconditions = entry.goal.preconditions

    override val value = entry.goal.value

    override val knownConditions = entry.goal.knownConditions

    override fun infoString(
        verbose: Boolean?,
        indent: Int,
    ): String = entry.goal.infoString(verbose, indent)
}

enum class AgendaLane {
    ECONOMIC,
    SAFETY,
}

enum class AgendaCompletionMode {
    TERMINAL,
    RESUMABLE,
    KEEP_ALIVE,
    COMPOSITE_TERMINAL,
}

fun interface AgendaCompletionPredicate {

    fun isComplete(
        agentProcess: AgentProcess,
        entry: AgendaEntry,
        agenda: GoalAgenda,
    ): Boolean
}

data class AgendaEntryApprovalRequest @JvmOverloads constructor(
    val entry: AgendaEntry,
    val sourceFact: Any? = null,
    val sourceType: String? = sourceFact?.javaClass?.name,
    val lane: AgendaLane = entry.lane,
    val bindings: Map<String, Any> = entry.bindings,
    val currentAgenda: GoalAgenda = GoalAgenda.EMPTY,
    val agentProcess: AgentProcess? = null,
)

sealed interface AgendaEntryApprovalResponse {
    val request: AgendaEntryApprovalRequest
    val approved: Boolean

    fun isApproved(): Boolean
}

data class AgendaEntryApproved(
    override val request: AgendaEntryApprovalRequest,
) : AgendaEntryApprovalResponse {
    override val approved: Boolean = true

    override fun isApproved(): Boolean = approved
}

data class AgendaEntryNotApproved(
    override val request: AgendaEntryApprovalRequest,
    val reason: String,
) : AgendaEntryApprovalResponse {
    override val approved: Boolean = false

    override fun isApproved(): Boolean = approved
}

/**
 * Implemented by objects that can approve or veto agenda entry activation.
 */
fun interface AgendaEntryApprover {

    fun approve(request: AgendaEntryApprovalRequest): AgendaEntryApprovalResponse

    companion object {

        @JvmField
        val APPROVE_ALL = AgendaEntryApprover { AgendaEntryApproved(it) }
    }
}

data class EvolutionOptions @JvmOverloads constructor(
    val agendaCatalog: GoalAgenda = GoalAgenda.EMPTY,
    val agendaEntryApprover: AgendaEntryApprover = AgendaEntryApprover.APPROVE_ALL,
    val completionPolicy: CompletionPolicy = CompletionPolicy.CONTINUE,
) {

    @Deprecated("Use agendaCatalog; agenda entries here are an activatable catalog, not active initial state.")
    val initialAgenda: GoalAgenda
        get() = agendaCatalog
}

data class ProcessOutcome @JvmOverloads constructor(
    val code: ProcessOutcomeCode = ProcessOutcomeCode.CONTINUE,
    val reason: String? = null,
    val goal: Goal? = null,
)

enum class ProcessOutcomeCode {
    CONTINUE,
    COMPLETED,
    EXHAUSTED,
    CANCELLED,
}

fun interface CompletionPolicy {

    fun evaluate(
        agentProcess: AgentProcess,
        agenda: GoalAgenda,
    ): ProcessOutcome

    companion object {

        @JvmField
        val CONTINUE = CompletionPolicy { _, _ -> ProcessOutcome() }
    }
}
