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
import com.embabel.agent.core.Episode
import com.embabel.agent.core.EpisodePolicy
import com.embabel.agent.core.Goal
import com.embabel.agent.core.GoalTarget
import com.embabel.agent.core.IoBinding
import com.embabel.agent.core.JvmType
import com.embabel.plan.common.condition.ConditionDetermination
import com.embabel.plan.common.condition.EffectSpec

/**
 * An [Episode] resolved against the goals and actions of a process scope.
 * @param goalsByName the candidate declared goals, keyed by name
 * @param consumes the resolved request type, explicit or inferred
 * @param productsByGoal for each candidate goal, the types manufactured on its
 * chain, consumed alongside the request when that candidate completes. Includes
 * the satisfying output and any intermediates, so a stale intermediate cannot
 * shortcut the next occurrence's plan. Consumption is scoped to the completed
 * candidate; another candidate's products are not touched. Standing state an
 * action maintains for itself (its effects satisfy its own input, as with an
 * accumulator) is never an episode product and survives completion.
 */
internal data class ResolvedEpisode(
    val episode: Episode,
    val goalsByName: Map<String, Goal>,
    val consumes: Class<*>,
    val productsByGoal: Map<String, List<Class<*>>>,
) {

    fun matches(goalName: String): Boolean = goalName in goalsByName

    fun productsFor(goalName: String): List<Class<*>> = productsByGoal[goalName] ?: emptyList()

}

/**
 * Resolves an [EpisodePolicy] against an agent's declared goals at process
 * creation, failing fast on invalid configuration. Targets resolve only to
 * canonical declared goals; a target with a scoped producer that is currently
 * blocked by missing facts remains a planner concern and passes here.
 */
internal object EpisodeResolution {

    fun resolve(policy: EpisodePolicy, agent: Agent): List<ResolvedEpisode> {
        val resolved = policy.episodes.map { resolveEpisode(it, agent) }
        val duplicated = resolved.groupBy { it.consumes }.filterValues { it.size > 1 }.keys
        require(duplicated.isEmpty()) {
            "Each request type may drive only one episode; consumed by multiple episodes: " +
                    duplicated.joinToString { it.name }
        }
        val goalOwner = mutableMapOf<String, Class<*>>()
        resolved
            .flatMap { episode -> episode.goalsByName.keys.map { it to episode.consumes } }
            .forEach { (goalName, consumes) -> requireSingleOwner(goalOwner, goalName, consumes) }
        return resolved
    }

    private fun resolveEpisode(episode: Episode, agent: Agent): ResolvedEpisode {
        val candidates = candidatesFor(episode, agent)
        require(candidates.isNotEmpty()) {
            "Episode target ${episode.target} resolves to no declared goal in scope. " +
                    "Available goals: ${agent.goals.joinToString { it.name }.ifEmpty { "none" }}"
        }
        candidates.forEach { requireConsumableOutput(episode, it, agent) }
        val chains = candidates.associate { it.name to analyzeGoalChain(it, agent) }
        check(chains.size == candidates.size) {
            "Candidate goal names must be unique before chain analysis"
        }
        val requiredOnEveryPath = chains.values
            .map { it.requiredOffChainBindings }
            .reduce { a, b -> a intersect b }
        val consumes = episode.consumes
            ?.also { validateExplicitConsumes(episode, it, requiredOnEveryPath) }
            ?: inferConsumes(episode, requiredOnEveryPath)
        return ResolvedEpisode(
            episode = episode,
            goalsByName = candidates.associateBy { it.name },
            consumes = consumes,
            productsByGoal = chains.mapValues { (_, chain) ->
                chain.productTypes
                    .filterNot { isSelfMaintained(it, agent) }
                    .mapNotNull { loadClassOrNull(it) }
            },
        )
    }

    private fun candidatesFor(episode: Episode, agent: Agent): List<Goal> =
        when (val target = episode.target) {
            is GoalTarget.Named -> namedCandidates(episode, target, agent)
            is GoalTarget.Output -> outputCandidates(episode, target, agent)
        }

    private fun outputCandidates(episode: Episode, target: GoalTarget.Output, agent: Agent): List<Goal> {
        val matches = agent.goals.filter { satisfiesOutputTarget(it, target) }
        requireDistinctNames(episode, matches)
        requireNamesUniqueInScope(matches, agent)
        return matches
    }

    /**
     * Completion recognition matches by goal name, so a candidate must not
     * share its name with any other scoped goal: an ordinary goal's completion
     * could otherwise be mistaken for the episode and consume its request.
     */
    private fun requireNamesUniqueInScope(candidates: List<Goal>, agent: Agent) {
        candidates.forEach { candidate ->
            require(agent.goals.count { it.name == candidate.name } == 1) {
                "Episode candidate ${candidate.name} shares its name with another scoped goal; " +
                        "goal names must be unique in scope to participate in an episode"
            }
        }
    }

