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
import com.embabel.agent.core.Goal
import com.embabel.agent.core.GoalTarget
import com.embabel.agent.core.IoBinding
import com.embabel.agent.core.JvmType
import com.embabel.plan.common.condition.ConditionDetermination
import com.embabel.plan.common.condition.EffectSpec

/**
 * An episode rule derived from a declared goal's graph when a fact first
 * asks for it - not at process creation, because a running process may
 * change. Rules are per goal: one goal, one rule.
 * @param goalsByName the rule's goal, keyed by name
 * @param evolvedEligible the loaded types an evolved instance may arrive
 * under for this rule: the input types every path to the goal needs but
 * no chain action produces
 * @param consumableTypesByGoal for each candidate goal, the validated consumable types
 * its chain can manufacture: the satisfying output and any intermediates,
 * loaded and consumable. Standing state an action maintains for itself (its
 * effects satisfy its own input, as with an accumulator) is never a consumable.
 * @param chainActionsByGoal for each candidate goal, the names of the actions
 * the planner can route toward it.
 * @param exclusiveChainActions the chain actions serving no non-episode
 * goal, gated from planning whenever the activated rule has no active
 * episode
 */
internal data class DerivedEpisodeRule(
    val goalsByName: Map<String, Goal>,
    val evolvedEligible: List<Class<*>> = emptyList(),
    val consumableTypesByGoal: Map<String, List<Class<*>>>,
    val chainActionsByGoal: Map<String, Set<String>>,
    val exclusiveChainActions: Set<String> = emptySet(),
) {

    fun matches(goalName: String): Boolean = goalName in goalsByName

    fun chainActionsFor(goalName: String): Set<String> = chainActionsByGoal[goalName].orEmpty()

    fun isEvolvedEligible(instance: Any): Boolean =
        evolvedEligible.any { it.isInstance(instance) }

}

/**
 * Every rule derived for an evolving process, plus the goals derivation
 * excluded and why. Exclusion is not rejection: a goal that cannot support
 * episodes never asked for them, and its recorded reason surfaces at the
 * evolve call site that needed the goal to be evolvable.
 */
internal data class DerivedEvolvingScope(
    val rules: List<DerivedEpisodeRule>,
    val exclusions: Map<String, String>,
    val objectiveGoals: Set<String>,
)

/**
 * Derives episode rules from an agent's declared goals, at first need.
 * A goal with a scoped producer that is currently blocked by missing facts
 * remains a planner concern and derives normally here.
 */
internal object EpisodeDerivation {

    /**
     * Derive an episode rule for every declared goal whose graph supports
     * one: everything the goal graph can state is derived, never declared.
     * A goal the graph cannot support is excluded with its reason, which
     * surfaces at the evolve call site that needed it. Contested arrival
     * types are legal and routed by value at arrival.
     */
    fun deriveEvolving(agent: Agent, objective: GoalTarget?): DerivedEvolvingScope {
        val objectiveGoals = resolveObjective(objective, agent)
        val rules = mutableListOf<DerivedEpisodeRule>()
        val exclusions = mutableMapOf<String, String>()
        agent.goals.forEach { goal ->
            if (goal.name in objectiveGoals) {
                // The objective ends the whole process, so it must never
                // repeat as an episode: an episodic objective would consume
                // its own completion and start again forever
                exclusions[goal.name] = "the committed objective ends the process, so it never repeats as an episode"
                return@forEach
            }
            try {
                rules += deriveRule(goal, agent)
            } catch (e: UnderivableGoalException) {
                exclusions[goal.name] = e.message ?: "underivable"
            }
        }
        return DerivedEvolvingScope(
            rules = withExclusiveChainActions(rules, agent),
            exclusions = exclusions,
            objectiveGoals = objectiveGoals,
        )
    }

    /**
     * The committed objective: the goals whose completion completes the
     * process. Empty means intentionally infinite. An objective naming no
     * declared goal fails fast.
     */
    fun resolveObjective(objective: GoalTarget?, agent: Agent): Set<String> {
        if (objective == null) {
            return emptySet()
        }
        val candidates = agent.goals.filter { matchesTarget(it, objective) }
        require(candidates.isNotEmpty()) {
            "Evolving objective $objective resolves to no declared goal in scope. " +
                    "Available goals: ${agent.goals.joinToString { it.name }.ifEmpty { "none" }}"
        }
        if (objective is GoalTarget.Named) {
            require(candidates.size == 1) {
                "Evolving objective $objective resolves to ${candidates.size} declared goals; " +
                        "a named target must identify exactly one"
            }
        }
        return candidates.mapTo(mutableSetOf()) { it.name }
    }

