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
import com.embabel.agent.core.EpisodePolicy
import com.embabel.agent.core.EpisodeRule
import com.embabel.agent.core.Goal
import com.embabel.agent.core.GoalTarget
import com.embabel.agent.core.IoBinding
import com.embabel.agent.core.JvmType
import com.embabel.plan.common.condition.ConditionDetermination
import com.embabel.plan.common.condition.EffectSpec

/**
 * An [EpisodeRule] resolved against the goals and actions of a process scope.
 * @param goalsByName the candidate declared goals, keyed by name
 * @param consumes the resolved request type, explicit or inferred
 * @param consumableTypesByGoal for each candidate goal, the validated consumable types
 * its chain can manufacture: the satisfying output and any intermediates,
 * loaded and consumable. Standing state an action maintains for itself (its
 * effects satisfy its own input, as with an accumulator) is never a consumable.
 * @param chainActionsByGoal for each candidate goal, the names of the actions
 * the planner can route toward it: attribution membership.
 * @param attributedTypesByAction for each chain action, the consumable types
 * whose new instances are attributed to the active episode when it runs.
 * Completion consumes the attributed consumables of the completed candidate's
 * chain by identity, so an occurrence consumes exactly what it made: a stale
 * intermediate of its own cannot shortcut the next occurrence's plan, and
 * instances made elsewhere are used, not consumed.
 */
internal data class ResolvedEpisodeRule(
    val goalsByName: Map<String, Goal>,
    val consumes: Class<*>,
    val consumableTypesByGoal: Map<String, List<Class<*>>>,
    val chainActionsByGoal: Map<String, Set<String>>,
    val attributedTypesByAction: Map<String, List<Class<*>>>,
    val exclusiveChainActions: Set<String> = emptySet(),
) {

    fun matches(goalName: String): Boolean = goalName in goalsByName

    fun chainActionsFor(goalName: String): Set<String> = chainActionsByGoal[goalName].orEmpty()

    fun isChainAction(actionName: String): Boolean =
        chainActionsByGoal.values.any { actionName in it }

    fun attributedTypesFor(actionName: String): List<Class<*>> =
        attributedTypesByAction[actionName].orEmpty()

}

/**
 * Resolves an [EpisodePolicy] against an agent's declared goals at process
 * creation, failing fast on invalid configuration. Targets resolve only to
 * canonical declared goals; a target with a scoped producer that is currently
 * blocked by missing facts remains a planner concern and passes here.
 */
internal object EpisodeResolution {

    fun resolve(policy: EpisodePolicy, agent: Agent): List<ResolvedEpisodeRule> {
        val resolved = policy.episodes.map { resolveRule(it, agent) }
        val duplicated = resolved.groupBy { it.consumes }.filterValues { it.size > 1 }.keys
        require(duplicated.isEmpty()) {
            "Each request type may drive only one episode; consumed by multiple episodes: " +
                    duplicated.joinToString { it.name }
        }
        val goalOwner = mutableMapOf<String, Class<*>>()
        resolved
            .flatMap { resolved -> resolved.goalsByName.keys.map { it to resolved.consumes } }
            .forEach { (goalName, consumes) -> requireSingleOwner(goalOwner, goalName, consumes) }
        return withExclusiveChainActions(resolved, agent)
    }

