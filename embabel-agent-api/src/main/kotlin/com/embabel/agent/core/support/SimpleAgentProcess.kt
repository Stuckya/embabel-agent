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
import com.embabel.agent.api.event.GoalAchievedEvent
import com.embabel.agent.api.event.ReplanRequestedEvent
import com.embabel.agent.api.tool.TerminateActionException
import com.embabel.agent.api.tool.TerminateAgentException
import com.embabel.agent.core.Action
import com.embabel.agent.core.ActionStatus
import com.embabel.agent.core.Agent
import com.embabel.agent.core.AgentProcess
import com.embabel.agent.core.AgentProcessStatusCode
import com.embabel.agent.core.Blackboard
import com.embabel.agent.core.Bindable
import com.embabel.agent.core.ProcessOptions
import com.embabel.agent.core.ReplanRequestedException
import com.embabel.agent.spi.PlannerFactory
import com.embabel.common.util.indentLines
import com.embabel.plan.Plan
import com.embabel.plan.Planner
import com.embabel.plan.PlanningDirective
import com.embabel.plan.WorldState
import com.embabel.plan.common.condition.WorldStateDeterminer
import java.time.Instant


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

    /** Occurrence and child lifecycle; all planning decisions stay in the planner session. */
    private val episodes = EpisodeRuntime(
        process = this,
        setStatus = ::setStatus,
        makeRunning = ::makeRunning,
    )

    /**
     * Actions to exclude from the next planning cycle.
     * Used to prevent infinite loops when an action requests replan but
     * would be the only applicable action again.
     * Cleared after each successful planning cycle.
     */
    protected val replanBlacklist = mutableSetOf<String>()

    /** Whether this process declared evolving mode, for platform wiring */
    internal val isEvolving: Boolean get() = episodes.isEvolving

    /**
     * Set by the platform on children of an evolving process: evolve from
     * inside a child hands the fact to the nearest evolving ancestor,
     * however deep the nesting, so an action publishes the same way
     * whether it runs in the parent or in a child.
     */
    internal var evolveDelegate: ((Any, String?) -> com.embabel.agent.core.OccurrenceId)?
        get() = episodes.evolveDelegate
        set(value) {
            episodes.evolveDelegate = value
        }

    internal var shareDelegate: ((Any) -> Unit)?
        get() = episodes.shareDelegate
        set(value) {
            episodes.shareDelegate = value
        }

    /**
     * The most recently completed episode, kept so callers can inspect
     * what just finished. Completed episodes are otherwise discarded.
     */
    internal val lastCompletedEpisode: Episode? get() = episodes.lastCompletedEpisode

    /** Recent framework children, bounded, for inspection */
    internal val frameworkChildren: List<AgentProcess> get() = episodes.frameworkChildren

    /** Total framework children ever dispatched */
    internal val frameworkChildCount: Int get() = episodes.frameworkChildCount

    /** Arrival bookkeeping still held, for leak inspection */
    internal val retainedArrivalBookkeeping: Int get() = episodes.retainedArrivalBookkeeping

    /** Active ledger record for contract tests and process observability. */
    internal fun activeEpisode(id: com.embabel.agent.core.OccurrenceId): Episode? =
        episodes.activeEpisode(id)

    override fun evolve(fact: Any) = episodes.evolve(fact)

    override fun evolve(fact: Any, publishingActionName: String?) =
        episodes.evolve(fact, publishingActionName)

    override fun share(fact: Any) = episodes.share(fact)

    override fun signalWorldChange() = episodes.onStandingStateChanged()

    override fun addObject(value: Any): Bindable {
        val result = super.addObject(value)
        episodes.onStandingStateChanged()
        return result
    }

    /**
     * Execute an action in this process. Episode chains only run in child
     * processes, so everything this process runs itself is its own
     * long-running work - the runtime just tracks which episode any
     * published fact came from.
     */
    protected fun executeTrackedAction(action: Action): ActionStatus =
        episodes.runTrackingPublisher(action) { executeAction(action) }

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
        identifyEarlyTermination()
        return this
    }

    protected fun handleProcessCompletion(
        plan: Plan,
        worldState: WorldState,
    ) {
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

    private fun handleEvolvingProcessCompletion(
        directive: PlanningDirective.CompleteProcess,
        worldState: WorldState,
    ) {
        episodes.reportAbandonedOccurrences()
        logger.debug(
            "✅ Evolving process {} completed by planner directive for goal {} in {} seconds",
            id,
            directive.goal.name,
            runningTime.seconds,
        )
        processContext.onProcessEvent(
            GoalAchievedEvent(
                agentProcess = this,
                worldState = worldState,
                goal = directive.goal,
            )
        )
        setStatus(AgentProcessStatusCode.COMPLETED)
    }

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
        if (isEvolving) {
            return formulateAndExecuteEvolving(worldState)
        }
        // Use blacklist to exclude actions that just triggered replan
        val plan = planner.bestValuePlanToAnyGoal(
            system = agent.planningSystem,
            excludedActionNames = replanBlacklist,
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
            executePlanStep(plan, worldState)
        }
        return this
    }

    private fun formulateAndExecuteEvolving(worldState: WorldState): AgentProcess {
        return when (val directive = episodes.nextDirective(replanBlacklist)) {
            is PlanningDirective.RunRoot -> {
                check(!directive.execution.plan.isComplete()) {
                    "A planner session must use CompleteProcess for root completion"
                }
                replanBlacklist.clear()
                _goal = directive.execution.plan.goal
                executePlanStep(directive.execution.plan, worldState)
                this
            }

            is PlanningDirective.RunEpisode -> {
                replanBlacklist.clear()
                episodes.runEpisode(directive)
                this
            }

            is PlanningDirective.AwaitEpisode -> {
                episodes.awaitEpisode(directive)
                this
            }

            is PlanningDirective.CompleteEpisode -> {
                episodes.completeEpisode(directive, worldState)
                this
            }

            is PlanningDirective.CancelEpisode -> {
                episodes.cancelEpisode(directive)
                this
            }

            PlanningDirective.AwaitProcess -> {
                if (replanBlacklist.isNotEmpty()) {
                    replanBlacklist.clear()
                    formulateAndExecuteEvolving(worldState)
                } else {
                    handlePlanNotFound(worldState)
                }
            }

            is PlanningDirective.CompleteProcess -> {
                handleEvolvingProcessCompletion(directive, worldState)
                this
            }
        }
    }

    private fun executePlanStep(
        plan: Plan,
        worldState: WorldState,
    ) {
        sendProcessRunningEvent(plan, worldState)
        val action = resolveActionFromPlan(plan)
        try {
            val actionStatus = executeTrackedAction(action)
            setStatus(actionStatusToAgentProcessStatus(actionStatus))
        } catch (rpe: ReplanRequestedException) {
            handleReplanRequest(action, rpe)
        } catch (e: TerminateActionException) {
            logger.info(
                "Action {} terminated early: {}",
                action.name,
                e.reason,
            )
            setStatus(AgentProcessStatusCode.RUNNING)
        } catch (e: TerminateAgentException) {
            logger.info(
                "Agent process terminated by action {}: {}",
                action.name,
                e.reason,
            )
            setStatus(AgentProcessStatusCode.TERMINATED)
        }
    }

    private fun resolveActionFromPlan(plan: Plan): Action =
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