    /**
     * A satisfying output must be a per-occurrence product. An output that is
     * standing state would survive consumption and keep the goal satisfied
     * forever, so the episode could never rearm.
     */
    private fun requireConsumableOutput(episode: Episode, goal: Goal, agent: Agent) {
        val outputTypeName = (goal.outputType as? JvmType)?.className ?: return
        require(!isSelfMaintained(outputTypeName, agent)) {
            "Episode candidate ${goal.name} is satisfied by $outputTypeName, which is standing state " +
                    "an action maintains for itself: a satisfying output must be a per-occurrence " +
                    "product. Return a distinct completion type"
        }
    }

    private fun requireDistinctNames(episode: Episode, candidates: List<Goal>) {
        val duplicated = candidates.groupBy { it.name }.filterValues { it.size > 1 }.keys
        require(duplicated.isEmpty()) {
            "Episode target ${episode.target} resolves distinct goals sharing a name: " +
                    "${duplicated.joinToString()}; goal names must be unique to participate in an episode"
        }
    }

    private fun namedCandidates(episode: Episode, target: GoalTarget.Named, agent: Agent): List<Goal> {
        val matches = agent.goals.filter { it.name == target.goalName }
        require(matches.size <= 1) {
            "Episode target ${episode.target} resolves to ${matches.size} declared goals; " +
                    "a named target must identify exactly one"
        }
        return matches
    }

    private fun satisfiesOutputTarget(goal: Goal, target: GoalTarget.Output): Boolean {
        val outputType = goal.outputType
        return outputType is JvmType && target.satisfiedByType.isAssignableFrom(outputType.clazz)
    }

    private fun requireSingleOwner(
        goalOwner: MutableMap<String, Class<*>>,
        goalName: String,
        consumes: Class<*>,
    ) {
        val prior = goalOwner.putIfAbsent(goalName, consumes)
        require(prior == null) {
            "Episodes consuming ${prior?.name} and ${consumes.name} both resolve to " +
                    "declared goal $goalName; each declared goal may belong to only one episode"
        }
    }

    private data class GoalChain(
        /** Off-chain input bindings required on every completion path to the goal */
        val requiredOffChainBindings: Set<String>,
        /** Output types declared by the chain's actions, satisfying output included */
        val productTypes: Set<String>,
    )

    /**
     * Analyze the condition graph the planner searches: from the goal's
     * preconditions to the actions whose effects satisfy them, then those
     * actions' preconditions, transitively — a static regression over the
     * goal's relevant conditions, the backward analogue of the planner's
     * forward search. Action effects already encode the planner's
     * assignability rules (subtype and supertype outputs), so chain
     * membership here matches what the planner can actually route. An
     * input-binding condition no scoped action's effects satisfy is an
     * off-chain input: an observation the planner cannot manufacture. Named
     * conditions without producers are current truth and belong to neither set.
     */
    private fun analyzeGoalChain(goal: Goal, agent: Agent): GoalChain =
        GoalChain(
            requiredOffChainBindings = requiredBindings(
                requiredConditions(goal.preconditions), agent, mutableMapOf(), mutableSetOf(),
            ),
            productTypes = chainProductTypes(goal, agent),
        )

    /**
     * Off-chain input bindings required on every completion path. A condition
     * satisfiable by any of several producers requires only what all producers
     * require (intersection); a producer requires everything its preconditions
     * require (union). Cycles contribute nothing: a self-produced type is
     * standing state, not a required observation.
     */
    private fun requiredBindings(
        conditions: Collection<String>,
        agent: Agent,
        memo: MutableMap<String, Set<String>>,
        inProgress: MutableSet<String>,
    ): Set<String> =
        conditions.flatMapTo(linkedSetOf()) { requiredBindingsFor(it, agent, memo, inProgress) }

    private fun requiredBindingsFor(
        condition: String,
        agent: Agent,
        memo: MutableMap<String, Set<String>>,
        inProgress: MutableSet<String>,
    ): Set<String> {
        memo[condition]?.let { return it }
        if (!inProgress.add(condition)) return emptySet()
        val required = computeRequiredBindings(condition, agent, memo, inProgress)
        inProgress.remove(condition)
        memo[condition] = required
        return required
    }

    private fun computeRequiredBindings(
        condition: String,
        agent: Agent,
        memo: MutableMap<String, Set<String>>,
        inProgress: MutableSet<String>,
    ): Set<String> {
        val producers = agent.actions.filter { producesCondition(it, condition) }
        if (producers.isEmpty()) return offChainBinding(condition)
        return producers
            .map { requiredBindings(requiredConditions(it.preconditions), agent, memo, inProgress) }
            .reduce { a, b -> a intersect b }
    }

