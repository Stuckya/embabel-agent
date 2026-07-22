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

import com.embabel.agent.api.common.PlatformServices
import com.embabel.agent.api.event.AgentProcessPlanFormulatedEvent
import com.embabel.agent.api.event.EpisodeCompletedEvent
import com.embabel.agent.api.event.GoalAchievedEvent
import com.embabel.agent.api.event.ReplanRequestedEvent
import com.embabel.agent.api.tool.TerminateActionException
import com.embabel.agent.api.tool.TerminateAgentException
import com.embabel.agent.api.tool.ToolControlFlowSignal
import com.embabel.agent.core.Action
import com.embabel.agent.core.ActionStatus
import com.embabel.agent.core.Agent
import com.embabel.agent.core.AgentProcess
import com.embabel.agent.core.AgentProcessStatusCode
import com.embabel.agent.core.Blackboard
import com.embabel.agent.core.ProcessOptions
import com.embabel.agent.core.ReplanRequestedException
import com.embabel.agent.spi.PlannerFactory
import com.embabel.common.util.indentLines
import com.embabel.plan.Plan
import com.embabel.plan.Planner
import com.embabel.plan.WorldState
import com.embabel.plan.common.condition.WorldStateDeterminer
import java.time.Instant
import java.util.Collections
import java.util.IdentityHashMap

