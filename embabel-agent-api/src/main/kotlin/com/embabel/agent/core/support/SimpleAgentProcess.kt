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
    private val pendingEpisodes = mutableMapOf<ResolvedEpisodeRule, ArrayDeque<Episode>>()
    private val activeEpisodes = mutableMapOf<ResolvedEpisodeRule, Episode>()

    protected fun admitArrivals() {
        resolvedEpisodes.forEach(::admitArrivalsFor)
    }

    private fun admitArrivalsFor(rule: ResolvedEpisodeRule) {
        blackboard.objectsOfType(rule.consumes)
            .filter { admissionSeen.add(it) }
            .forEach { arrival -> admitOrQueue(rule, Episode(arrival)) }
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
                rule.consumes.name,
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
    protected fun executeActionAttributingConsumables(action: Action): ActionStatus {
        val owner = activeEpisodeOwning(action.name) ?: return executeAction(action)
        val (rule, episode) = owner
        val before: MutableSet<Any> = Collections.newSetFromMap(IdentityHashMap())
        before.addAll(blackboard.objects)
        val status = executeAction(action)
        blackboard.objects
            .filter { it !in before }
            .filter { instance -> rule.attributedTypesFor(action.name).any { it.isInstance(instance) } }
            .forEach { instance -> episode.record(action.name, instance) }
        return status
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
                val actionStatus = executeActionAttributingConsumables(action)
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
