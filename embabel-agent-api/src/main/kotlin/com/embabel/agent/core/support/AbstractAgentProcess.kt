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
package com.embabel.agent.core.support

import com.embabel.agent.api.common.TerminationScope
import com.embabel.agent.api.common.TerminationSignal
import com.embabel.agent.api.termination.TerminationSignalPolicy
import com.embabel.agent.api.tool.TerminateActionException
import com.embabel.agent.api.tool.TerminateAgentException
import com.embabel.agent.api.common.PlatformServices
import com.embabel.agent.api.common.StuckHandlingResultCode
import com.embabel.agent.api.common.ToolsStats
import com.embabel.agent.api.event.*
import com.embabel.agent.core.*
import com.embabel.agent.core.AgentProcess.Companion.withCurrent
import com.embabel.agent.spi.DelayedActionExecutionSchedule
import com.embabel.agent.spi.ProntoActionExecutionSchedule
import com.embabel.agent.spi.ScheduledActionExecutionSchedule
import com.embabel.agent.spi.support.AgenticEventListenerToolsStats
import com.embabel.common.ai.model.EmbeddingServiceMetadata
import com.embabel.common.ai.model.LlmMetadata
import com.embabel.plan.Goal as PlanGoal
import com.embabel.plan.PlanningSystem
import com.embabel.plan.WorldState
import com.embabel.plan.common.condition.ConditionPlanningSystem
import com.embabel.plan.common.condition.WorldStateDeterminer
import com.fasterxml.jackson.annotation.JsonIgnore
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicReference

/**
 * Abstract implementation of AgentProcess that provides common functionality
 */