open class SimpleAgentProcess(
    id: String,
    parentId: String?,
    agent: Agent,
    processOptions: ProcessOptions,
    blackboard: Blackboard,
    platformServices: PlatformServices,
    plannerFactory: PlannerFactory,
    timestamp: Instant = Instant.now(),
) : AbstractAgentProcess(
    id = id,
    parentId = parentId,
    agent = agent,
    processOptions = processOptions,
    blackboard = blackboard,
    platformServices = platformServices,
    timestamp = timestamp,
) {

    override val worldStateDeterminer: WorldStateDeterminer = BlackboardWorldStateDeterminer(
        processContext = processContext,
        logicalExpressionParser = platformServices.logicalExpressionParser,
    )

    override val planner: Planner<*, *, *> = plannerFactory.createPlanner(processOptions, worldStateDeterminer)

    /**
     * Episode policy resolved against the process scope.
     * Resolution validates the policy, so invalid configuration fails here at construction.
     */
    private val resolvedEpisodes: List<ResolvedEpisodeRule> =
        EpisodeResolution.resolve(processOptions.episodes, agent)

    /**
     * Actions to exclude from the next planning cycle.
     * Used to prevent infinite loops when an action requests replan but
     * would be the only applicable action again.
     * Cleared after each successful planning cycle.
     */
    protected val replanBlacklist = mutableSetOf<String>()

    /**
     * Serial admission: each arriving occurrence becomes an [Episode], and
     * at most one Episode per rule is ACTIVE. Later arrivals wait PENDING,
     * hidden and queued FIFO, admitted when the active episode completes.
     * A pending request never changes type-level conditions because the
     * active request of the same type stays visible.
     */
    private val admissionSeen: MutableSet<Any> =
        Collections.newSetFromMap(IdentityHashMap())

    /**
     * Instances published as occurrences, each mapped to the episode whose
     * chain action published it, if any: lineage captured at publication.
     * Designation rides the instance: an evolved fact is admitted to drive
     * an episode, while a plain addObject fact of the same type is
     * standing state.
     */
    private val evolvedArrivals: MutableMap<Any, EvolveOrigin> = IdentityHashMap()

    private data class EvolveOrigin(val causedBy: Episode?, val publishedBy: String?)

    /** The episode whose chain action is currently executing, if any */
    private var executingEpisode: Episode? = null

    /** The name of the action currently executing, if any */
    private var executingAction: String? = null

    /**
     * The most recently completed episode, retained for lineage inspection.
     * Completed episodes are otherwise discarded.
     */
    internal var lastCompletedEpisode: Episode? = null
        private set

    /** The active episode for the rule owning the given goal, for inspection */
    internal fun episodeFor(goalName: String): Episode? =
        resolvedEpisodes.firstOrNull { it.matches(goalName) }?.let { activeEpisodes[it] }
    private val pendingEpisodes = mutableMapOf<ResolvedEpisodeRule, ArrayDeque<Episode>>()
    private val activeEpisodes = mutableMapOf<ResolvedEpisodeRule, Episode>()

    /**
     * Publish a fact as an occurrence. The process evolves only at evolve
     * boundaries, and every evolution is handled inside an episode: the
     * instance is admitted to the rule whose chain consumes its type,
     * serially, and consumed by identity on completion.
     */
    fun evolve(fact: Any) {
        requireRoutable(fact)
        evolvedArrivals[fact] = EvolveOrigin(executingEpisode, executingAction)
        blackboard.addObject(fact)
    }

    /**
     * Fail fast at the boundary: an evolving process's evolvable types are
     * its enforced contract. Without this, a mis-deployed publisher would
     * believe work was scheduled while nothing ever admits the fact.
     */
    private fun requireRoutable(fact: Any) {
        val routable = resolvedEpisodes.any { rule ->
            rule.consumes?.isInstance(fact) == true || rule.isEvolvedEligible(fact)
        }
        require(routable) {
            "${fact.javaClass.simpleName} cannot evolve this process: no episodic rule consumes it. " +
                    "Evolvable types: ${evolvableTypeNames().ifEmpty { "none" }}"
        }
    }

    private fun evolvableTypeNames(): String =
        resolvedEpisodes
            .flatMap { rule -> rule.consumes?.let { listOf(it) }.orEmpty() + rule.evolvedEligible }
            .joinToString { it.simpleName }

    protected fun admitArrivals() {
        resolvedEpisodes.forEach(::admitArrivalsFor)
    }

    private fun admitArrivalsFor(rule: ResolvedEpisodeRule) {
        arrivalsFor(rule)
            .filter { admissionSeen.add(it) }
            .forEach { arrival ->
                val origin = evolvedArrivals[arrival]
                admitOrQueue(rule, Episode(arrival, origin?.causedBy, origin?.publishedBy))
            }
    }

    /**
     * Type-subscribed rules admit every visible instance of their consumed
     * type. Evolved-only rules admit only instances published through
     * [evolve], routed by eligible type.
     */
    private fun arrivalsFor(rule: ResolvedEpisodeRule): List<Any> {
        val consumes = rule.consumes
            ?: return blackboard.objects.filter { evolvedArrivals.containsKey(it) && rule.isEvolvedEligible(it) }
        return blackboard.objectsOfType(consumes)
    }

    private fun admitOrQueue(rule: ResolvedEpisodeRule, episode: Episode) {
        if (activeEpisodes.putIfAbsent(rule, episode) == null) {
            episode.activate()
            logger.debug("Process {} admitted {}", id, episode)
            return
        }
        blackboard.hide(episode.request)
        pendingEpisodes.getOrPut(rule) { ArrayDeque() }.add(episode)
        logger.debug("Process {} queued {}", id, episode)
    }

    /**
     * Complete the active episode: consume its request by identity, then
     * admit the next pending episode so a fresh chain can begin at the
     * next tick.
     */
    private fun completeActiveEpisode(rule: ResolvedEpisodeRule): Boolean {
        val episode = activeEpisodes.remove(rule) ?: return false
        blackboard.hide(episode.request)
        episode.complete()
        lastCompletedEpisode = episode
        logger.debug("Process {} completed {}", this.id, episode)
        admitNext(rule)
        return true
    }

    private fun admitNext(rule: ResolvedEpisodeRule) {
        val next = pendingEpisodes[rule]?.removeFirstOrNull() ?: return
        blackboard.reveal(next.request)
        next.activate()
        activeEpisodes[rule] = next
        logger.debug("Process {} admitted queued {}", id, next)
    }

    protected fun handlePlanNotFound(worldState: WorldState): AgentProcess {
        logger.debug(
            "🚦 Process $id stuck\n" +
                    """|No plan from:
                   |${worldState.infoString(verbose = true, indent = 1)}
                   |in:
                   |${agent.planningSystem.infoString(verbose = true, 1)}
                   |context:
                   |${blackboard.infoString(true, 1)}
                   |"""
                        .trimMargin()
                        .indentLines(1)
        )
        setStatus(AgentProcessStatusCode.STUCK)
        val earlyTermination = identifyEarlyTermination()
        if (earlyTermination != null) {
            return this
        }
        return this
    }

    protected fun handleProcessCompletion(
        plan: Plan,
        worldState: WorldState,
    ) {
        val rule = resolvedEpisodes.firstOrNull { it.matches(plan.goal.name) }
        if (rule != null) {
            completeEpisode(rule, plan, worldState)
            return
        }
        logger.debug(
            "✅ Process {} completed, achieving goal {} in {} seconds",
            this.id,
            plan.goal.name,
            this.runningTime.seconds,
        )
        processContext.onProcessEvent(
            GoalAchievedEvent(
                agentProcess = this,
                worldState = worldState,
                goal = plan.goal,
            )
        )
        logger.debug("Final blackboard: {}", blackboard.infoString())
        setStatus(AgentProcessStatusCode.COMPLETED)
    }

    /**
     * Completes a goal episode without completing the process: consumes the
     * active episode's request and its attributed consumables (satisfying
     * output and any intermediates this occurrence made) by identity, then keeps the process running so ordinary
     * selection resumes at the next planning tick. The next pending episode,
     * if any, is admitted and replans the entire chain fresh.
     */
    private fun completeEpisode(
        rule: ResolvedEpisodeRule,
        plan: Plan,
        worldState: WorldState,
    ) {
        logger.debug(
            "🔁 Process {} completed episode goal {}; consuming and continuing",
            this.id,
            plan.goal.name,
        )
        val consumedConsumables = consumeAttributed(rule, plan.goal.name)
        val consumedRequest = completeActiveEpisode(rule)
        if (!consumedRequest) {
            logger.warn(
                "Process {} episode goal {} completed with no active episode; " +
                        "no {} occurrence was admitted for this completion",
                this.id,
                plan.goal.name,
                rule.consumes?.name ?: "evolved occurrence",
            )
        }
        if (!consumedRequest && consumedConsumables == 0) {
            logger.error(
                "Process {} episode goal {} completed but nothing was consumed; " +
                        "failing instead of spinning on a goal that will stay satisfied",
                this.id,
                plan.goal.name,
            )
            setStatus(AgentProcessStatusCode.FAILED)
            return
        }
        if (makeRunning()) {
            // The event announces consumption and continuation, so it fires
            // only once both are true
            processContext.onProcessEvent(
                EpisodeCompletedEvent(
                    agentProcess = this,
                    worldState = worldState,
                    goal = plan.goal,
                )
            )
        }
    }

    /**
     * Consume the active episode's attributed consumables for the completed
     * candidate by identity: exactly what this occurrence made, nothing
     * made elsewhere. Consumables of the episode's own failed attempts are
     * recorded like any other and swept here.
     */
    private fun consumeAttributed(rule: ResolvedEpisodeRule, goalName: String): Int {
        val episode = activeEpisodes[rule] ?: return 0
        val consumed = episode.consumablesFrom(rule.chainActionsFor(goalName))
        consumed.forEach(blackboard::hide)
        if (consumed.isNotEmpty()) {
            logger.debug(
                "Process {} consumed {} attributed consumable(s)",
                this.id,
                consumed.size,
            )
        }
        return consumed.size
    }

    /**
     * Execute the action, attributing new instances of its declared consumable
     * types to the active episode whose chain it belongs to. Attribution is
     * by identity, so completion consumes exactly what the occurrence made.
     */
    protected fun executeActionAttributingConsumables(action: Action, servedGoal: String): ActionStatus {
        val owner = attributionOwner(servedGoal, action.name)
        executingEpisode = owner?.second
        executingAction = action.name
        try {
            if (owner == null) {
                return executeAction(action)
            }
            val (rule, episode) = owner
            val grounded = groundRequestBinding(rule, episode)
            try {
                val before: MutableSet<Any> = Collections.newSetFromMap(IdentityHashMap())
                before.addAll(blackboard.objects)
                val status = executeAction(action)
                blackboard.objects
                    .filter { it !in before }
                    .filter { instance -> rule.attributedTypesFor(action.name).any { it.isInstance(instance) } }
                    .forEach { instance -> episode.record(action.name, instance) }
                return status
            } finally {
                grounded.forEach(blackboard::reveal)
            }
        } finally {
            executingEpisode = null
            executingAction = null
        }
    }

    /**
     * Ground the request binding for an evolved episode's chain execution:
     * while the action runs, the episode's request is the only visible
     * instance of its own class, so binding by type resolves the occurrence
     * the episode holds. Standard ground-action semantics, per occurrence.
     * Type-subscribed rules need no grounding: their queues already hide
     * every competitor.
     */
    private fun groundRequestBinding(rule: ResolvedEpisodeRule, episode: Episode): List<Any> {
        if (rule.consumes != null) {
            return emptyList()
        }
        val requestClass = episode.request.javaClass
        val shadowing = blackboard.objects
            .filter { it !== episode.request && requestClass.isInstance(it) }
        shadowing.forEach(blackboard::hide)
        return shadowing
    }

    /**
     * Ownership follows the plan being served, not chain membership: an
     * action executing for an episode goal's plan is attributed to that
     * rule's active episode, and an action serving another business goal
     * attributes nothing, however many chains it appears in. Relevance is
     * goal-relative (AIMA SS10.2.2). Per-tick value selection returns the
     * unsatisfiable pairing goal for opportunistic steps, where no served
     * goal exists; membership remains the documented fallback there.
     */
    private fun attributionOwner(servedGoal: String, actionName: String): Pair<ResolvedEpisodeRule, Episode>? {
        val servedRule = resolvedEpisodes.firstOrNull { it.matches(servedGoal) }
        if (servedRule != null) {
            return activeEpisodes[servedRule]?.let { servedRule to it }
        }
        if (servedGoal != NIRVANA.name) {
            return null
        }
        return activeEpisodeOwning(actionName)
    }

    private fun activeEpisodeOwning(actionName: String): Pair<ResolvedEpisodeRule, Episode>? =
        activeEpisodes.entries
            .firstOrNull { (rule, _) -> rule.isChainAction(actionName) }
            ?.toPair()

    /**
     * Everything episodic happens inside an episode: a rule's exclusive
     * chain actions are plannable only while the rule has an active
     * episode, so a standing resource can never let the chain complete
     * outside an episode.
     */
    protected fun gatedChainActions(): Set<String> =
        resolvedEpisodes
            .filter { it !in activeEpisodes }
            .flatMapTo(mutableSetOf()) { it.exclusiveChainActions }

    protected fun sendProcessRunningEvent(
        plan: Plan,
        worldState: WorldState,
    ) {
        processContext.onProcessEvent(
            AgentProcessPlanFormulatedEvent(
                agentProcess = this,
                worldState = worldState,
                plan = plan,
            )
        )
        logger.debug("▶️ Process {} running: {}\n\tPlan: {}", id, worldState, plan.infoString())
    }

    override fun formulateAndExecutePlan(worldState: WorldState): AgentProcess {
        admitArrivals()
        // Use blacklist to exclude actions that just triggered replan
        val plan = planner.bestValuePlanToAnyGoal(
            system = agent.planningSystem,
            excludedActionNames = replanBlacklist + gatedChainActions(),
        )
        if (plan == null) {
            // If no plan found with blacklist, try without it as a fallback
            // This handles the case where the blacklisted action is the only option
            if (replanBlacklist.isNotEmpty()) {
                logger.debug(
                    "No plan found with blacklist {}, clearing and retrying",
                    replanBlacklist,
                )
                replanBlacklist.clear()
                return formulateAndExecutePlan(worldState)
            }
            return handlePlanNotFound(worldState)
        }

        // Clear blacklist after successful planning
        replanBlacklist.clear()

        _goal = plan.goal

        if (plan.isComplete()) {
            handleProcessCompletion(plan, worldState)
        } else {
            sendProcessRunningEvent(plan, worldState)

            val action = resolveActionFromPlan(plan)
            try {
                val actionStatus = executeActionAttributingConsumables(action, plan.goal.name)
                setStatus(actionStatusToAgentProcessStatus(actionStatus))
            } catch (rpe: ReplanRequestedException) {
                handleReplanRequest(action, rpe)
            } catch (e: TerminateActionException) {
                // Action requested early termination - continue with next action
                logger.info(
                    "Action {} terminated early: {}",
                    action.name,
                    e.reason,
                )
                // Keep status as RUNNING to continue with next action
                setStatus(AgentProcessStatusCode.RUNNING)
            } catch (e: TerminateAgentException) {
                // Agent termination requested - stop the entire process
                logger.info(
                    "Agent process terminated by action {}: {}",
                    action.name,
                    e.reason,
                )
                setStatus(AgentProcessStatusCode.TERMINATED)
            } catch (e: Exception) {
                if (e is ToolControlFlowSignal) {
                    // Other control flow signals (e.g., UserInputRequiredException) must propagate
                    throw e
                }
                throw e
            }
        }
        return this
    }

    private fun resolveActionFromPlan(plan: Plan): com.embabel.agent.core.Action =
        agent.actions.singleOrNull { it.name == plan.actions.first().name }
            ?: error(
                "No unique action found for ${plan.actions.first().name} in ${agent.actions.map { it.name }}"
            )

    /**
     * Handles a [ReplanRequestedException] thrown by an action.
     *
     * Shared by [SimpleAgentProcess] and [ConcurrentAgentProcess] to keep replan semantics
     * consistent across both execution modes:
     * 1. Applies the blackboard updates supplied by the throwing action.
     * 2. Blacklists the action for the next planning cycle to prevent an immediate infinite loop.
     * 3. Emits a [ReplanRequestedEvent].
     * 4. Keeps the process status as [AgentProcessStatusCode.RUNNING] so the main loop replans.
     */
    protected fun handleReplanRequest(action: Action, rpe: ReplanRequestedException) {
        rpe.blackboardUpdater.accept(blackboard)
        replanBlacklist.add(action.name)
        logger.info(
            "Action {} requested replan: {}. Blacklisted for next cycle.",
            action.name,
            rpe.reason,
        )
        processContext.onProcessEvent(
            ReplanRequestedEvent(
                agentProcess = this,
                reason = rpe.reason,
            )
        )
        setStatus(AgentProcessStatusCode.RUNNING)
    }
}
