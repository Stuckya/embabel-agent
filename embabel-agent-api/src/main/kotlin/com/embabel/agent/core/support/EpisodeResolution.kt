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
 * candidate; another candidate's products are not touched. Self-maintained
 * facts (a type some action both consumes and produces, such as an accumulator)
 * are never episode products and survive completion.
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
                    "Available goals: ${agent.goals.joinToString { it.name }}"
        }
        val chains = candidates.associate { it.name to analyzeGoalChain(it, agent) }
        val offChainInputTypes = chains.values.flatMapTo(linkedSetOf()) { it.offChainInputTypes }
        val consumes = episode.consumes
            ?.also { validateExplicitConsumes(episode, it, offChainInputTypes) }
            ?: inferConsumes(episode, offChainInputTypes)
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
            is GoalTarget.Output -> agent.goals.filter { satisfiesOutputTarget(it, target) }
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
        /** Input types on the goal path that no scoped action's effects can satisfy */
        val offChainInputTypes: Set<String>,
        /** Output types declared by the chain's actions, satisfying output included */
        val productTypes: Set<String>,
    )

    /**
     * Walk the condition graph the planner searches: from the goal's
     * preconditions to the actions whose effects satisfy them, then those
     * actions' preconditions, transitively. Action effects already encode the
     * planner's assignability rules (subtype and supertype outputs), so chain
     * membership here matches what the planner can actually route. An
     * input-binding condition no scoped action's effects satisfy is an
     * off-chain input: an observation the planner cannot manufacture. Named
     * conditions without producers are current truth and belong to neither set.
     */
    private fun analyzeGoalChain(goal: Goal, agent: Agent): GoalChain {
        val offChainInputTypes = linkedSetOf<String>()
        val chainActions = linkedSetOf<Action>()
        val visited = mutableSetOf<String>()
        val toWalk = ArrayDeque(requiredConditions(goal.preconditions))
        while (toWalk.isNotEmpty()) {
            val condition = toWalk.removeFirst()
            if (!visited.add(condition)) continue
            val producers = agent.actions.filter { producesCondition(it, condition) }
            if (producers.isEmpty()) {
                inputBindingType(condition)?.let { offChainInputTypes.add(it) }
                continue
            }
            producers
                .filter { chainActions.add(it) }
                .forEach { toWalk.addAll(requiredConditions(it.preconditions)) }
        }
        return GoalChain(
            offChainInputTypes = offChainInputTypes,
            productTypes = chainActions.flatMapTo(linkedSetOf()) { action -> action.outputs.map { it.type } },
        )
    }

    private fun producesCondition(action: Action, condition: String): Boolean =
        action.effects[condition] == ConditionDetermination.TRUE

    /**
     * The type of an input-binding condition, or null for a named condition.
     * Parsing delegates to [IoBinding] so the binding format has one owner.
     */
    private fun inputBindingType(condition: String): String? {
        if (":" !in condition) return null
        return IoBinding(condition).type
    }

    private fun requiredConditions(spec: EffectSpec): List<String> =
        spec.filterValues { it == ConditionDetermination.TRUE }.keys.toList()

    /**
     * A type an action both consumes and produces is self-maintained standing
     * state, such as an accumulator, never a per-occurrence episode product.
     */
    private fun isSelfMaintained(type: String, agent: Agent): Boolean =
        agent.actions.any { action ->
            action.inputs.any { it.type == type } && action.outputs.any { it.type == type }
        }

    /**
     * The consumed request must be something the goal path actually observes:
     * an off-chain input. Consuming an unrelated type would leave the real
     * request visible, so the episode could fire again without new work.
     */
    private fun validateExplicitConsumes(
        episode: Episode,
        explicit: Class<*>,
        offChainInputTypes: Set<String>,
    ) {
        val matchesOffChainInput = offChainInputTypes
            .mapNotNull { loadClassOrNull(it) }
            .any { offChain -> offChain.isAssignableFrom(explicit) || explicit.isAssignableFrom(offChain) }
        require(matchesOffChainInput) {
            "Episode target ${episode.target} cannot consume ${explicit.name}: it is not an off-chain " +
                    "input of the goal path. Off-chain inputs: " +
                    offChainInputTypes.joinToString().ifEmpty { "none" }
        }
    }

    /**
     * Infer the consumed request type as the single off-chain input on the goal
     * path. Such an input is an observation rather than a plannable product,
     * which is exactly what a request occurrence is. Anything else is ambiguous
     * and requires explicit consumeOnCompletion.
     */
    private fun inferConsumes(episode: Episode, offChainInputTypes: Set<String>): Class<*> {
        require(offChainInputTypes.isNotEmpty()) {
            "Cannot infer the consumed request for episode target ${episode.target}: " +
                    "the goal path has no off-chain input. Specify consumeOnCompletion explicitly"
        }
        require(offChainInputTypes.size == 1) {
            "Cannot infer the consumed request for episode target ${episode.target}: " +
                    "the goal path has multiple off-chain inputs: ${offChainInputTypes.joinToString()}. " +
                    "Specify consumeOnCompletion explicitly"
        }
        val typeName = offChainInputTypes.single()
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