abstract class AbstractAgentProcess(
    override val id: String,
    override val parentId: String?,
    override val agent: Agent,
    override val processOptions: ProcessOptions,
    override val blackboard: Blackboard,
    @get:JsonIgnore
    protected val platformServices: PlatformServices,
    override val timestamp: Instant = Instant.now(),
) : AgentProcess, ProcessCancellationTokenProvider, Blackboard by blackboard {

    protected val logger: Logger = LoggerFactory.getLogger(javaClass)

    private var _lastWorldState: WorldState? = null

    protected var _goal: com.embabel.plan.Goal? = null

    private val _history: MutableList<ActionInvocation> = CopyOnWriteArrayList()

    private val _status = AtomicReference(AgentProcessStatusCode.NOT_STARTED)

    private var _failureInfo: Any? = null

    override val failureInfo: Any?
        get() = _failureInfo

    private var _outcome: ProcessOutcome = ProcessOutcome()

    override val outcome: ProcessOutcome
        get() = _outcome

    private val _terminationRequest = AtomicReference<TerminationSignal?>(null)

    internal val terminationRequest: TerminationSignal?
        get() = _terminationRequest.get()

    private fun setTerminationRequest(signal: TerminationSignal) {
        _terminationRequest.set(signal)
    }

    /**
     * Atomically clear the slot only if its current value is exactly [expected].
     * Returns true if the caller successfully consumed the observed signal.
     * Returns false if the slot was mutated concurrently — the newer value
     * is preserved and will be visible to the next consumer.
     */
    internal fun compareAndResetTerminationRequest(expected: TerminationSignal): Boolean =
        _terminationRequest.compareAndSet(expected, null)

    // Agenda and outcome state is process-thread confined. External publish() calls
    // only touch pending ingress under lock plus atomic status/termination signals.
    private var _goalAgenda: GoalAgenda = GoalAgenda.EMPTY

    private val activatedAgendaEntryIds = mutableSetOf<String>()

    private val completedAgendaGoals = mutableSetOf<Goal>()

    override val goalAgenda: GoalAgenda
        get() = _goalAgenda

    override val cancellationToken: ProcessCancellationToken = object : ProcessCancellationToken {
        override val isCancellationRequested: Boolean
            get() = terminationRequest != null ||
                    status == AgentProcessStatusCode.TERMINATED ||
                    status == AgentProcessStatusCode.KILLED

        override val reason: String?
            get() = terminationRequest?.reason
    }

    private data class PendingBlackboardIngress(
        val fact: Any,
        val receipt: IngressReceipt,
    )

    private data class ActiveBlackboardIngress(
        val fact: Any,
        val key: String,
        val expiresAt: Instant?,
        val receipt: IngressReceipt,
    )

    private val pendingIngressLock = Any()

    private val pendingIngress = mutableListOf<PendingBlackboardIngress>()

    private val activeIngress = CopyOnWriteArrayList<ActiveBlackboardIngress>()

    override val ingress: BlackboardIngress = object : BlackboardIngress {

        override fun publish(
            fact: Any,
            options: IngressOptions,
        ): IngressReceipt = publishIngress(fact, options)
    }

    private fun publishIngress(
        fact: Any,
        options: IngressOptions,
    ): IngressReceipt {
        val receipt = IngressReceipt(
            id = UUID.randomUUID().toString(),
            factType = fact.javaClass.name,
            publishedAt = Instant.now(),
            options = options,
        )
        synchronized(pendingIngressLock) {
            options.coalesceKey?.let { coalesceKey ->
                pendingIngress.removeIf { it.receipt.options.coalesceKey == coalesceKey }
            }
            pendingIngress += PendingBlackboardIngress(
                fact = fact,
                receipt = receipt,
            )
        }
        platformServices.eventListener.onProcessEvent(
            BlackboardIngressPublishedEvent(
                agentProcess = this,
                receipt = receipt,
                fact = fact,
            )
        )
        if (options.wake != IngressWake.NONE) {
            wakeIfBlocked()
        }
        if (options.wake == IngressWake.SAFETY_PREEMPT) {
            terminateAction("Safety ingress published: ${receipt.factType}")
        }
        return receipt
    }

    private fun wakeIfBlocked() {
        while (true) {
            val currentStatus = _status.get()
            when (currentStatus) {
                AgentProcessStatusCode.WAITING,
                AgentProcessStatusCode.STUCK,
                AgentProcessStatusCode.PAUSED,
                    -> if (_status.compareAndSet(currentStatus, AgentProcessStatusCode.RUNNING)) {
                    return
                }

                else -> return
            }
        }
    }

    private fun drainIngress() {
        val now = Instant.now()
        expireIngress(now)
        val toDrain = synchronized(pendingIngressLock) {
            pendingIngress.toList().also {
                pendingIngress.clear()
            }
        }
        toDrain.forEach { drainIngress(it, now) }
        expireIngress(now)
    }

    private fun drainIngress(pending: PendingBlackboardIngress, now: Instant) {
        val options = pending.receipt.options
        val key = ingressKey(pending.fact, options)
        if (options.mode == IngressMode.LATEST) {
            hideVisibleIngress(
                key = key,
                reason = BlackboardIngressHideReason.LATEST_REPLACED,
                replacementReceipt = pending.receipt,
            )
        }
        addObject(pending.fact)
        options.activationKey?.let {
            blackboard.setCondition(it, true)
            activateAgendaEntries(
                activationKey = it,
                sourceFact = pending.fact,
            )
        }
        activeIngress += ActiveBlackboardIngress(
            fact = pending.fact,
            key = key,
            expiresAt = options.ttl?.let { now.plus(it) },
            receipt = pending.receipt,
        )
        platformServices.eventListener.onProcessEvent(
            BlackboardIngressDrainedEvent(
                agentProcess = this,
                receipt = pending.receipt,
                fact = pending.fact,
            )
        )
    }

    private fun activateAgendaEntries(
        activationKey: String?,
        sourceFact: Any?,
    ) {
        val now = Instant.now()
        processOptions.evolution.agendaCatalog.entries
            .filter { it.activationKey == activationKey }
            .filterNot { it.isExpired(now) }
            .forEach {
                activateAgendaEntry(
                    entry = it,
                    sourceFact = sourceFact,
                    rememberActivation = activationKey == null,
                )
            }
    }

    private fun activateAgendaEntry(
        entry: AgendaEntry,
        sourceFact: Any?,
        rememberActivation: Boolean,
    ): AgendaEntryApprovalResponse {
        val request = AgendaEntryApprovalRequest(
            entry = entry,
            sourceFact = sourceFact,
            sourceType = sourceFact?.javaClass?.name,
            lane = entry.lane,
            bindings = entry.bindings,
            currentAgenda = _goalAgenda,
            agentProcess = this,
        )
        if (rememberActivation && activatedAgendaEntryIds.contains(entry.id)) {
            return AgendaEntryNotApproved(
                request = request,
                reason = "Agenda entry ${entry.id} was already activated",
            )
        }
        if (_goalAgenda.entries.any { it.id == entry.id }) {
            return AgendaEntryNotApproved(
                request = request,
                reason = "Agenda entry ${entry.id} is already active",
            )
        }
        val response = processOptions.evolution.agendaEntryApprover.approve(request)
        if (response.approved) {
            _goalAgenda = _goalAgenda.withEntry(entry)
            if (rememberActivation) {
                activatedAgendaEntryIds += entry.id
            }
        }
        return response
    }

    override fun addAgendaEntry(
        entry: AgendaEntry,
        sourceFact: Any?,
    ): AgendaEntryApprovalResponse =
        activateAgendaEntry(
            entry = entry,
            sourceFact = sourceFact,
            rememberActivation = false,
        )

    private fun expireAgenda(now: Instant) {
        _goalAgenda = _goalAgenda.expire(now)
    }

    private fun shouldRunEvolutionCycle(): Boolean {
        val evolution = processOptions.evolution
        return _goalAgenda.entries.isNotEmpty() ||
                activeIngress.isNotEmpty() ||
                hasPendingIngress() ||
                evolution.agendaCatalog.entries.isNotEmpty() ||
                evolution.completionPolicy !== CompletionPolicy.CONTINUE
    }

    private fun hasPendingIngress(): Boolean =
        synchronized(pendingIngressLock) {
            pendingIngress.isNotEmpty()
        }

    protected fun effectivePlanningSystem(): PlanningSystem {
        val activeEntries = if (goalAgenda.entries.any { it.lane == AgendaLane.SAFETY }) {
            goalAgenda.entries.filter { it.lane == AgendaLane.SAFETY }
        } else {
            goalAgenda.entries
        }
        val activeGoals = activeEntries.map { AgendaPlanningGoal(it) }.toSet()
        return if (activeGoals.isEmpty()) {
            basePlanningSystem()
        } else {
            ConditionPlanningSystem(
                actions = agent.actions.toSet(),
                goals = activeGoals,
            )
        }
    }

    private fun basePlanningSystem(): PlanningSystem {
        if (completedAgendaGoals.isEmpty()) {
            return agent.planningSystem
        }
        val base = agent.planningSystem
        return object : PlanningSystem {
            override val actions = base.actions
            override val goals = base.goals
                .filterNot { goal -> completedAgendaGoals.any { completedGoal -> completedGoal == goal } }
                .toSet()

            override fun knownConditions(): Set<String> = base.knownConditions()

            override fun infoString(
                verbose: Boolean?,
                indent: Int,
            ): String = base.infoString(verbose, indent)
        }
    }

    private fun ingressKey(
        fact: Any,
        options: IngressOptions,
    ): String = options.coalesceKey ?: fact.javaClass.name

    private fun expireIngress(now: Instant) {
        activeIngress
            .filter { it.expiresAt != null && !it.expiresAt.isAfter(now) }
            .forEach {
                hideIngress(
                    ingress = it,
                    reason = BlackboardIngressHideReason.TTL_EXPIRED,
                    replacementReceipt = null,
                )
            }
    }

    private fun hideVisibleIngress(
        key: String,
        reason: BlackboardIngressHideReason,
        replacementReceipt: IngressReceipt?,
    ) {
        val visibleObjects = blackboard.objects
        activeIngress
            .filter { it.key == key && visibleObjects.contains(it.fact) }
            .forEach {
                hideIngress(
                    ingress = it,
                    reason = reason,
                    replacementReceipt = replacementReceipt,
                )
            }
    }

    private fun hideIngress(
        ingress: ActiveBlackboardIngress,
        reason: BlackboardIngressHideReason,
        replacementReceipt: IngressReceipt?,
    ) {
        blackboard.hide(ingress.fact)
        activeIngress.remove(ingress)
        platformServices.eventListener.onProcessEvent(
            BlackboardIngressHiddenEvent(
                agentProcess = this,
                receipt = ingress.receipt,
                fact = ingress.fact,
                reason = reason,
                replacementReceipt = replacementReceipt,
            )
        )
    }

    protected fun applyCompletionPolicy(): Boolean =
        applyProcessOutcome(processOptions.evolution.completionPolicy.evaluate(this, goalAgenda))

    protected fun hasExhaustedAgenda(): Boolean =
        _goalAgenda.entries.isEmpty() && completedAgendaGoals.isNotEmpty()

    protected fun exhaustAgenda(reason: String): Boolean =
        applyProcessOutcome(
            ProcessOutcome(
                code = ProcessOutcomeCode.EXHAUSTED,
                reason = reason,
            )
        )

    protected fun applyProcessOutcome(outcome: ProcessOutcome): Boolean {
        _outcome = outcome
        return when (outcome.code) {
            ProcessOutcomeCode.CONTINUE -> false
            ProcessOutcomeCode.COMPLETED -> {
                setStatus(AgentProcessStatusCode.COMPLETED)
                true
            }

            ProcessOutcomeCode.EXHAUSTED -> {
                _failureInfo = outcome
                setStatus(AgentProcessStatusCode.TERMINATED)
                true
            }

            ProcessOutcomeCode.CANCELLED -> {
                _failureInfo = outcome
                setStatus(AgentProcessStatusCode.TERMINATED)
                true
            }
        }
    }

    protected fun statusAfterGoalAchieved(goal: PlanGoal): AgentProcessStatusCode {
        val agendaEntry = (goal as? AgendaPlanningGoal)?.entry
            ?: _goalAgenda.entries.firstOrNull { it.goal == goal }
        if (agendaEntry == null) {
            _outcome = ProcessOutcome(
                code = ProcessOutcomeCode.COMPLETED,
                reason = "Goal ${goal.name} completed",
                goal = goal as? Goal,
            )
            return AgentProcessStatusCode.COMPLETED
        }
        return when (agendaEntry.completionMode) {
            AgendaCompletionMode.TERMINAL -> {
                _outcome = ProcessOutcome(
                    code = ProcessOutcomeCode.COMPLETED,
                    reason = "Agenda entry ${agendaEntry.id} completed",
                    goal = agendaEntry.goal,
                )
                AgentProcessStatusCode.COMPLETED
            }

            AgendaCompletionMode.COMPOSITE_TERMINAL -> {
                if (agendaEntry.completionPredicate?.isComplete(this, agendaEntry, _goalAgenda) == true) {
                    _outcome = ProcessOutcome(
                        code = ProcessOutcomeCode.COMPLETED,
                        reason = "Composite agenda entry ${agendaEntry.id} completed",
                        goal = agendaEntry.goal,
                    )
                    AgentProcessStatusCode.COMPLETED
                } else {
                    _outcome = ProcessOutcome(
                        code = ProcessOutcomeCode.CONTINUE,
                        reason = "Composite agenda entry ${agendaEntry.id} is waiting for completion predicate",
                        goal = agendaEntry.goal,
                    )
                    AgentProcessStatusCode.WAITING
                }
            }

            AgendaCompletionMode.RESUMABLE -> {
                _goalAgenda = _goalAgenda.withoutEntry(agendaEntry.id)
                completedAgendaGoals += agendaEntry.goal
                _outcome = ProcessOutcome(
                    code = ProcessOutcomeCode.CONTINUE,
                    reason = "Agenda entry ${agendaEntry.id} completed; continuing",
                    goal = agendaEntry.goal,
                )
                AgentProcessStatusCode.RUNNING
            }

            AgendaCompletionMode.KEEP_ALIVE -> {
                _outcome = ProcessOutcome(
                    code = ProcessOutcomeCode.CONTINUE,
                    reason = "Agenda entry ${agendaEntry.id} is keeping the process alive",
                    goal = agendaEntry.goal,
                )
                AgentProcessStatusCode.WAITING
            }
        }
    }

    override fun terminateAgent(reason: String) {
        // Cascade to children first
        val children = platformServices.agentProcessRepository.findByParentId(id)
        children.forEach { child ->
            logger.debug("Terminating child process {} of {}", child.id, id)
            child.terminateAgent(reason)
        }

        // Exhaustive when - compile error if new status added
        @Suppress(names=["UNUSED_VARIABLE"])
        val _forceExhaustive = when (status) {
            AgentProcessStatusCode.RUNNING,
            AgentProcessStatusCode.NOT_STARTED -> {
                // Will reach checkpoint - set signal for deferred termination
                setTerminationRequest(TerminationSignal(TerminationScope.AGENT, reason))
            }
            AgentProcessStatusCode.KILLED,
            AgentProcessStatusCode.FAILED,
            AgentProcessStatusCode.TERMINATED -> {
                // Already in terminal state - ignore
                logger.info("Process {} already {}, ignoring terminate request", id, status)
            }
            AgentProcessStatusCode.COMPLETED,
            AgentProcessStatusCode.STUCK,
            AgentProcessStatusCode.WAITING,
            AgentProcessStatusCode.PAUSED -> {
                // No guaranteed next tick - set status immediately
                logger.info("Terminating process {} (was {}): {}", id, status, reason)
                setStatus(AgentProcessStatusCode.TERMINATED)
            }
        }
    }

    override fun terminateAction(reason: String) {
        setTerminationRequest(TerminationSignal(TerminationScope.ACTION, reason))
    }

    override val lastWorldState: WorldState?
        get() = _lastWorldState

    private val agenticEventListenerToolsStats = AgenticEventListenerToolsStats()

    override val goal: com.embabel.plan.Goal? get() = _goal

    override val processContext = ProcessContext(
        platformServices = platformServices.withEventListener(
            agenticEventListenerToolsStats,
        ),
        agentProcess = this,
        processOptions = processOptions,
        outputChannel = platformServices.outputChannel + processOptions.outputChannel,
    )

    /**
     * Get the WorldStateDeterminer for this process
     */
    protected abstract val worldStateDeterminer: WorldStateDeterminer

    private val _llmInvocations: MutableList<LlmInvocation> = CopyOnWriteArrayList()

    override val llmInvocations: List<LlmInvocation>
        get() = _llmInvocations.toList()

    override fun recordLlmInvocation(llmInvocation: LlmInvocation) {
        _llmInvocations.add(llmInvocation)
    }

    private val _embeddingInvocations: MutableList<EmbeddingInvocation> = CopyOnWriteArrayList()

    override val embeddingInvocations: List<EmbeddingInvocation>
        get() = _embeddingInvocations.toList()

    override fun recordEmbeddingInvocation(invocation: EmbeddingInvocation) {
        _embeddingInvocations.add(invocation)
    }

    // --- Subtree aggregation (fix #368) ----------------------------------------
    //
    // When an AgentProcess spawns child processes, cost/usage/models reporting
    // must reflect the *entire* subtree, not just this process's own LLM calls.
    // Each override below follows the same pattern:
    //     result = <own contribution> + Σ child.<same method>()
    // The recursion walks the tree via `childProcesses()`, which resolves
    // direct children at call time through the AgentProcess repository.

    /**
     * Direct children of this process (non-recursive).
     * Resolved on every call — not cached — so children spawned later are picked up.
     */
    private fun childProcesses(): List<AgentProcess> =
        platformServices.agentProcessRepository.findByParentId(id)

    /** LLM cost = own LLM cost + sum of each child's LLM cost (recursive). */
    override fun cost(): Double =
        ownCost() + childProcesses().sumOf { it.cost() }

    /**
     * LLM token usage aggregated across the whole subtree.
     * Nulls from children with no invocations are coerced to 0.
     */
    override fun usage(): Usage {
        val own = ownUsage()
        val children = childProcesses()
        return Usage(
            (own.promptTokens ?: 0) + children.sumOf { it.usage().promptTokens ?: 0 },
            (own.completionTokens ?: 0) + children.sumOf { it.usage().completionTokens ?: 0 },
            null,
        )
    }

    /**
     * Distinct LLMs used anywhere in the subtree, sorted by name.
     * `distinctBy { it.name }` removes duplicates when the same model is used
     * at multiple levels.
     */
    override fun modelsUsed(): List<LlmMetadata> =
        (ownModelsUsed() + childProcesses().flatMap { it.modelsUsed() })
            .distinctBy { it.name }.sortedBy { it.name }

    /** Embedding cost = own + sum of each child's embedding cost (recursive). */
    override fun embeddingCost(): Double =
        embeddingInvocations.sumOf { it.cost() } + childProcesses().sumOf { it.embeddingCost() }

    /** Embedding usage aggregated across the whole subtree. */
    override fun embeddingUsage(): Usage {
        val ownPrompt = embeddingInvocations.sumOf { it.usage.promptTokens ?: 0 }
        val children = childProcesses()
        return Usage(
            ownPrompt + children.sumOf { it.embeddingUsage().promptTokens ?: 0 },
            null,
            null,
        )
    }

    /** Distinct embedding services used anywhere in the subtree, sorted by name. */
    override fun embeddingModelsUsed(): List<EmbeddingServiceMetadata> =
        (embeddingInvocations.map { it.embeddingMetadata } +
                childProcesses().flatMap { it.embeddingModelsUsed() })
            .distinctBy { it.name }.sortedBy { it.name }

    /** Total LLM call count across the whole subtree. */
    override fun llmInvocationCount(): Int =
        llmInvocations.size + childProcesses().sumOf { it.llmInvocationCount() }

    /** Total embedding call count across the whole subtree. */
    override fun embeddingInvocationCount(): Int =
        embeddingInvocations.size + childProcesses().sumOf { it.embeddingInvocationCount() }

    override val status: AgentProcessStatusCode
        get() = _status.get()

    override val history: List<ActionInvocation>
        get() = _history.toList()

    override val toolsStats: ToolsStats
        get() = agenticEventListenerToolsStats

    protected fun setStatus(status: AgentProcessStatusCode) {
        _status.set(status)
    }

    override fun kill(): ProcessKilledEvent? {
        // Kill child processes first (recursive)
        val children = platformServices.agentProcessRepository.findByParentId(id)
        children.forEach { child ->
            logger.debug("Killing child process {} of {}", child.id, id)
            child.kill()
        }

        setStatus(AgentProcessStatusCode.KILLED)
        return ProcessKilledEvent(this)
    }

    override fun bind(
        key: String,
        value: Any,
    ): Bindable {
        blackboard[key] = value
        processContext.onProcessEvent(
            ObjectBoundEvent(
                agentProcess = this,
                name = key,
                value = value,
            )
        )
        return this
    }

    override fun plusAssign(pair: Pair<String, Any>) {
        bind(pair.first, pair.second)
    }

    // Override set to bind so that delegation works
    override operator fun set(
        key: String,
        value: Any,
    ) {
        bind(key, value)
    }

    override fun addObject(value: Any): Bindable {
        blackboard.addObject(value)
        processContext.onProcessEvent(
            ObjectAddedEvent(
                agentProcess = this,
                value = value,
            )
        )
        return this
    }

    override operator fun plusAssign(value: Any) {
        addObject(value)
    }

    private fun makeRunning(): Boolean {
        val currentStatus = _status.get()
        return when (currentStatus) {
            AgentProcessStatusCode.COMPLETED,
            AgentProcessStatusCode.KILLED, AgentProcessStatusCode.TERMINATED,
                -> {
                logger.warn("Process {} Cannot be made RUNNING as its status is {}", this.id, status)
                return false
            }

            else -> {
                _status.compareAndSet(currentStatus, AgentProcessStatusCode.RUNNING)
                true
            }
        }
    }

    override fun run(): AgentProcess {
        if (!makeRunning()) {
            return this
        }

        if (
            agent.goals.isEmpty() &&
            processOptions.evolution.agendaCatalog.entries.isEmpty() &&
            processOptions.plannerType.needsGoals
        ) {
            logger.info("🛑 Process {} has no goals: {}", this.id, agent.goals)
            error("Agent ${agent.name} has no goals: ${agent.infoString(verbose = true)}")
        }

        var replanAfterStuckHandling: Boolean
        do {
            replanAfterStuckHandling = false
            tick()
            while (status == AgentProcessStatusCode.RUNNING) {
                val earlyTermination = identifyEarlyTermination()
                if (earlyTermination != null) {
                    return this
                }
                tick()
            }
            when (status) {
                AgentProcessStatusCode.NOT_STARTED -> {
                    logger.debug("Process {} is not started: {}", this.id, status)
                }

                AgentProcessStatusCode.RUNNING -> {
                    logger.debug("Process {} is happily running: {}", this.id, status)
                }

                AgentProcessStatusCode.COMPLETED -> {
                    platformServices.eventListener.onProcessEvent(AgentProcessCompletedEvent(this))
                }

                AgentProcessStatusCode.FAILED -> {
                    platformServices.eventListener.onProcessEvent(AgentProcessFailedEvent(this))
                }

                AgentProcessStatusCode.TERMINATED -> {
                    platformServices.eventListener.onProcessEvent(AgentProcessTerminatedEvent(this))
                }

                AgentProcessStatusCode.KILLED -> {
                    // Event will have been raised at the point of kill
                }

                AgentProcessStatusCode.WAITING -> {
                    platformServices.eventListener.onProcessEvent(AgentProcessWaitingEvent(this))
                }

                AgentProcessStatusCode.PAUSED -> {
                    platformServices.eventListener.onProcessEvent(AgentProcessPausedEvent(this))
                    replanAfterStuckHandling = handleStuck(agent)
                }

                AgentProcessStatusCode.STUCK -> {
                    platformServices.eventListener.onProcessEvent(AgentProcessStuckEvent(this))
                    replanAfterStuckHandling = handleStuck(agent)
                }
            }
            if (replanAfterStuckHandling) {
                val earlyTermination = identifyEarlyTermination()
                if (earlyTermination != null) {
                    return this
                }
                setStatus(AgentProcessStatusCode.RUNNING)
            }
        } while (replanAfterStuckHandling)
        return this
    }

    /**
     * Should this process be terminated early?
     * Also clears any pending termination requests after processing.
     */
    protected fun identifyEarlyTermination(): EarlyTermination? {
        // Check for API-driven termination signal first
        val signalTermination = TerminationSignalPolicy.shouldTerminate(this)
        if (signalTermination != null) {
            val observed = terminationRequest
            if (observed != null) compareAndResetTerminationRequest(observed)
            logger.debug(
                "Process {} terminated by termination signal: {}",
                this.id,
                signalTermination.reason,
            )
            platformServices.eventListener.onProcessEvent(signalTermination)
            _failureInfo = signalTermination
            setStatus(AgentProcessStatusCode.TERMINATED)
            return signalTermination
        }

        // Clear any stale ACTION signal that wasn't consumed by tool loop
        // (e.g., set by a simple action without tool loop)
        val staleSignal = terminationRequest
        if (staleSignal != null && staleSignal.scope == TerminationScope.ACTION) {
            if (compareAndResetTerminationRequest(staleSignal)) {
                logger.debug("Clearing stale ACTION termination signal: {}", staleSignal.reason)
            }
        }

        // Check configured early termination policies
        val earlyTermination = processOptions.processControl.earlyTerminationPolicy.shouldTerminate(this)
        if (earlyTermination != null) {
            logger.debug(
                "Process {} terminated by {} because {}",
                this.id,
                earlyTermination.policy,
                earlyTermination.reason,
            )
            platformServices.eventListener.onProcessEvent(earlyTermination)
            _failureInfo = earlyTermination
            setStatus(AgentProcessStatusCode.TERMINATED)
            return earlyTermination
        }
        return null
    }

    /**
     * Try to resolve a stuck process using StuckHandler if provided
     */
    protected fun handleStuck(agent: Agent): Boolean {
        val stuckHandler = agent.stuckHandler
        if (stuckHandler == null) {
            if (processOptions.plannerType.needsGoals) {
                logger.warn(
                    "Process {} is stuck with no StuckHandler. This may or may not be an error. History ({}):\n\t{}",
                    this.id,
                    history.size,
                    history.joinToString("\n\t") { it.actionName },
                )
            } else {
                // This is not an error. It's a common state for chatbots, for example.
                logger.debug("Process {} is paused, with no available actions", this.id)
            }
            return false
        }
        val result = stuckHandler.handleStuck(this)
        platformServices.eventListener.onProcessEvent(result)
        return when (result.code) {
            StuckHandlingResultCode.REPLAN -> {
                if (finished) {
                    logger.info("Process {} is {} during stuck handling, will not replan", this.id, status)
                    false
                } else {
                    logger.info("Process {} unstuck and will replan: {}", this.id, result.message)
                    true
                }
            }

            StuckHandlingResultCode.NO_RESOLUTION -> {
                logger.warn("Process {} stuck: {}", this.id, result.message)
                setStatus(AgentProcessStatusCode.STUCK)
                false
            }
        }
    }

    override fun tick(): AgentProcess {
        if (!makeRunning()) {
            return this
        }

        if (shouldRunEvolutionCycle()) {
            expireAgenda(Instant.now())
            activateAgendaEntries(
                activationKey = null,
                sourceFact = null,
            )
            drainIngress()
            if (applyCompletionPolicy()) {
                platformServices.agentProcessRepository.update(this)
                return this
            }
        }
        val worldState = worldStateDeterminer.determineWorldState()
        _lastWorldState = worldState
        platformServices.eventListener.onProcessEvent(
            AgentProcessReadyToPlanEvent(
                agentProcess = this,
                worldState = worldState,
            )
        )
        logger.debug(
            "Process {} tick (about to plan): {}, blackboard={}",
            id,
            worldState,
            blackboard.infoString(verbose = false),
        )

        // Let subclasses handle the planning and execution
        return formulateAndExecutePlan(worldState)
            .apply {
                platformServices.agentProcessRepository.update(this)
            }
    }


    /**
     * Execute the plan based on the current world state
     * @param worldState The current world state
     */
    protected abstract fun formulateAndExecutePlan(
        worldState: WorldState,
    ): AgentProcess

    /**
     * Execute an action
     */
    protected fun executeAction(action: Action): ActionStatus {
        val outputTypes: Map<String, DomainType> =
            action.outputs.associateBy({ it.name }, { agent.resolveType(it.type) })
        logger.debug(
            "⚙️ Process {} executing action {}: outputTypes={}",
            id,
            action.name,
            outputTypes,
        )

        val actionExecutionStartEvent = ActionExecutionStartEvent(
            agentProcess = this,
            action = action,
        )
        platformServices.eventListener.onProcessEvent(actionExecutionStartEvent)
        val actionExecutionSchedule = platformServices.operationScheduler.scheduleAction(actionExecutionStartEvent)
        when (actionExecutionSchedule) {
            is ProntoActionExecutionSchedule -> {
                // Do nothing
            }

            is DelayedActionExecutionSchedule -> {
                // Delay and move on
                logger.debug("Process {} delayed action {}: {}", id, action.name, actionExecutionSchedule)
                try {
                    Thread.sleep(actionExecutionSchedule.delay.toMillis())
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    _status.set(AgentProcessStatusCode.TERMINATED)
                    return ActionStatus(
                        runningTime = Duration.between(actionExecutionStartEvent.timestamp, Instant.now()),
                        status = ActionStatusCode.FAILED,
                    )
                }
                logger.debug("Process {} delayed action {}: done", id, action.name)
            }

            is ScheduledActionExecutionSchedule -> {
                return ActionStatus(
                    Duration.between(actionExecutionStartEvent.timestamp, Instant.now()),
                    ActionStatusCode.PAUSED
                )
            }
        }

        // Capture blackboard state before execution to detect if it was cleared
        val blackboardObjectsBefore = blackboard.objects.toList()

        val timestamp = Instant.now()
        val actionStatus = try {
            withCurrent {
                val effectiveAction = action.withEffectiveQos(platformServices.actionQosProperties())
                effectiveAction.qos
                    .retryTemplate("Action-${action.name}")
                    .execute<ActionStatus, Throwable> { context ->
                        // Clear effect conditions on retry (not first attempt)
                        if (context.retryCount > 0) {
                            logger.debug(
                                "Retry attempt {} for action {}, clearing effect conditions",
                                context.retryCount,
                                action.name
                            )
                            action.effects.forEach { (condition, _) ->
                                blackboard.setCondition(condition, false)
                            }
                        }

                        effectiveAction.execute(
                            processContext = processContext,
                        )
                    }
            }
        } catch (e: TerminateActionException) {
            logger.info("Action {} terminated early: {}", action.name, e.reason)
            ActionStatus(Duration.between(timestamp, Instant.now()), ActionStatusCode.TERMINATED)
        } catch (e: TerminateAgentException) {
            logger.info("Action {} requested agent termination: {}", action.name, e.reason)
            ActionStatus(Duration.between(timestamp, Instant.now()), ActionStatusCode.AGENT_TERMINATED)
        }
        val actionStatusAfterCancellation = actionStatusAfterCooperativeActionTermination(actionStatus, timestamp)
        val runningTime = Duration.between(timestamp, Instant.now())
        _history += ActionInvocation(
            actionName = action.name,
            timestamp = timestamp,
            runningTime = runningTime,
        )

        // Set hasRun condition on blackboard after action execution.
        // This must be set for ALL actions (not just canRerun=false) because other
        // actions may depend on hasRun as a precondition (e.g., aggregate actions).
        // The canRerun flag controls whether hasRun=FALSE is a precondition, not
        // whether to track that the action ran.
        // Only set if the blackboard wasn't cleared during execution.
        // For state-clearing actions, the blackboard reset naturally prevents re-runs
        // since inputs are gone. Setting hasRun on the NEW state's blackboard would
        // incorrectly block actions that haven't run in the new state.
        val blackboardWasCleared = blackboard.objects.none { it in blackboardObjectsBefore }
        if (!blackboardWasCleared) {
            blackboard.setCondition(Rerun.hasRunCondition(action), true)
        }

        platformServices.eventListener.onProcessEvent(
            actionExecutionStartEvent.resultEvent(
                actionStatus = actionStatusAfterCancellation,
            )
        )

        logger.debug("New world state: {}", worldStateDeterminer.determineWorldState())
        return actionStatusAfterCancellation
    }

    private fun actionStatusAfterCooperativeActionTermination(
        actionStatus: ActionStatus,
        timestamp: Instant,
    ): ActionStatus {
        if (actionStatus.status != ActionStatusCode.SUCCEEDED) {
            return actionStatus
        }
        val signal = terminationRequest
        return if (signal != null &&
            signal.scope == TerminationScope.ACTION &&
            compareAndResetTerminationRequest(signal)
        ) {
            logger.info("Action cooperatively observed termination signal: {}", signal.reason)
            ActionStatus(Duration.between(timestamp, Instant.now()), ActionStatusCode.TERMINATED)
        } else {
            actionStatus
        }
    }

    /**
     * Convert action status to agent process status
     */
    protected fun actionStatusToAgentProcessStatus(actionStatus: ActionStatus): AgentProcessStatusCode {
        return when (actionStatus.status) {
            ActionStatusCode.SUCCEEDED -> {
                logger.debug("Process {} action {} is running", id, actionStatus.status)
                AgentProcessStatusCode.RUNNING
            }

            ActionStatusCode.FAILED -> {
                logger.debug("❌ Process {} action {} failed", id, actionStatus.status)
                AgentProcessStatusCode.FAILED
            }

            ActionStatusCode.WAITING -> {
                logger.debug("⏳ Process {} action {} waiting", id, actionStatus.status)
                AgentProcessStatusCode.WAITING
            }

            ActionStatusCode.PAUSED -> {
                logger.debug("⏳ Process {} action {} paused", id, actionStatus.status)
                AgentProcessStatusCode.PAUSED
            }

            ActionStatusCode.TERMINATED -> {
                logger.debug("Process {} action terminated early, continuing", id)
                AgentProcessStatusCode.RUNNING
            }

            ActionStatusCode.AGENT_TERMINATED -> {
                logger.debug("Process {} action requested agent termination", id)
                AgentProcessStatusCode.TERMINATED
            }
        }
    }
}