    private fun matchesTarget(goal: Goal, target: GoalTarget): Boolean =
        when (target) {
            is GoalTarget.Named -> goal.name == target.goalName
            is GoalTarget.Output -> satisfiesOutputTarget(goal, target)
        }

    /**
     * An episode chain is plannable only while one of its episodes is
     * active, so a chain action serving no other goal is excluded from
     * planning when its rule has no active episode. Without the gate a
     * standing resource could let the chain complete with no admitted episode. Actions
     * shared with non-episode goals are never gated.
     */
    private fun withExclusiveChainActions(
        resolved: List<DerivedEpisodeRule>,
        agent: Agent,
    ): List<DerivedEpisodeRule> {
        val episodeGoalNames = resolved.flatMapTo(mutableSetOf()) { it.goalsByName.keys }
        val nonEpisodeChain = agent.goals
            .filterNot { it.name in episodeGoalNames }
            .flatMapTo(mutableSetOf()) { goal -> chainActions(goal, agent).map { it.name } }
        return resolved.map { rule ->
            rule.copy(
                exclusiveChainActions = rule.chainActionsByGoal.values
                    .flatMapTo(mutableSetOf()) { it } - nonEpisodeChain
            )
        }
    }

    private fun deriveRule(goal: Goal, agent: Agent): DerivedEpisodeRule {
        requireNamesUniqueInScope(listOf(goal), agent)
        val chain = chainActions(goal, agent)
        val evolvedEligible = evolvedEligibleTypes(goal, chain)
        requireConsumableOutput(goal, agent)
        val consumables = chain
            .flatMapTo(linkedSetOf()) { action -> action.outputs.map { it.type } }
            .filterNot { isSelfMaintained(it, agent) }
            .map { loadConsumableClass(goal.name, it) }
        return DerivedEpisodeRule(
            goalsByName = mapOf(goal.name to goal),
            evolvedEligible = evolvedEligible,
            consumableTypesByGoal = mapOf(goal.name to consumables),
            chainActionsByGoal = mapOf(goal.name to chain.mapTo(linkedSetOf()) { it.name }),
        )
    }

    /**
     * The types an evolved fact may arrive under for this rule: the input
     * types the goal's chain reads but cannot make itself, loaded. Some
     * path through the chain needs each of them; whether the path chosen
     * at run time does is the run's business. An evolved fact of any other
     * type never goes to this rule.
     */
    private fun evolvedEligibleTypes(goal: Goal, chain: Set<Action>): List<Class<*>> {
        val produced = chain.flatMapTo(mutableSetOf()) { action -> action.outputs.map { it.type } }
        val eligible = chain
            .flatMap { it.inputs }
            .filter { it.name == IoBinding.DEFAULT_BINDING }
            .map { it.type }
            .distinct()
            .filterNot { it in produced }
            .map { type ->
                IoBinding(type).resolveJvmType()?.clazz
                    ?: throw UnderivableGoalException(
                        "Goal ${goal.name} cannot load required input " +
                                "$type: evolved occurrences must be loadable JVM types"
                    )
            }
        requireDerivable(eligible.isNotEmpty()) {
            "Goal ${goal.name} has no required input that its own chain does not produce: " +
                    "nothing can be evolved for it"
        }
        return eligible
    }

    private fun loadConsumableClass(goalName: String, typeName: String): Class<*> =
        IoBinding(typeName).resolveJvmType()?.clazz
            ?: throw UnderivableGoalException(
                "Episode candidate $goalName produces $typeName, which cannot be loaded: " +
                        "every consumable must be a loadable JVM type"
            )

    /**
     * Completion recognition matches by goal name, so a candidate must not
     * share its name with any other scoped goal: an ordinary goal's completion
     * could otherwise be mistaken for the episode and consume its request.
     */
    private fun requireNamesUniqueInScope(candidates: List<Goal>, agent: Agent) {
        candidates.forEach { candidate ->
            requireDerivable(agent.goals.count { it.name == candidate.name } == 1) {
                "Episode candidate ${candidate.name} shares its name with another scoped goal; " +
                        "goal names must be unique in scope to participate in an episode"
            }
        }
    }

