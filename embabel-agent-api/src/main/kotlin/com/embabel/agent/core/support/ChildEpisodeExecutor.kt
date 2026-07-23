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

import com.embabel.agent.api.common.PlannerType
import com.embabel.agent.core.Action
import com.embabel.agent.core.Agent
import com.embabel.agent.core.AgentProcess
import com.embabel.agent.core.AgentProcessStatusCode
import com.embabel.agent.core.Goal
import com.embabel.agent.core.ProcessOptions
import com.embabel.agent.spi.PlannerFactory
import com.embabel.plan.Planner
import com.embabel.plan.common.condition.WorldStateDeterminer
import org.slf4j.LoggerFactory
import java.util.Collections
import java.util.IdentityHashMap

/** Bounded inspection window for framework-dispatched children */
private const val RETAINED_CHILDREN = 32

/** One valued, dispatchable episode: the planner's selection input */
internal data class EpisodeChoice(
    val rule: ResolvedEpisodeRule,
    val episode: Episode,
    val goal: Goal,
    val chainActions: List<Action>,
    val value: Double,
)

/** What executing a child episode did */
internal enum class ChildExecution {
    COMPLETED,
    NOT_COMPLETED,
    BUDGET_EXHAUSTED,
}

/**
 * The child rung's execution adapter. It receives an already-selected
 * episode and runs it: synthesizes the child from the derived chain,
 * spawns it with the parent's options minus the evolving declaration,
 * contains failure, and merges the outcome home. It never chooses which
 * episode wins - selection belongs to the planner - but it does supply
 * the values selection ranks, through the same full-path planner its
 * children run.
 */
