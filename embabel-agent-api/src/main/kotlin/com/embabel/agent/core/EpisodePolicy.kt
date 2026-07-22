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
package com.embabel.agent.core

import java.util.List.copyOf

/**
 * Reference to one or more declared goals in the scope of an agent process.
 * Targets are resolved against the active scope at process creation and
 * never synthesize goals: they can only reference goals the agent declares.
 */
sealed interface GoalTarget {

    /**
     * All scoped declared goals whose output type is assignable to [satisfiedByType].
     * Several matches are alternative candidate goals: ordinary conditions and
     * planner selection choose among them, and the first completion counts.
     */
    data class Output(val satisfiedByType: Class<*>) : GoalTarget {
        override fun toString(): String = "output(${satisfiedByType.name})"
    }

    /**
     * Exactly one declared goal, referenced by stable name.
     */
    data class Named(val goalName: String) : GoalTarget {

        init {
            require(goalName.isNotBlank()) { "A named goal target requires a goal name" }
        }

        override fun toString(): String = "named($goalName)"
    }

    companion object {

        @JvmStatic
        fun output(satisfiedByType: Class<*>): GoalTarget = Output(satisfiedByType)

        @JvmStatic
        fun named(goalName: String): GoalTarget = Named(goalName)

    }

}

/**
 * The rule declaring one repeatable, nonterminal goal episode: a declared
 * goal reached via a runtime request occurrence. Completing an episode goal
 * does not complete the process. On completion the request and the completed
 * candidate's chain products (the satisfying output and any intermediates)
 * are hidden through existing identity-based [Blackboard.hide], so a later
 * occurrence replans the chain fresh. Product cleanup is conservative static
 * analysis over the candidate's possible producer paths; standing state an
 * action maintains for itself survives. Validation against the process scope
 * happens at process creation. The rule is construction-time configuration;
 * each admitted occurrence is a runtime episode.
 * @param target the candidate declared goal(s) this episode completes
 * @param consumes the request type whose occurrences drive this episode.
 * Must be an off-chain input required on every completion path of every
 * candidate, match that binding's type exactly, and use the default binding.
 * Null means infer it at process creation, permitted only when exactly one
 * such off-chain input exists.
 */
data class EpisodeRule @JvmOverloads constructor(
    val target: GoalTarget,
    val consumes: Class<*>? = null,
    val evolved: Boolean = false,
)

/**
 * Episode policy for an agent process. Attach via [ProcessOptions.withEpisodes].
 * An empty policy preserves ordinary goal completion behavior exactly.
 * Nonterminal completion and request/output consumption are one contract:
 * an episode always consumes on completion and never completes the process.
 * Policies are immutable; the fluent methods return new policies, and
 * validation against the process scope happens at process creation.
 */
class EpisodePolicy @JvmOverloads constructor(
    episodes: List<EpisodeRule> = emptyList(),
) {

    val episodes: List<EpisodeRule> = copyOf(episodes)

    init {
        val duplicated = this.episodes.groupBy { it.target }.filterValues { it.size > 1 }.keys
        require(duplicated.isEmpty()) {
            "Each goal target may appear in only one episode: ${duplicated.joinToString()}"
        }
    }

    /**
     * Add another episode for the given target.
     * Exposed to Java as addEpisode to avoid clashing with the static entry point.
     */
    @JvmName("addEpisode")
    fun episode(target: GoalTarget): EpisodePolicy =
        EpisodePolicy(episodes + EpisodeRule(target))

    /**
     * Set the request type the most recently added episode consumes on completion.
     * Required when the episode's goal path has more than one off-chain input.
     */
    fun consumeOnCompletion(requestType: Class<*>): EpisodePolicy {
        require(episodes.isNotEmpty()) {
            "consumeOnCompletion requires an episode: call episode(target) first"
        }
        require(!episodes.last().evolved) {
            "consumeOnCompletion and evolved are exclusive for the episode targeting ${episodes.last().target}"
        }
        require(episodes.last().consumes == null) {
            "consumeOnCompletion is already set for the episode targeting ${episodes.last().target}"
        }
        return EpisodePolicy(episodes.dropLast(1) + episodes.last().copy(consumes = requestType))
    }

    /**
     * Make the most recently added episode evolved-only: it admits only
     * occurrences published through the process's evolve entry point,
     * routed by eligible off-chain input type. No driver mapping exists;
     * designation rides each instance at publication.
     */
    fun evolved(): EpisodePolicy {
        require(episodes.isNotEmpty()) {
            "evolved requires an episode: call episode(target) first"
        }
        require(episodes.last().consumes == null) {
            "evolved and consumeOnCompletion are exclusive for the episode targeting ${episodes.last().target}"
        }
        require(!episodes.last().evolved) {
            "evolved is already set for the episode targeting ${episodes.last().target}"
        }
        return EpisodePolicy(episodes.dropLast(1) + episodes.last().copy(evolved = true))
    }

    override fun equals(other: Any?): Boolean =
        other is EpisodePolicy && other.episodes == episodes

    override fun hashCode(): Int = episodes.hashCode()

    override fun toString(): String = "EpisodePolicy(episodes=$episodes)"

    companion object {

        @JvmField
        val NONE = EpisodePolicy()

        /**
         * Start a policy with one episode for the given target.
         */
        @JvmStatic
        fun episode(target: GoalTarget): EpisodePolicy =
            EpisodePolicy(listOf(EpisodeRule(target)))

    }

}