    /**
     * A goal's output must be something each run makes fresh and completion
     * can consume. An output that doubles as long-lived shared state would
     * survive consumption and keep the goal satisfied forever, and an
     * output with no JVM class could never be hidden at all: either way
     * the goal could never run again as a fresh episode.
     */
    private fun requireConsumableOutput(goal: Goal, agent: Agent) {
        val outputType = goal.outputType
        if (outputType !is JvmType) {
            throw UnderivableGoalException(
                "Episode candidate ${goal.name} does not produce a JVM output type: " +
                        "its instances could never be consumed, so the goal could never run again as a fresh episode"
            )
        }
        requireDerivable(!isSelfMaintained(outputType.className, agent)) {
            "Episode candidate ${goal.name} is satisfied by ${outputType.className}, which is standing state " +
                    "an action maintains for itself: a satisfying output must be a per-occurrence " +
                    "consumable. Return a distinct completion type"
        }
    }

    private fun satisfiesOutputTarget(goal: Goal, target: GoalTarget.Output): Boolean =
        goal.outputType?.isAssignableTo(target.satisfiedByType) == true

    private fun chainActions(goal: Goal, agent: Agent): Set<Action> {
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
        return chainActions
    }

    private fun producesCondition(action: Action, condition: String): Boolean =
        action.effects[condition] == ConditionDetermination.TRUE

    private fun requiredConditions(spec: EffectSpec): List<String> =
        spec.filterValues { it == ConditionDetermination.TRUE }.keys.toList()

    /**
     * A chain action's output is standing state, never a per-run
     * consumable, when some producer of it sustains the type without a
     * fresh occurrence: either the producer's effects satisfy one of its
     * own required inputs, as with a running total, or a cycle of actions
     * regenerates the type with no outside input at all, as with a pair
     * feeding each other. Deleting the cycle check was tried: hiding
     * cycle-maintained state blocked every chain that reads it.
     */
    private fun isSelfMaintained(type: String, agent: Agent): Boolean =
        agent.actions
            .filter { producesType(it, type) }
            .any { producer -> maintainsOwnInput(producer) || regenerableWithoutOffChainInput(producer, agent) }

    private fun producesType(action: Action, type: String): Boolean =
        action.outputs.any { it.type == type }

    private fun maintainsOwnInput(action: Action): Boolean =
        requiredConditions(action.preconditions).any { producesCondition(action, it) }

    /**
     * Can this producer run using only what the scope regenerates on its
     * own? A producer is regenerable when some way of satisfying each of
     * its inputs needs no outside occurrence: cycles sustain themselves,
     * named conditions are current truth, and an input no action produces
     * is an occurrence and blocks regeneration.
     */
    private fun regenerableWithoutOffChainInput(action: Action, agent: Agent): Boolean =
        requiredConditions(action.preconditions).all { canRegenerate(it, agent, mutableSetOf()) }

    private fun canRegenerate(
        condition: String,
        agent: Agent,
        inProgress: MutableSet<String>,
    ): Boolean {
        if (!inProgress.add(condition)) return true
        val result = computeCanRegenerate(condition, agent, inProgress)
        inProgress.remove(condition)
        return result
    }

    private fun computeCanRegenerate(
        condition: String,
        agent: Agent,
        inProgress: MutableSet<String>,
    ): Boolean {
        val producers = agent.actions.filter { producesCondition(it, condition) }
        if (producers.isEmpty()) return ":" !in condition
        return producers.any { producer ->
            requiredConditions(producer.preconditions).all { canRegenerate(it, agent, inProgress) }
        }
    }

}

/**
 * The derivation protocol's rejection: thrown when a goal's graph cannot
 * support an episode rule, caught by derivation and surfaced as the goal's
 * exclusion reason at the evolve boundary. A plain IllegalArgumentException
 * from deeper code is a bug and propagates out of construction.
 */
internal class UnderivableGoalException(message: String) : IllegalArgumentException(message)

private inline fun requireDerivable(condition: Boolean, message: () -> String) {
    if (!condition) {
        throw UnderivableGoalException(message())
    }
}