internal class ChildEpisodeExecutor(
    private val process: SimpleAgentProcess,
    plannerFactory: PlannerFactory,
    worldStateDeterminer: WorldStateDeterminer,
) {

    private val logger = LoggerFactory.getLogger(javaClass)

    /**
     * The full-path planner children run under, used to value and check
     * each candidate chain before dispatch regardless of the parent's
     * planner type.
     */
    private val childPlanner: Planner<*, *, *> =
        plannerFactory.createPlanner(ProcessOptions.DEFAULT, worldStateDeterminer)

    private val recentChildren = ArrayDeque<AgentProcess>()

    /** Total children ever dispatched for this process */
    var childCount = 0
        private set

    /** Dispatches that failed or threw, for the budget brake's diagnosis */
    private var failedDispatches = 0

    /** Recent children, bounded: inspection sees a window, not a hoard */
    val recentChildrenView: List<AgentProcess> get() = recentChildren.toList()

    /**
     * Value each active episode by the plan its child would run. An
     * unplannable chain has no value and is never a candidate, so a
     * blocked episode costs one plan check per tick, never a doomed child.
     */
    fun choices(active: Map<ResolvedEpisodeRule, Episode>, agent: Agent): List<EpisodeChoice> =
        active.entries.mapNotNull { (rule, episode) ->
            // Derived rules are per goal by construction; single() fails
            // loudly if that invariant ever changes
            val goal = rule.goalsByName.values.single()
            val value = chainValue(rule, goal, agent)
            if (value == Double.NEGATIVE_INFINITY) {
                return@mapNotNull null
            }
            EpisodeChoice(rule, episode, goal, chainActions(rule, goal, agent), value)
        }

    /**
     * The full-path value of the chain a child would run for this rule's
     * goal. Dispatch selection and contested routing both rank by it, so
     * the two decisions share one planning view.
     */
    fun chainValue(rule: ResolvedEpisodeRule, goal: Goal, agent: Agent): Double =
        childPlanner.planToGoal(chainActions(rule, goal, agent), goal)
            ?.netValue(childPlanner.worldState())
            ?: Double.NEGATIVE_INFINITY

    private fun chainActions(rule: ResolvedEpisodeRule, goal: Goal, agent: Agent): List<Action> {
        val chainNames = rule.chainActionsFor(goal.name)
        return agent.actions.filter { it.name in chainNames }
    }

    /**
     * Execute the selected episode in a synthesized child. The parent's
     * action budget bounds total dispatches; a failing child is contained,
     * its occurrence unconsumed, awaiting redispatch when next selected.
     */
    fun execute(choice: EpisodeChoice): ChildExecution {
        if (childCount >= process.processOptions.budget.actions) {
            // A persistent crash burns to this brake: name the failures so
            // exhaustion by failure never masquerades as ordinary spend
            logger.warn(
                "Process {} reached its action budget ({}) dispatching child episodes, {} of them failed; terminating",
                process.id,
                process.processOptions.budget.actions,
                failedDispatches,
            )
            return ChildExecution.BUDGET_EXHAUSTED
        }
        val childAgent = process.agent.copy(
            actions = choice.chainActions,
            goals = setOf(choice.goal),
        )
        val parentVisible: MutableSet<Any> = Collections.newSetFromMap(IdentityHashMap())
        parentVisible.addAll(process.objects)
        val platform = process.processContext.platformServices.agentPlatform
        // The child inherits the parent's options - budget limits,
        // identities, context - minus the evolving declaration, with the
        // full-path planner episodes require
        val child = platform.createChildProcess(
            childAgent,
            process,
            process.processOptions.copy(evolving = null, plannerType = PlannerType.GOAP),
        )
        recordChild(child)
        // A failing child is contained: the parent keeps running, the
        // occurrence stays unconsumed, and a later selection respawns
        val completed = runCatching { child.run() }.getOrElse { failure ->
            failedDispatches++
            logger.warn(
                "Process {} child episode for {} failed; the occurrence stays for a later selection",
                process.id,
                choice.goal.name,
                failure,
            )
            return ChildExecution.NOT_COMPLETED
        }
        if (completed.status == AgentProcessStatusCode.FAILED) {
            failedDispatches++
            logger.warn(
                "Process {} child episode for {} FAILED{}; the occurrence stays for a later selection",
                process.id,
                choice.goal.name,
                completed.failureInfo?.let { ": $it" } ?: "",
            )
            return ChildExecution.NOT_COMPLETED
        }
        if (completed.status != AgentProcessStatusCode.COMPLETED) {
            logger.debug(
                "Process {} child episode for {} did not complete ({}); request stays for a later selection",
                process.id,
                choice.goal.name,
                completed.status,
            )
            return ChildExecution.NOT_COMPLETED
        }
        mergeOutcome(choice, child, parentVisible)
        return ChildExecution.COMPLETED
    }

    /**
     * Merge-back: everything the chain wrote comes home, so accumulator
     * patterns author identically on both rungs. Consumable-typed
     * instances are recorded onto the episode and consumed at completion;
     * undeclared standing writes survive as they would in-process. A
     * narrower contract would strand standing state in the child: a chain
     * whose loop condition reads a stranded accumulator self-chains
     * forever.
     */
    private fun mergeOutcome(choice: EpisodeChoice, child: AgentProcess, parentVisible: Set<Any>) {
        val attributedTypes = choice.rule.consumableTypesByGoal[choice.goal.name].orEmpty()
        val anchor = choice.rule.chainActionsFor(choice.goal.name).first()
        val parentNow: MutableSet<Any> = Collections.newSetFromMap(IdentityHashMap())
        parentNow.addAll(process.objects)
        child.objects
            .filter { it !in parentVisible && it !in parentNow }
            .forEach { instance ->
                // Through the process path, so merged outputs emit
                // ObjectAddedEvent like any other addition
                process.addObject(instance)
                if (attributedTypes.any { it.isInstance(instance) }) {
                    choice.episode.record(anchor, instance)
                }
            }
    }

    private fun recordChild(child: AgentProcess) {
        recentChildren.addLast(child)
        if (recentChildren.size > RETAINED_CHILDREN) {
            recentChildren.removeFirst()
        }
        childCount++
    }
}