    private fun offChainBinding(condition: String): Set<String> {
        if (":" !in condition) return emptySet()
        return setOf(condition)
    }

    private fun chainProductTypes(goal: Goal, agent: Agent): Set<String> {
        val chainActions = linkedSetOf<Action>()
        val visited = mutableSetOf<String>()
        val toWalk = ArrayDeque(requiredConditions(goal.preconditions))
        while (toWalk.isNotEmpty()) {
            val condition = toWalk.removeFirst()
            if (!visited.add(condition)) continue
            agent.actions
                .filter { producesCondition(it, condition) }
                .filter { chainActions.add(it) }
                .forEach { toWalk.addAll(requiredConditions(it.preconditions)) }
        }
        return chainActions.flatMapTo(linkedSetOf()) { action -> action.outputs.map { it.type } }
    }

    private fun producesCondition(action: Action, condition: String): Boolean =
        action.effects[condition] == ConditionDetermination.TRUE

    private fun requiredConditions(spec: EffectSpec): List<String> =
        spec.filterValues { it == ConditionDetermination.TRUE }.keys.toList()

    /**
     * A chain action's product is standing state, never a per-occurrence
     * episode product, when the action can sustain its own input: its effects
     * satisfy one of its required input conditions by the planner's own
     * matching rules. This covers exact accumulators (tally -> tally) and
     * subtype accumulators (tally -> RunningTally) alike.
     */
    private fun isSelfMaintained(type: String, agent: Agent): Boolean =
        agent.actions.any { maintainsOwnInputProducing(it, type) }

    private fun maintainsOwnInputProducing(action: Action, type: String): Boolean {
        if (action.outputs.none { it.type == type }) return false
        return requiredConditions(action.preconditions).any { producesCondition(action, it) }
    }

    /**
     * The consumed request must be an off-chain input required on every
     * completion path of every candidate, must match the binding type exactly,
     * and must use the default binding. Anything looser could pair the wrong
     * occurrence with a completion or leave the episode's real driver visible.
     */
    private fun validateExplicitConsumes(
        episode: Episode,
        explicit: Class<*>,
        requiredOnEveryPath: Set<String>,
    ) {
        val binding = requiredOnEveryPath.firstOrNull { IoBinding(it).type == explicit.name }
        require(binding != null) {
            "Episode target ${episode.target} cannot consume ${explicit.name}: it is not an off-chain " +
                    "input required on every completion path. Required off-chain inputs: " +
                    describeBindings(requiredOnEveryPath)
        }
        requireDefaultBinding(episode, binding)
    }

    private fun requireDefaultBinding(episode: Episode, bindingCondition: String) {
        val bindingName = IoBinding(bindingCondition).name
        require(bindingName == IoBinding.DEFAULT_BINDING) {
            "Episode target ${episode.target} cannot consume a request bound as '$bindingName': " +
                    "named request bindings are not supported in phase 1"
        }
    }

    private fun describeBindings(bindings: Set<String>): String =
        bindings.joinToString().ifEmpty { "none" }

    /**
     * Infer the consumed request type as the single off-chain input required on
     * every completion path. Such an input is an observation rather than a
     * plannable product, which is exactly what a request occurrence is.
     * Anything else is ambiguous and requires explicit consumeOnCompletion.
     */
    private fun inferConsumes(episode: Episode, requiredOnEveryPath: Set<String>): Class<*> {
        require(requiredOnEveryPath.isNotEmpty()) {
            "Cannot infer the consumed request for episode target ${episode.target}: " +
                    "no off-chain input is required on every completion path. " +
                    "Specify consumeOnCompletion explicitly"
        }
        require(requiredOnEveryPath.size == 1) {
            "Cannot infer the consumed request for episode target ${episode.target}: " +
                    "multiple off-chain inputs are required: ${describeBindings(requiredOnEveryPath)}. " +
                    "Specify consumeOnCompletion explicitly"
        }
        val binding = requiredOnEveryPath.single()
        requireDefaultBinding(episode, binding)
        val typeName = IoBinding(binding).type
        return loadClassOrNull(typeName)
            ?: throw IllegalArgumentException(
                "Cannot infer the consumed request for episode target ${episode.target}: " +
                        "cannot load inferred input type $typeName. Specify consumeOnCompletion explicitly"
            )
    }

    private fun loadClassOrNull(typeName: String): Class<*>? =
        try {
            Class.forName(typeName, true, Thread.currentThread().contextClassLoader)
        } catch (_: ClassNotFoundException) {
            null
        }

}
