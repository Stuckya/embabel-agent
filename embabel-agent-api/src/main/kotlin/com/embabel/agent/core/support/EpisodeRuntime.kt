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
import com.embabel.agent.spi.PlannerFactory
import com.embabel.plan.Plan
import com.embabel.plan.PlanningSystem
import com.embabel.plan.WorldState
import com.embabel.plan.common.condition.WorldStateDeterminer
import org.slf4j.LoggerFactory
import java.util.Collections
import java.util.IdentityHashMap

/**
 * The episode machinery for one evolving process: derivation, admission,
 * routing, the visibility windows, evolve lineage, dispatch selection, and
 * completion bookkeeping. The process owns the tick loop and its status;
 * the runtime owns every episode decision inside it, borrowing exactly the
 * two process powers it declares - status and rearming. Outside evolving
 * mode evolve delegates up the tower or fails fast, and every other path
 * is inert.
 */
internal class EpisodeRuntime(
    private val process: SimpleAgentProcess,
    plannerFactory: PlannerFactory,
    worldStateDeterminer: WorldStateDeterminer,
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
        process.processOptions.evolving?.let { EpisodeResolution.deriveEvolving(agent, it.objective) }

    /**
     * Episode rules derived from the goal graph in evolving mode. Empty
     * outside evolving mode, where no episode machinery engages.
     */
    private val resolvedEpisodes: List<ResolvedEpisodeRule> =
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
    private val activatedRules = mutableSetOf<ResolvedEpisodeRule>()

    /**
     * The process-episode: in evolving mode the process itself is the
     * outermost episode, active from construction, terminal from within and
     * episodic from a parent's level. Its request is the founding percept,
     * the initial observations present at construction. Founding-frame work
     * executes inside it, so evolves it publishes record it as their cause.
     */
    private val foundingEpisode: Episode? =
        derivedScope?.let { Episode(FoundingPercept(blackboard.objects.toList())).also(Episode::activate) }

    /**
     * Founding-frame goals already achieved: recorded and withdrawn from
     * planning so the process resumes its mission instead of completing or
     * replaying them. The spot-welding robot reattaches the door and
     * resumes its work (AIMA 3e p. 422); only the objective ends the mission.
     */
    private val achievedFrameGoals = mutableSetOf<String>()

    /**
     * The founding shadow: a construction-time instance of the objective's
     * satisfying type is an ungrounded leftover - the mission's satisfying
     * instance must be its own product (AIMA 3e SS10.1) - and would
     * otherwise strand the mission, unsatisfied yet unplannable. Shadowed
     * here, revealed at founding completion.
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
     * chain action published it, if any: lineage captured at publication.
     * Designation rides the instance: an evolved fact is admitted to drive
     * an episode, while a plain addObject fact of the same type is
     * standing state.
     */
    private val evolvedArrivals: MutableMap<Any, EvolveOrigin> = IdentityHashMap()

    private data class EvolveOrigin(
        val causedBy: Episode?,
        val publishedBy: String?,
        val owner: ResolvedEpisodeRule? = null,
    )

    /**
     * The episode evolve attributes publications to right now: the founding
     * episode during a frame action, the dispatching episode during a child
     * run. Pure lineage state - no chain action ever executes here.
     */
    private var executingEpisode: Episode? = null

    /** The name of the frame action currently executing, if any */
    private var executingAction: String? = null

    /**
     * The most recently completed episode, retained for lineage inspection.
     * Completed episodes are otherwise discarded.
     */
    var lastCompletedEpisode: Episode? = null
        private set

    private val pendingEpisodes = mutableMapOf<ResolvedEpisodeRule, ArrayDeque<Episode>>()
    private val activeEpisodes = mutableMapOf<ResolvedEpisodeRule, Episode>()

    /**
     * Standing instances shadowed for an episode's outcome window: without
     * this, a founding-frame product of a consumable type would keep the
     * goal satisfied and the episode would complete vacuously, consuming
     * its occurrence without running its chain. Shadowed at admission,
     * revealed at completion: founding products are shadowed, never consumed.
     */
    private val outcomeGroundings: MutableMap<Episode, List<Any>> = IdentityHashMap()

    /**
     * The episode execution adapter: it runs already-selected
     * episodes and supplies the values selection ranks. Selection itself
     * stays with the planner.
     */
    private val executor: EpisodeExecutor by lazy {
        EpisodeExecutor(process, plannerFactory, worldStateDeterminer)
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
     * inside a child delegates up the tower to the nearest evolving
     * ancestor, so chain actions publish occurrences exactly as frame
     * actions do.
     */
    var evolveDelegate: ((Any) -> Unit)? = null

    /**
     * Publish a fact as an occurrence. The process evolves only at evolve
     * boundaries, and every evolution is handled inside an episode: the
     * instance is admitted to the rule that names its type as a required
     * off-chain input, serially, and consumed by identity on completion.
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

    private fun routableRules(fact: Any): List<ResolvedEpisodeRule> =
        resolvedEpisodes.filter { it.isEvolvedEligible(fact) }

    /**
     * Routing is planning: a contested arrival is owned by the rule whose
     * goal the planner values highest given the arrival, the same best-value
     * decision default mode makes over shared input types. Ownership is part
     * of the arrival boundary fact, decided once here and never renegotiated,
     * so a busy rule cannot lose an arrival that belongs to it on the merits.
     * Ties go to the first-declared candidate, matching the planner's own
     * stable ordering. Same-class competitors are hidden during the
     * competition, mirroring request grounding.
     */
    private fun routeByPlan(fact: Any, candidates: List<ResolvedEpisodeRule>): ResolvedEpisodeRule? {
        val competitors = blackboard.objects.filter { it !== fact && fact.javaClass.isInstance(it) }
        competitors.forEach(blackboard::hide)
        try {
            val scored = candidates.map { it to bestPlanValue(it) }
            val best = scored.maxByOrNull { it.second } ?: return null
            if (best.second == Double.NEGATIVE_INFINITY) {
                // Least commitment: no candidate can plan, so nothing owns
                // the occurrence yet - the choice stays unbound and is
                // retried when the world changes, then decided by merit
                return null
            }
            return best.first
        } finally {
            competitors.forEach(blackboard::reveal)
        }
    }

    /**
     * Routing shares dispatch's planning view: a candidate is valued by the
     * full-path plan its child would run, so a parent planner with no
     * full-path guarantee can never strand a contested occurrence the
     * dispatch could execute.
     */
    private fun bestPlanValue(rule: ResolvedEpisodeRule): Double =
        rule.goalsByName.values.maxOfOrNull { goal -> executor.chainValue(rule, goal, agent) }
            ?: Double.NEGATIVE_INFINITY

    /**
     * Fail fast at the boundary: an evolving process's evolvable types are
     * its enforced contract. Without this, a mis-deployed publisher would
     * believe work was scheduled while nothing ever admits the fact. In
     * evolving mode the message also carries why derivation excluded goals,
     * so the publisher learns what the graph could not support.
     */
    private fun requireRoutable(fact: Any, candidates: List<ResolvedEpisodeRule>) {
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
        resolvedEpisodes.flatMap { it.evolvedEligible }.joinToString { it.simpleName }

    fun admitArrivals() {
        resolveDeferredRouting()
        resolvedEpisodes.forEach(::admitArrivalsFor)
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
     * Resume admissions deferred at an episode boundary once founding-scope
     * work is no longer plannable: terminal evaluation precedes rearming.
     */
    private fun admitDeferred() {
        if (derivedScope == null) {
            return
        }
        val idleWithPending = resolvedEpisodes.filter {
            activeEpisodes[it] == null && !pendingEpisodes[it].isNullOrEmpty()
        }
        if (idleWithPending.isEmpty() || foundingWorkPlannable()) {
            return
        }
        idleWithPending.forEach(::admitNext)
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
     * A rule admits only instances published through [evolve], routed to
     * their owning rule fixed at the arrival boundary.
     */
    private fun arrivalsFor(rule: ResolvedEpisodeRule): List<Any> =
        blackboard.objects.filter { routesTo(rule, it) }

    /**
     * An evolved arrival routes to its owning rule. Uncontested arrivals
     * are owned at the evolve boundary; contested ones at the next tick,
     * before any admission scan runs.
     */
    private fun routesTo(rule: ResolvedEpisodeRule, instance: Any): Boolean =
        evolvedArrivals[instance]?.owner == rule

    private fun admitOrQueue(rule: ResolvedEpisodeRule, episode: Episode) {
        activatedRules += rule
        // An occurrence reopens the question: the goal was achieved as
        // state, and a fresh request makes it unachieved by definition
        achievedFrameGoals -= rule.goalsByName.keys
        if (activeEpisodes[rule] == null && foundingWorkPlannable()) {
            // Terminal evaluation precedes rearming for fresh arrivals too:
            // plannable founding work runs before any admission re-arms the
            // rule, whichever path the occurrence came in by
            blackboard.hide(episode.request)
            pendingEpisodes.getOrPut(rule) { ArrayDeque() }.add(episode)
            logger.debug("Process {} deferred {}: terminal evaluation precedes admission", id, episode)
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
     * Complete the active episode: consume its request by identity. Rearm
     * is the caller's decision, because terminal evaluation sits between
     * consumption and the next admission.
     */
    private fun completeActiveEpisode(rule: ResolvedEpisodeRule): Boolean {
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

    private fun admitNext(rule: ResolvedEpisodeRule) {
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

    private fun groundOutcomeWindow(rule: ResolvedEpisodeRule, episode: Episode) {
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
     * Completes a goal episode without completing the process: consumes the
     * active episode's request and its attributed consumables (satisfying
     * output and any intermediates this occurrence made) by identity, then keeps the process running so ordinary
     * selection resumes at the next planning tick. The next pending episode,
     * if any, is admitted once no founding-scope work is plannable - terminal
     * evaluation precedes rearming - and replans the entire chain fresh.
     */
    private fun completeEpisode(
        rule: ResolvedEpisodeRule,
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
            logger.debug("Process {} deferring admission: terminal evaluation precedes rearming", id)
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
     * An incidental founding-frame achievement is recorded and withdrawn
     * from planning; the process resumes instead of completing.
     */
    fun recordFrameAchievement(goalName: String) {
        achievedFrameGoals += goalName
        logger.info(
            "Process {} achieved frame goal {} and resumes: completion is anchored to the objective",
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
     * Terminal evaluation precedes rearming: at an episode boundary,
     * founding-scope work that is plannable runs before any pending
     * occurrence is admitted, so termination never depends on value tuning
     * between a terminal plan and the next episode. Founding scope is every
     * goal not owned by an activated rule, evaluated on the
     * post-consumption world.
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
        resolvedEpisodes.any { it.matches(goalName) && it in activatedRules }

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
    private fun consumeAttributed(rule: ResolvedEpisodeRule, goalName: String): Int {
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
     * Execute a frame action. Under one execution model everything the
     * parent runs is founding-frame work - episode chains execute only in
     * children - so the only bookkeeping is lineage: an evolve the action
     * publishes records the founding episode as its cause.
     */
    fun executingInFrame(action: Action, execute: () -> ActionStatus): ActionStatus {
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
        // the framework dispatches it. The gate holds against opportunistic
        // value selection, which picks achievable actions without a goal
        resolvedEpisodes
            .filter { episodicNow(it) }
            .flatMapTo(mutableSetOf()) { it.exclusiveChainActions }

    /**
     * A rule becomes episodic at its first observed occurrence: before that
     * the goal is founding frame, and its chain runs ungated.
     */
    private fun episodicNow(rule: ResolvedEpisodeRule): Boolean =
        rule in activatedRules

    /**
     * The planning system for the next tick: achieved founding-frame goals
     * are withdrawn so a satisfied incident never outcompetes the mission,
     * and activated rules' goals are withdrawn because they belong to the
     * dispatch machinery - the parent can never plan toward or complete an
     * episodic goal, not even vacuously off a satisfied condition.
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
     * Selection is the planner's: active episodes are
     * valued by the plans their children would run and compete with frame
     * work on net value. One execution per tick, so standing work and
     * other episodes interleave exactly as value dictates. An unplannable
     * chain is never a candidate, so a blocked episode never spawns a
     * doomed child.
     */
    fun dispatchIfEpisodeWins(plan: Plan?, worldState: WorldState): Boolean {
        val choice = executor.choices(activeEpisodes, agent).maxByOrNull { it.value } ?: return false
        if (plan != null && plan.netValue(worldState) > choice.value) {
            return false
        }
        // An evolve delegated up from inside the child records the
        // dispatching episode as its cause: lineage rides the dispatch
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
