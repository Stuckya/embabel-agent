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

import com.embabel.agent.core.Action
import com.embabel.agent.core.Agent
import com.embabel.agent.core.AgentProcess
import com.embabel.agent.core.AgentProcessStatusCode
import com.embabel.agent.core.Goal
import com.embabel.agent.core.ProcessOptions
import com.embabel.agent.spi.PlannerFactory
import com.embabel.plan.WorldState
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

/** What dispatching an episode did */
internal enum class DispatchOutcome {
    COMPLETED,
    NOT_COMPLETED,
    BUDGET_EXHAUSTED,
}

/**
 * The episode execution adapter. It receives an already-selected
 * episode and runs it: synthesizes the child from the derived chain,
 * spawns it with the parent's options minus the evolving declaration,
 * contains failure, and merges the outcome home. It never chooses which
 * episode wins - selection belongs to the planner - and it proves nothing
 * ahead of the run: the child's own execution is the only verdict on a
 * chain.
 */
internal class EpisodeExecutor(
    private val process: SimpleAgentProcess,
    plannerFactory: PlannerFactory,
    worldStateDeterminer: WorldStateDeterminer,
) {

    private val logger = LoggerFactory.getLogger(javaClass)

    private val recentChildren = ArrayDeque<AgentProcess>()

    /** Total children ever dispatched for this process */
    var childCount = 0
        private set

    /** Dispatches that failed or threw, for the budget brake's diagnosis */
    private var failedDispatches = 0

    /** Recent children, bounded: inspection sees a window, not a hoard */
    val recentChildrenView: List<AgentProcess> get() = recentChildren.toList()

    /**
     * Nothing is proven before dispatch: an active episode is a candidate
     * at its goal's declared value, and the child's own run is the only
     * verdict on the chain. The one exclusion is observational, the sphex
     * defense: an episode whose child ran and blocked is not re-attempted
     * into a world that has not changed since.
     */
    fun choices(
        active: Map<ResolvedEpisodeRule, Episode>,
        agent: Agent,
        worldState: WorldState,
    ): List<EpisodeChoice> {
        val visible = visibleNow()
        return active.entries.mapNotNull { (rule, episode) ->
            if (blockedAttempts[episode] == visible) {
                // The world it blocked in is the world it would block in
                return@mapNotNull null
            }
            // Derived rules are per goal by construction; single() fails
            // loudly if that invariant ever changes
            val goal = rule.goalsByName.values.single()
            EpisodeChoice(rule, episode, goal, chainActions(rule, goal, agent), goal.value(worldState))
        }
    }

    private fun chainActions(rule: ResolvedEpisodeRule, goal: Goal, agent: Agent): List<Action> {
        val chainNames = rule.chainActionsFor(goal.name)
        return agent.actions.filter { it.name in chainNames }
    }

    /**
     * Episodes whose last child ran and blocked, keyed to the visible
     * parent world it blocked in. A crashed child is deliberately absent:
     * transient faults do not change boards, so crashes retry freely under
     * the budget while blocks wait for new facts.
     */
    private val blockedAttempts: MutableMap<Episode, Set<Any>> = IdentityHashMap()

    private fun visibleNow(): Set<Any> {
        val visible: MutableSet<Any> = Collections.newSetFromMap(IdentityHashMap())
        visible.addAll(process.objects)
        return visible
    }

    /**
     * Execute the selected episode in a synthesized child. The parent's
     * action budget bounds total dispatches; a failing child is contained,
     * its occurrence unconsumed, awaiting redispatch when next selected.
     */
    fun execute(choice: EpisodeChoice): DispatchOutcome {
        if (childCount >= process.processOptions.budget.actions) {
            // A persistent crash burns to this brake: name the failures so
            // exhaustion by failure never masquerades as ordinary spend
            logger.warn(
                "Process {} reached its action budget ({}) dispatching child episodes, {} of them failed; terminating",
                process.id,
                process.processOptions.budget.actions,
                failedDispatches,
            )
            return DispatchOutcome.BUDGET_EXHAUSTED
        }
        blockedAttempts.remove(choice.episode)
        // Nothing was proven before this dispatch: the child's run is the
        // verdict, and the boundary contains whatever happens. The declared
        // pairing goal travels with the chain so a value-walking planner
        // keeps its documented configuration
        val childAgent = process.agent.copy(
            actions = choice.chainActions,
            goals = setOf(choice.goal) + process.agent.goals.filter { it.name == NIRVANA.name },
        )
        val parentVisible: MutableSet<Any> = Collections.newSetFromMap(IdentityHashMap())
        parentVisible.addAll(process.objects)
        val platform = process.processContext.platformServices.agentPlatform
        // The child inherits the parent's options - budget limits,
        // identities, context, and the declared planner - minus the
        // evolving declaration. The framework never overrides a declared
        // planner: the child runs what the developer chose
        val child = platform.createChildProcess(
            childAgent,
            process,
            process.processOptions.copy(evolving = null),
        )
        groundRequestWindow(choice, child)
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
            return DispatchOutcome.NOT_COMPLETED
        }
        if (completed.status == AgentProcessStatusCode.FAILED) {
            failedDispatches++
            logger.warn(
                "Process {} child episode for {} FAILED{}; the occurrence stays for a later selection",
                process.id,
                choice.goal.name,
                completed.failureInfo?.let { ": $it" } ?: "",
            )
            return DispatchOutcome.NOT_COMPLETED
        }
        if (completed.status != AgentProcessStatusCode.COMPLETED) {
            blockedAttempts[choice.episode] = visibleNow()
            logger.debug(
                "Process {} child episode for {} blocked ({}); it waits for the world to change",
                process.id,
                choice.goal.name,
                completed.status,
            )
            return DispatchOutcome.NOT_COMPLETED
        }
        mergeOutcome(choice, child, parentVisible)
        return DispatchOutcome.COMPLETED
    }

    /**
     * Merge-back: everything the chain wrote comes home, so accumulator
     * patterns author identically inside and outside episodes.
     * Consumable-typed instances are recorded onto the episode and
     * consumed at completion; undeclared standing writes survive. A
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

    /**
     * The request window, structurally: in the child's world the episode's
     * request is the only visible instance of its own class, so binding by
     * type resolves the occurrence the episode holds, not a newer plain
     * fact riding the snapshot. Standard ground-action semantics per
     * occurrence (AIMA 3e SS10.1).
     */
    private fun groundRequestWindow(choice: EpisodeChoice, child: AgentProcess) {
        val requestClass = choice.episode.request.javaClass
        child.objects
            .filter { it !== choice.episode.request && requestClass.isInstance(it) }
            .forEach(child::hide)
    }

    private fun recordChild(child: AgentProcess) {
        recentChildren.addLast(child)
        if (recentChildren.size > RETAINED_CHILDREN) {
            recentChildren.removeFirst()
        }
        childCount++
    }
}
