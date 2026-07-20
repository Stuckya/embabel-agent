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
 * One repeatable, nonterminal goal episode: a declared goal reached via a
 * runtime request occurrence. Completing an episode goal does not complete
 * the process. On completion the exact request occurrence and the products
 * manufactured on the goal path (the satisfying output and any intermediates)
 * are hidden through the existing blackboard API, so a later occurrence
 * replans the entire chain fresh. Self-maintained standing state survives.
 * @param target the candidate declared goal(s) this episode completes
 * @param consumes the request type consumed when the episode completes.
 * Null means infer it at process creation, which is only permitted when the
 * goal path has exactly one input type no scoped action produces.
 * @param interruptsCurrentAction reserved for cooperative interruption.
 * Must be false: rejected until that phase lands.
 */
data class Episode(
    val target: GoalTarget,
    val consumes: Class<*>? = null,
    val interruptsCurrentAction: Boolean = false,
)

/**
 * Episode policy for an agent process. Attach via [ProcessOptions.withEpisodes].
 * An empty policy preserves ordinary goal-completes-process behavior exactly.
 * Nonterminal completion and request/output consumption are one contract:
 * an episode always consumes on completion and never completes the process.
 */
data class EpisodePolicy(
    val episodes: List<Episode> = emptyList(),
) {

    /**
     * Add another episode for the given target.
     * Exposed to Java as addEpisode to avoid clashing with the static entry point.
     */
    @JvmName("addEpisode")
    fun episode(target: GoalTarget): EpisodePolicy =
        this.copy(episodes = episodes + Episode(target))

    /**
     * Set the request type the most recently added episode consumes on completion.
     * Required when the episode's goal path has more than one possible input.
     */
    fun consumeOnCompletion(requestType: Class<*>): EpisodePolicy {
        require(episodes.isNotEmpty()) {
            "consumeOnCompletion requires an episode: call episode(target) first"
        }
        return this.copy(
            episodes = episodes.dropLast(1) + episodes.last().copy(consumes = requestType)
        )
    }

    companion object {

        @JvmField
        val NONE = EpisodePolicy()

        /**
         * Start a policy with one episode for the given target.
         */
        @JvmStatic
        fun episode(target: GoalTarget): EpisodePolicy =
            EpisodePolicy(listOf(Episode(target)))

    }

}