    /**
     * An episode chain is plannable only while one of its episodes is
     * active, so a chain action serving no other goal is excluded from
     * planning when its rule has no active episode. Without the gate a
     * standing resource could let the chain complete driverless. Actions
     * shared with non-episode goals are never gated.
     */
    private fun withExclusiveChainActions(
        resolved: List<ResolvedEpisodeRule>,
        agent: Agent,
    ): List<ResolvedEpisodeRule> {
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

    private fun resolveRule(rule: EpisodeRule, agent: Agent): ResolvedEpisodeRule {
        val candidates = candidatesFor(rule, agent)
        require(candidates.isNotEmpty()) {
            "Episode target ${rule.target} resolves to no declared goal in scope. " +
                    "Available goals: ${agent.goals.joinToString { it.name }.ifEmpty { "none" }}"
        }
        val chains = candidates.associate { it.name to analyzeGoalChain(it, agent) }
        check(chains.size == candidates.size) {
            "Candidate goal names must be unique before chain analysis"
        }
        val requiredOnEveryPath = chains.values
            .map { it.requiredOffChainBindings }
            .reduce { a, b -> a intersect b }
        val consumes = rule.consumes
            ?.also { validateExplicitConsumes(rule, it, requiredOnEveryPath) }
            ?: inferConsumes(rule, requiredOnEveryPath)
        candidates.forEach { requireConsumableOutput(rule, it, agent) }
        val consumableTypesByGoal = chains.mapValues { (goalName, chain) ->
            chain.outputTypes
                .filterNot { isSelfMaintained(it, agent) }
                .map { loadConsumableClass(goalName, it) }
        }
        return ResolvedEpisodeRule(
            goalsByName = candidates.associateBy { it.name },
            consumes = consumes,
            consumableTypesByGoal = consumableTypesByGoal,
            chainActionsByGoal = chains.mapValues { (_, chain) ->
                chain.chainActions.mapTo(linkedSetOf()) { it.name }
            },
            attributedTypesByAction = attributedTypes(chains, consumableTypesByGoal),
        )
    }

    /**
     * For each chain action, the consumable types whose instances are
     * attributed to the active episode when the action executes: the
     * action's declared outputs, restricted to validated consumable types, so
     * self-maintained standing state is never attributed.
     */
    private fun attributedTypes(
        chains: Map<String, GoalChain>,
        consumableTypesByGoal: Map<String, List<Class<*>>>,
    ): Map<String, List<Class<*>>> {
        val byAction = mutableMapOf<String, MutableSet<Class<*>>>()
        chains.forEach { (goalName, chain) ->
            val consumables = consumableTypesByGoal[goalName].orEmpty()
            chain.chainActions.forEach { action ->
                val declared = action.outputs.mapTo(mutableSetOf()) { it.type }
                byAction.getOrPut(action.name) { linkedSetOf() } +=
                    consumables.filter { it.name in declared }
            }
        }
        return byAction.mapValues { it.value.toList() }
    }

    private fun loadConsumableClass(goalName: String, typeName: String): Class<*> =
        IoBinding(typeName).resolveJvmType()?.clazz
            ?: throw IllegalArgumentException(
                "Episode candidate $goalName produces $typeName, which cannot be loaded: " +
                        "every consumable must be a loadable JVM type"
            )

    private fun candidatesFor(rule: EpisodeRule, agent: Agent): List<Goal> =
        when (val target = rule.target) {
            is GoalTarget.Named -> namedCandidates(rule, target, agent)
            is GoalTarget.Output -> outputCandidates(rule, target, agent)
        }

    private fun outputCandidates(rule: EpisodeRule, target: GoalTarget.Output, agent: Agent): List<Goal> {
        val matches = agent.goals.filter { satisfiesOutputTarget(it, target) }
        requireDistinctNames(rule, matches)
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
     * A satisfying output must be a per-occurrence consumable. An output that is
     * standing state would survive consumption and keep the goal satisfied
     * forever, and a non-JVM output could never be hidden at all: either way
     * the episode could not rearm.
     */
    private fun requireConsumableOutput(rule: EpisodeRule, goal: Goal, agent: Agent) {
        val outputType = goal.outputType
        require(outputType is JvmType) {
            "Episode candidate ${goal.name} does not produce a JVM output type: " +
                    "its instances could never be consumed, so the episode could not rearm"
        }
        require(!isSelfMaintained(outputType.className, agent)) {
            "Episode candidate ${goal.name} is satisfied by ${outputType.className}, which is standing state " +
                    "an action maintains for itself: a satisfying output must be a per-occurrence " +
                    "consumable. Return a distinct completion type"
        }
    }

    private fun requireDistinctNames(rule: EpisodeRule, candidates: List<Goal>) {
        val duplicated = candidates.groupBy { it.name }.filterValues { it.size > 1 }.keys
        require(duplicated.isEmpty()) {
            "Episode target ${rule.target} resolves distinct goals sharing a name: " +
                    "${duplicated.joinToString()}; goal names must be unique to participate in an episode"
        }
    }

    private fun namedCandidates(rule: EpisodeRule, target: GoalTarget.Named, agent: Agent): List<Goal> {
        val matches = agent.goals.filter { it.name == target.goalName }
        require(matches.size <= 1) {
            "Episode target ${rule.target} resolves to ${matches.size} declared goals; " +
                    "a named target must identify exactly one"
        }
        return matches
    }

    private fun satisfiesOutputTarget(goal: Goal, target: GoalTarget.Output): Boolean =
        goal.outputType?.isAssignableTo(target.satisfiedByType) == true

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
        /** The actions the planner can route toward the goal */
        val chainActions: Set<Action>,
    ) {
        /** Output types the chain's actions declare, satisfying output included */
        val outputTypes: Set<String>
            get() = chainActions.flatMapTo(linkedSetOf()) { action -> action.outputs.map { it.type } }
    }

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
            chainActions = chainActions(goal, agent),
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
     * A chain action's output is standing state, never a per-occurrence
     * consumable, when some producer of it can sustain the type without a
     * fresh occurrence: either the producer's effects satisfy one of its own
     * required inputs (an accumulator, exact or subtype), or the producer
     * transitively requires no off-chain input at all (a multi-action cycle
     * such as a ping-pong pair). Both checks use the planner's own matching
     * rules.
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
     * Can this producer run using only what the scope regenerates on its own?
     * Any-path semantics: a producer is regenerable when some way of satisfying
     * each of its inputs needs no off-chain occurrence. Cycles sustain
     * themselves; named conditions are current truth; an off-chain binding is
     * an occurrence and blocks regeneration.
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

    /**
     * The driving request must be an off-chain input required on every
     * completion path of every candidate, must match the binding type exactly,
     * and must use the default binding. Serial admission pairs completions
     * with the active driver by identity, so mis-pairing is impossible; this
     * rule guards attribution instead: a goal reachable without the driver
     * could complete and consume an active driver whose work never ran.
     */
    private fun validateExplicitConsumes(
        rule: EpisodeRule,
        explicit: Class<*>,
        requiredOnEveryPath: Set<String>,
    ) {
        val binding = requiredOnEveryPath.firstOrNull { IoBinding(it).type == explicit.name }
        require(binding != null) {
            "Episode target ${rule.target} cannot consume ${explicit.name}: it is not an off-chain " +
                    "input required on every completion path. Required off-chain inputs: " +
                    describeBindings(requiredOnEveryPath)
        }
        requireDefaultBinding(rule, binding)
    }

    private fun requireDefaultBinding(rule: EpisodeRule, bindingCondition: String) {
        val bindingName = IoBinding(bindingCondition).name
        require(bindingName == IoBinding.DEFAULT_BINDING) {
            "Episode target ${rule.target} cannot consume a request bound as '$bindingName': " +
                    "named request bindings are not supported"
        }
    }

    private fun describeBindings(bindings: Set<String>): String =
        bindings.joinToString().ifEmpty { "none" }

    /**
     * Infer the consumed request type as the single off-chain input required on
     * every completion path. Such an input is an observation rather than a
     * plannable output, which is exactly what a request occurrence is.
     * Anything else is ambiguous and requires explicit consumeOnCompletion.
     */
    private fun inferConsumes(rule: EpisodeRule, requiredOnEveryPath: Set<String>): Class<*> {
        require(requiredOnEveryPath.isNotEmpty()) {
            "Cannot infer the consumed request for episode target ${rule.target}: " +
                    "no off-chain input is required on every completion path. " +
                    "Specify consumeOnCompletion explicitly"
        }
        require(requiredOnEveryPath.size == 1) {
            "Cannot infer the consumed request for episode target ${rule.target}: " +
                    "multiple off-chain inputs are required: ${describeBindings(requiredOnEveryPath)}. " +
                    "Specify consumeOnCompletion explicitly"
        }
        val binding = requiredOnEveryPath.single()
        requireDefaultBinding(rule, binding)
        return IoBinding(binding).resolveJvmType()?.clazz
            ?: throw IllegalArgumentException(
                "Cannot infer the consumed request for episode target ${rule.target}: " +
                        "cannot load inferred input type ${IoBinding(binding).type}. " +
                        "Specify consumeOnCompletion explicitly"
            )
    }

}
