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

    fun withBindings(bindings: Map<String, Any>): AgendaEntry =
        copy(bindings = bindings)

    fun withSource(source: Any?): AgendaEntry =
        copy(source = source)

    fun withCompletionMode(completionMode: AgendaCompletionMode): AgendaEntry =
        copy(completionMode = completionMode)

    fun withActivationKey(activationKey: String?): AgendaEntry =
        copy(activationKey = activationKey)

    fun <T : Any> activatedBy(trigger: ActivationTrigger<T>): AgendaEntry =
        withActivationKey(trigger.key)

    fun withTtl(ttl: Duration?): AgendaEntry =
        copy(ttl = ttl)

    fun withCompletionPredicate(completionPredicate: AgendaCompletionPredicate?): AgendaEntry =
        copy(completionPredicate = completionPredicate)

    companion object {

        @JvmStatic
        fun of(
            id: String,
            goal: Goal,
        ): AgendaEntry = AgendaEntry(id = id, goal = goal)
    }
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

enum class AgendaCompletionMode {
    TERMINAL,
    RESUMABLE,
    COMPOSITE_TERMINAL,
}

data class EvolutionPolicy @JvmOverloads constructor(
    val rules: List<RuntimeGoalRule> = emptyList(),
) {

    fun onFact(factType: Class<*>): RuntimeFactRuleSpec =
        RuntimeFactRuleSpec(policy = this, factType = factType)

    @JvmOverloads
    @Deprecated("Use onFact(...).handleWith(...).resumable() or onFact(...).handleWithGoal(...).resumable().")
    fun onEvent(
        eventType: Class<*>,
        runtimeAction: Class<*>,
        completionMode: AgendaCompletionMode = AgendaCompletionMode.RESUMABLE,
    ): EvolutionPolicy =
        withRule(
            RuntimeGoalRule(
                eventType = eventType,
                runtimeAction = runtimeAction,
                completionMode = completionMode,
            )
        )

    fun withRule(rule: RuntimeGoalRule): EvolutionPolicy =
        copy(rules = rules + rule)

    companion object {

        @JvmField
        val EMPTY = EvolutionPolicy()
    }
}

class RuntimeFactRuleSpec internal constructor(
    private val policy: EvolutionPolicy,
    private val factType: Class<*>,
) {

    fun handleWith(outputType: Class<*>): RuntimeGoalRuleSpec =
        RuntimeGoalRuleSpec(
            policy = policy,
            factType = factType,
            runtimeAction = outputType,
            goal = null,
        )

    fun handleWithGoal(goal: Goal): RuntimeGoalRuleSpec =
        RuntimeGoalRuleSpec(
            policy = policy,
            factType = factType,
            runtimeAction = null,
            goal = goal,
        )
}

class RuntimeGoalRuleSpec internal constructor(
    private val policy: EvolutionPolicy,
    private val factType: Class<*>,
    private val runtimeAction: Class<*>?,
    private val goal: Goal?,
) {

    fun terminal(): EvolutionPolicy =
        withCompletionMode(AgendaCompletionMode.TERMINAL)

    fun resumable(): EvolutionPolicy =
        withCompletionMode(AgendaCompletionMode.RESUMABLE)

    fun compositeTerminal(): EvolutionPolicy =
        withCompletionMode(AgendaCompletionMode.COMPOSITE_TERMINAL)

    private fun withCompletionMode(completionMode: AgendaCompletionMode): EvolutionPolicy =
        policy.withRule(
            RuntimeGoalRule(
                eventType = factType,
                runtimeAction = runtimeAction,
                completionMode = completionMode,
                goal = goal,
            )
        )
}

data class RuntimeGoalRule @JvmOverloads constructor(
    val eventType: Class<*>,
    val runtimeAction: Class<*>? = null,
    val completionMode: AgendaCompletionMode = AgendaCompletionMode.RESUMABLE,
    val goal: Goal? = null,
    val id: String = "${eventType.name}->${goal?.name ?: runtimeAction?.name ?: "runtime-goal"}",
) {

    val factType: Class<*>
        get() = eventType

    fun withGoal(goal: Goal): RuntimeGoalRule =
        copy(goal = goal)

    @JvmOverloads
    fun toAgendaEntry(
        sourceFact: Any,
        activationId: String = System.identityHashCode(sourceFact).toString(),
    ): AgendaEntry {
        val canonicalGoal = goal ?: error("Runtime goal rule $id has not been canonicalized")
        return AgendaEntry(
            id = "$id:$activationId",
            goal = canonicalGoal,
            source = sourceFact,
            completionMode = completionMode,
        )
    }
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
    val bindings: Map<String, Any> = entry.bindings,
    val currentAgenda: GoalAgenda = GoalAgenda.EMPTY,
    val agentProcess: AgentProcess? = null,
)

sealed interface AgendaEntryApprovalResponse {
    val request: AgendaEntryApprovalRequest
    val approved: Boolean
}

data class AgendaEntryApproved(
    override val request: AgendaEntryApprovalRequest,
) : AgendaEntryApprovalResponse {
    override val approved: Boolean = true
}

data class AgendaEntryNotApproved(
    override val request: AgendaEntryApprovalRequest,
    val reason: String,
) : AgendaEntryApprovalResponse {
    override val approved: Boolean = false
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
    val policy: EvolutionPolicy = EvolutionPolicy.EMPTY,
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
