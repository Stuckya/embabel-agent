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

import com.embabel.agent.api.event.EpisodeCompletedEvent
import com.embabel.agent.core.Action
import com.embabel.agent.core.ActionStatus
import com.embabel.agent.core.Agent
import com.embabel.agent.core.AgentProcess
import com.embabel.agent.core.AgentProcessStatusCode
import com.embabel.agent.core.Blackboard
import com.embabel.agent.core.Goal
import com.embabel.agent.core.IoBinding
import com.embabel.agent.core.JvmType
import com.embabel.plan.Plan
import com.embabel.plan.PlanningSystem
import com.embabel.plan.WorldState
import org.slf4j.LoggerFactory
import java.util.Collections
import java.util.IdentityHashMap

/**
 * The episode machinery for one evolving process: derivation, admission,
 * routing, the visibility windows, publisher tracking, dispatch selection,
 * and completion bookkeeping. The process owns its run loop and status;
 * the runtime owns every episode decision inside it, borrowing exactly the
 * two process powers it declares - setting status and resuming. Outside evolving
 * mode evolve hands the fact to the nearest evolving ancestor or fails
 * fast, and every other path is inert.
 */
internal class EpisodeRuntime(
    private val process: SimpleAgentProcess,
    private val setStatus: (AgentProcessStatusCode) -> Unit,
    private val makeRunning: () -> Boolean,
) {

    private val logger = LoggerFactory.getLogger(javaClass)

    private val blackboard: Blackboard get() = process.blackboard
    private val agent: Agent get() = process.agent
    private val planner get() = process.planner
    private val id: String get() = process.id

    /**
     * In evolving mode every episode rule is derived from the goal graph at
     * construction, with underivable goals excluded and their reasons kept
     * for the evolve call site. Null outside evolving mode.
     */
    private val derivedScope: DerivedEvolvingScope? =
        process.processOptions.evolving?.let { EpisodeDerivation.deriveEvolving(agent, it.objective) }

    /**
     * Episode rules derived from the goal graph in evolving mode. Empty
     * outside evolving mode, where no episode machinery engages.
     */
    private val derivedRules: List<DerivedEpisodeRule> =
        derivedScope?.rules.orEmpty()

    init {
        // Dispatch would reject an ephemeral parent only at first dispatch,
        // mid-mission: the conflicting declarations fail here
        require(derivedScope == null || !process.processOptions.ephemeral) {
            "An ephemeral process cannot evolve: episodes execute in child processes, " +
                    "which require the persistence the ephemeral declaration disclaims"
        }
    }

    /**
     * Rules that have admitted at least one occurrence. A goal's
     * episodicity is established by its first observed occurrence: before
     * that the goal is founding frame, and after that its exclusive chain
     * is the episode machinery's, not the parent planner's.
     */
    private val activatedRules = mutableSetOf<DerivedEpisodeRule>()

    /**
     * In evolving mode the whole process is treated as one outermost
     * episode, active from construction. Its request is the set of facts
     * already on the blackboard at creation. Work that belongs to no
     * smaller episode runs inside it, so when that work publishes a fact
     * through evolve, this episode is recorded as the publisher.
     */
    private val foundingEpisode: Episode? =
        derivedScope?.let { Episode(FoundingFacts(blackboard.objects.toList())).also(Episode::activate) }

    /**
     * Goals outside any episode that were already achieved: recorded and
     * removed from planning so the process goes back to its long-running
     * work instead of ending, or doing the same thing again. Only the
     * declared objective ends the process.
     */
    private val achievedFrameGoals = mutableSetOf<String>()

    /**
     * Instances of the objective's output type that were already on the
     * blackboard at creation. Left visible, they would look like the
     * objective was already met, and the process could neither finish
     * properly nor plan toward finishing. They are hidden here and made
     * visible again when the process completes.
     */
    private val foundingShadow: List<Any> = shadowFoundingObjective()

    private fun shadowFoundingObjective(): List<Any> {
        val scope = derivedScope ?: return emptyList()
        if (scope.objectiveGoals.isEmpty()) {
            return emptyList()
        }
        val satisfyingClasses = agent.goals
            .filter { it.name in scope.objectiveGoals }
            .mapNotNull { goal ->
                val resolved = (goal.outputType as? JvmType)
                    ?.let { IoBinding(it.className).resolveJvmType()?.clazz }
                if (resolved == null) {
                    logger.warn(
                        "Process {} cannot resolve the satisfying type of objective goal {}: " +
                                "construction-time instances of it will not be shadowed",
                        id,
                        goal.name,
                    )
                }
                resolved
            }
        if (satisfyingClasses.isEmpty()) {
            return emptyList()
        }
        val shadowed = blackboard.objects.filter { instance ->
            satisfyingClasses.any { it.isInstance(instance) }
        }
        shadowed.forEach(blackboard::hide)
        return shadowed
    }

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
     * action published it, if any: the publisher is captured at the moment
     * of publication. The call site decides what a fact is: a fact
     * published through evolve starts an episode, while a fact added
     * through addObject is ordinary shared state, even when both have the
     * same type.
     */
    private val evolvedArrivals: MutableMap<Any, EvolveOrigin> = IdentityHashMap()

    private data class EvolveOrigin(
        val causedBy: Episode?,
        val publishedBy: String?,
        val owner: DerivedEpisodeRule? = null,
    )

    /**
     * The episode evolve attributes publications to right now: the founding
     * episode during a frame action, the dispatching episode during a child
     * run. Pure publisher tracking - no chain action ever executes here.
     */
    private var executingEpisode: Episode? = null

    /** The name of the frame action currently executing, if any */
    private var executingAction: String? = null

    /**
     * The most recently completed episode, kept so callers can inspect
     * what just finished and who published what. Completed episodes are
     * otherwise discarded.
     */
    var lastCompletedEpisode: Episode? = null
        private set

    private val pendingEpisodes = mutableMapOf<DerivedEpisodeRule, ArrayDeque<Episode>>()
    private val activeEpisodes = mutableMapOf<DerivedEpisodeRule, Episode>()

    /**
     * Pre-existing instances hidden for the length of an episode: without
     * this, an old object of a type the episode is meant to produce would
     * make the goal look already satisfied, and the episode would complete
     * without running its chain. Hidden when the episode starts, made
     * visible again when it completes - hidden, never consumed.
     */
    private val outcomeGroundings: MutableMap<Episode, List<Any>> = IdentityHashMap()

    /**
     * The episode execution adapter: it runs already-selected
     * episodes and supplies the values selection ranks. Selection itself
     * stays with the planner.
     */
    private val executor: EpisodeExecutor by lazy {
        EpisodeExecutor(process)
    }

    /** Recent framework children, bounded, for inspection */
    val frameworkChildren: List<AgentProcess> get() = executor.recentChildrenView

    /** Total framework children ever dispatched */
    val frameworkChildCount: Int get() = executor.childCount

    /** Whether this process declared evolving mode, for platform wiring */
    val isEvolving: Boolean get() = derivedScope != null

    /**
     * Arrival bookkeeping still held, for inspection: an intentionally
     * infinite process ingests occurrences forever, so bookkeeping retained
     * past completion is a leak, not a record.
     */
    val retainedArrivalBookkeeping: Int get() = evolvedArrivals.size + admissionSeen.size

    /**
     * Set by the platform on children of an evolving process: evolve from
     * inside a child hands the fact to the nearest evolving ancestor,
     * however deep the nesting, so an action publishes the same way
     * whether it runs in the parent or in a child.
     */
    var evolveDelegate: ((Any) -> Unit)? = null

    /**
     * Publish a fact that asks for one run of an episode. The fact goes to
     * the rule whose chain needs its type as an input, one run at a time,
     * and exactly this instance is consumed when the run completes.
     */
    fun evolve(fact: Any) {
        if (derivedScope == null) {
            val delegate = evolveDelegate
            require(delegate != null) {
                "evolve requires an evolving process: declare withEvolving() on the process options"
            }
            delegate(fact)
            return
        }
        val candidates = routableRules(fact)
        requireRoutable(fact, candidates)
        process.addObject(fact)
        // Contested ownership stays open until the next planning tick: a
        // mid-action evolve precedes its own action's remaining effects, so
        // the arrival's world has not materialized yet
        evolvedArrivals[fact] = EvolveOrigin(executingEpisode, executingAction, candidates.singleOrNull())
    }

    private fun routableRules(fact: Any): List<DerivedEpisodeRule> =
        derivedRules.filter { it.isEvolvedEligible(fact) }

    /**
     * When more than one rule could take an arriving fact, the rule whose
     * goal has the highest declared value in the current world owns it.
     * Ownership is decided once and not revisited, so a rule that is busy
     * cannot lose a fact that belongs to it. Ties go to the first-declared
     * candidate. Other instances of the same class are hidden while the
     * values are read, so a value function that inspects the world sees
     * only the arriving fact.
     */
    private fun routeByPlan(fact: Any, candidates: List<DerivedEpisodeRule>): DerivedEpisodeRule? {
        val competitors = blackboard.objects.filter { it !== fact && fact.javaClass.isInstance(it) }
        competitors.forEach(blackboard::hide)
        try {
            // Declared values always bind: ownership resolves at the first
            // settled tick, never earlier than the arrival's world
            val scored = candidates.map { it to bestPlanValue(it) }
            return scored.maxByOrNull { it.second }?.first
        } finally {
            competitors.forEach(blackboard::reveal)
        }
    }

    /**
     * Routing shares dispatch's view: a candidate is worth its goal's
     * declared value in the current world. Nothing is proven at routing
     * time - the owning rule's child run is the verdict on the chain.
     */
    private fun bestPlanValue(rule: DerivedEpisodeRule): Double =
        rule.goalsByName.values.maxOfOrNull { goal -> goal.value(planner.worldState()) }
            ?: Double.NEGATIVE_INFINITY

    /**
     * Fail fast at the boundary: an evolving process's evolvable types are
     * its enforced contract. Without this, a mis-deployed publisher would
     * believe work was scheduled while nothing ever admits the fact. In
     * evolving mode the message also carries why derivation excluded goals,
     * so the publisher learns what the graph could not support.
     */
    private fun requireRoutable(fact: Any, candidates: List<DerivedEpisodeRule>) {
        require(candidates.isNotEmpty()) {
            "${fact.javaClass.simpleName} cannot evolve this process: no episodic rule consumes it. " +
                    "Evolvable types: ${evolvableTypeNames().ifEmpty { "none" }}" +
                    describeExclusions()
        }
    }

    private fun describeExclusions(): String {
        val exclusions = derivedScope?.exclusions.orEmpty()
        if (exclusions.isEmpty()) {
            return ""
        }
        return ". Goals excluded from derivation: " +
                exclusions.entries.joinToString("; ") { (goal, reason) -> "$goal ($reason)" }
    }

    private fun evolvableTypeNames(): String =
        derivedRules.flatMap { it.evolvedEligible }.joinToString { it.simpleName }

    fun admitArrivals() {
        resolveDeferredRouting()
        derivedRules.forEach(::admitArrivalsFor)
        admitDeferred()
    }

    /**
     * Resolve contested ownership left open at the evolve boundary, now
     * that the arrival's world has settled. Arrival order remains the
     * boundary fact; ownership is decided against the materialized world.
     */
    private fun resolveDeferredRouting() {
        evolvedArrivals.entries
            .filter { it.value.owner == null }
            .toList()
            .forEach { (fact, origin) ->
                routeByPlan(fact, routableRules(fact))?.let { owner ->
                    evolvedArrivals[fact] = origin.copy(owner = owner)
                }
            }
    }

    /**
     * Admit waiting episodes once the process's own work is out of moves.
     * Work that could end the process always runs before the next episode
     * is allowed to start.
     */
    private fun admitDeferred() {
        if (derivedScope == null) {
            return
        }
        val idleWithPending = derivedRules.filter {
            activeEpisodes[it] == null && !pendingEpisodes[it].isNullOrEmpty()
        }
        if (idleWithPending.isEmpty() || foundingWorkPlannable()) {
            return
        }
        idleWithPending.forEach(::admitNext)
    }

    private fun admitArrivalsFor(rule: DerivedEpisodeRule) {
        arrivalsFor(rule)
            .filter { admissionSeen.add(it) }
            .forEach { arrival ->
                val origin = evolvedArrivals[arrival]
                admitOrQueue(rule, Episode(arrival, origin?.causedBy, origin?.publishedBy))
            }
    }

    /**
     * A rule admits only instances published through [evolve], routed to
     * their owning rule fixed at the arrival boundary.
     */
    private fun arrivalsFor(rule: DerivedEpisodeRule): List<Any> =
        blackboard.objects.filter { routesTo(rule, it) }

    /**
     * An evolved arrival routes to its owning rule. Uncontested arrivals
     * are owned at the evolve boundary; contested ones at the next tick,
     * before any admission scan runs.
     */
    private fun routesTo(rule: DerivedEpisodeRule, instance: Any): Boolean =
        evolvedArrivals[instance]?.owner == rule

    private fun admitOrQueue(rule: DerivedEpisodeRule, episode: Episode) {
        activatedRules += rule
        // An occurrence reopens the question: the goal was achieved as
        // state, and a fresh request makes it unachieved by definition
        achievedFrameGoals -= rule.goalsByName.keys
        if (activeEpisodes[rule] == null && foundingWorkPlannable()) {
            // Work that could end the process runs before any new episode
            // starts, whichever path the fact came in by
            blackboard.hide(episode.request)
            pendingEpisodes.getOrPut(rule) { ArrayDeque() }.add(episode)
            logger.debug("Process {} deferred {}: work that could end the process runs first", id, episode)
            return
        }
        if (activeEpisodes.putIfAbsent(rule, episode) == null) {
            episode.activate()
            groundOutcomeWindow(rule, episode)
            logger.debug("Process {} admitted {}", id, episode)
            return
        }
        blackboard.hide(episode.request)
        pendingEpisodes.getOrPut(rule) { ArrayDeque() }.add(episode)
        logger.debug("Process {} queued {}", id, episode)
    }

    /**
     * Complete the active episode and consume exactly its request. Whether
     * the next waiting episode starts is the caller's decision, because
     * work that could end the process gets its chance first.
     */
    private fun completeActiveEpisode(rule: DerivedEpisodeRule): Boolean {
        val episode = activeEpisodes.remove(rule) ?: return false
        blackboard.hide(episode.request)
        outcomeGroundings.remove(episode)?.forEach(blackboard::reveal)
        // The consumed occurrence releases its arrival bookkeeping: an
        // intentionally infinite process must not accumulate per-occurrence
        // state past completion
        admissionSeen.remove(episode.request)
        evolvedArrivals.remove(episode.request)
        episode.complete()
        lastCompletedEpisode = episode
        logger.debug("Process {} completed {}", id, episode)
        return true
    }

    private fun admitNext(rule: DerivedEpisodeRule) {
        val next = pendingEpisodes[rule]?.removeFirstOrNull() ?: return
        if (!blackboard.reveal(next.request)) {
            logger.warn(
                "Process {} admitted {} but its request was not hidden; queue state may be inconsistent",
                id,
                next,
            )
        }
        next.activate()
        activeEpisodes[rule] = next
        groundOutcomeWindow(rule, next)
        logger.debug("Process {} admitted queued {}", id, next)
    }

    private fun groundOutcomeWindow(rule: DerivedEpisodeRule, episode: Episode) {
        val consumableTypes = rule.consumableTypesByGoal.values.flatten().toSet()
        val shadowed = blackboard.objects.filter { instance ->
            instance !== episode.request && consumableTypes.any { it.isInstance(instance) }
        }
        shadowed.forEach(blackboard::hide)
        if (shadowed.isNotEmpty()) {
            outcomeGroundings[episode] = shadowed
        }
    }

    /**
     * Completes one episode without completing the process: consumes the
     * episode's request and exactly the objects this run made - its final
     * output and any intermediates - then keeps the process running so
     * normal planning resumes. The next waiting episode, if any, starts
     * once the process's own work is out of moves, and plans its chain
     * from scratch.
     */
    private fun completeEpisode(
        rule: DerivedEpisodeRule,
        goal: Goal,
        worldState: WorldState,
    ) {
        logger.debug(
            "🔁 Process {} completed episode goal {}; consuming and continuing",
            id,
            goal.name,
        )
        val consumedConsumables = consumeAttributed(rule, goal.name)
        val consumedRequest = completeActiveEpisode(rule)
        if (foundingWorkPlannable()) {
            logger.debug("Process {} deferring admission: work that could end the process runs first", id)
        } else {
            admitNext(rule)
        }
        if (!consumedRequest) {
            logger.warn(
                "Process {} episode goal {} completed with no active episode; " +
                        "no evolved occurrence was admitted for this completion",
                id,
                goal.name,
            )
        }
        if (!consumedRequest && consumedConsumables == 0) {
            logger.error(
                "Process {} episode goal {} completed but nothing was consumed; " +
                        "failing instead of spinning on a goal that will stay satisfied",
                id,
                goal.name,
            )
            setStatus(AgentProcessStatusCode.FAILED)
            return
        }
        if (makeRunning()) {
            // The event announces consumption and continuation, so it fires
            // only once both are true
            process.processContext.onProcessEvent(
                EpisodeCompletedEvent(
                    agentProcess = process,
                    worldState = worldState,
                    goal = goal,
                )
            )
        }
    }

    /**
     * Completion is anchored to the committed objective: in evolving mode
     * only the founding episode's goal completes the process, and no
     * objective means intentionally infinite. Outside evolving mode any
     * goal completes the process, as ever.
     */
    fun completesProcess(goalName: String): Boolean {
        val scope = derivedScope ?: return true
        return goalName in scope.objectiveGoals
    }

    /**
     * A goal outside any episode was achieved along the way: record it and
     * remove it from planning. The process keeps running - only the
     * declared objective ends it.
     */
    fun recordFrameAchievement(goalName: String) {
        achievedFrameGoals += goalName
        logger.info(
            "Process {} achieved goal {} along the way and keeps running: only the objective ends the process",
            id,
            goalName,
        )
        makeRunning()
    }

    fun completeFoundingEpisode() {
        val founding = foundingEpisode ?: return
        foundingShadow.forEach(blackboard::reveal)
        founding.complete()
        lastCompletedEpisode = founding
    }

    /**
     * True when the process's own work - any goal not owned by an episode
     * rule - still has a possible plan in the world as it stands after
     * consumption. While it does, that work runs before any waiting
     * episode starts, so whether the process can end never depends on how
     * its values compare with the next episode's.
     */
    private fun foundingWorkPlannable(): Boolean {
        if (derivedScope == null) {
            return false
        }
        return foundingGoals().any { planner.planToGoal(agent.planningSystem.actions, it) != null }
    }

    private fun foundingGoals(): List<Goal> =
        agent.goals.filter {
            it.name != NIRVANA.name && it.name !in achievedFrameGoals && !ownedByActivatedRule(it.name)
        }

    private fun ownedByActivatedRule(goalName: String): Boolean =
        derivedRules.any { it.matches(goalName) && it in activatedRules }

    /**
     * A process completing with occurrences still queued abandons them:
     * report the fact rather than dropping it silently.
     */
    fun reportAbandonedOccurrences() {
        val abandoned = pendingEpisodes.values.sumOf { it.size }
        if (abandoned > 0) {
            logger.info(
                "Process {} finished with {} pending occurrence(s) abandoned in queue",
                id,
                abandoned,
            )
        }
        val unowned = evolvedArrivals.count { it.value.owner == null }
        if (unowned > 0) {
            logger.info(
                "Process {} finished with {} evolved occurrence(s) that never found an owner",
                id,
                unowned,
            )
        }
    }

    /**
     * Consume the active episode's attributed consumables for the completed
     * candidate by identity: exactly what this occurrence made, nothing
     * made elsewhere. A failed child never merges, so a failed attempt
     * leaves nothing behind to sweep.
     */
    private fun consumeAttributed(rule: DerivedEpisodeRule, goalName: String): Int {
        val episode = activeEpisodes[rule] ?: return 0
        val consumed = episode.consumablesFrom(rule.chainActionsFor(goalName))
        consumed.forEach(blackboard::hide)
        if (consumed.isNotEmpty()) {
            logger.debug(
                "Process {} consumed {} attributed consumable(s)",
                id,
                consumed.size,
            )
        }
        return consumed.size
    }

    /**
     * Execute an action in the parent process. Episode chains only run in
     * child processes, so everything the parent itself runs is its own
     * long-running work. The only bookkeeping here is publisher tracking:
     * a fact this action publishes records the outermost episode as its
     * publisher.
     */
    fun runTrackingPublisher(action: Action, execute: () -> ActionStatus): ActionStatus {
        executingEpisode = foundingEpisode
        executingAction = action.name
        try {
            return execute()
        } finally {
            executingEpisode = null
            executingAction = null
        }
    }

    /**
     * Everything episodic happens inside an episode: a rule's exclusive
     * chain actions are plannable only while the rule has an active
     * episode, so a standing resource can never let the chain complete
     * outside an episode.
     */
    fun gatedChainActions(): Set<String> =
        // An activated rule's chain is never the parent planner's to run:
        // the framework dispatches it. The exclusion also holds against
        // value-driven planners, which pick any runnable action by value
        // without needing a goal to justify it
        derivedRules
            .filter { episodicNow(it) }
            .flatMapTo(mutableSetOf()) { it.exclusiveChainActions }

    /**
     * A rule becomes episodic at its first observed occurrence: before that
     * the goal is founding frame, and its chain runs ungated.
     */
    private fun episodicNow(rule: DerivedEpisodeRule): Boolean =
        rule in activatedRules

    /**
     * The planning system for the next planning round: goals already
     * achieved along the way are removed so finished work never outranks
     * the remaining work, and goals that belong to episodes are removed
     * because only dispatch may complete them - the parent can never plan
     * toward an episode's goal, not even when its condition already looks
     * satisfied.
     */
    fun planningSystem(): PlanningSystem {
        val withdrawn = achievedFrameGoals +
                activatedRules.flatMapTo(mutableSetOf()) { it.goalsByName.keys }
        if (withdrawn.isEmpty()) {
            return agent.planningSystem
        }
        val declared = agent.planningSystem
        return object : PlanningSystem {
            override val actions = declared.actions
            override val goals = declared.goals.filterNot { it.name in withdrawn }.toSet()
            override fun knownConditions() = declared.knownConditions()
            override fun infoString(verbose: Boolean?, indent: Int) = declared.infoString(verbose, indent)
        }
    }

    /**
     * Each planning round, the best active episode - ranked by its goal's
     * declared value - competes with the process's own best plan, and the
     * winner runs. One execution per round, so the process's own work and
     * its episodes interleave by value. Nothing is proven about a chain
     * before it runs: the child's run is the verdict, and an episode
     * whose child blocked waits for the blackboard to change before it is
     * tried again.
     */
    fun dispatchIfEpisodeWins(plan: Plan?, worldState: WorldState): Boolean {
        val choice = executor.choices(activeEpisodes, agent, worldState).maxByOrNull { it.value } ?: return false
        if (plan != null && plan.netValue(worldState) > choice.value) {
            return false
        }
        // A fact published from inside the child records the dispatching
        // episode as its publisher
        executingEpisode = choice.episode
        val execution = try {
            executor.execute(choice)
        } finally {
            executingEpisode = null
        }
        when (execution) {
            DispatchOutcome.COMPLETED -> completeEpisode(choice.rule, choice.goal, planner.worldState())
            DispatchOutcome.NOT_COMPLETED -> {
                // Contained failure or a stuck child: the occurrence is
                // intact and the next tick re-selects by value
            }
            DispatchOutcome.BUDGET_EXHAUSTED -> {
                reportAbandonedOccurrences()
                setStatus(AgentProcessStatusCode.TERMINATED)
                return true
            }
        }
        // FAILED is terminal here too: the anti-spin failsafe must never be
        // resurrected to RUNNING by the tail of its own dispatch
        if (process.status != AgentProcessStatusCode.TERMINATED &&
            process.status != AgentProcessStatusCode.FAILED
        ) {
            makeRunning()
        }
        return true
    }
}
