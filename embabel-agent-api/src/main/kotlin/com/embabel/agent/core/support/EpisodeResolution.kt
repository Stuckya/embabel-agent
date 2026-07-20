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

import com.embabel.agent.core.Agent
import com.embabel.agent.core.Episode
import com.embabel.agent.core.EpisodePolicy
import com.embabel.agent.core.Goal
import com.embabel.agent.core.GoalTarget
import com.embabel.agent.core.JvmType

/**
 * An [Episode] resolved against the goals and actions of a process scope.
 * @param goalsByName the candidate declared goals, keyed by name
 * @param consumes the resolved request type, explicit or inferred
 */
internal data class ResolvedEpisode(
    val episode: Episode,
    val goalsByName: Map<String, Goal>,
    val consumes: Class<*>,
) {

    fun matches(goalName: String): Boolean = goalName in goalsByName

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
        return resolved
    }

    private fun resolveEpisode(episode: Episode, agent: Agent): ResolvedEpisode {
        require(!episode.interruptsCurrentAction) {
            "interruptsCurrentAction is not yet supported: cooperative interruption is a later phase"
        }
        val candidates = when (val target = episode.target) {
            is GoalTarget.Named -> agent.goals.filter { it.name == target.goalName }
            is GoalTarget.Output -> agent.goals.filter { goal ->
                val outputType = goal.outputType
                outputType is JvmType && target.satisfiedByType.isAssignableFrom(outputType.clazz)
            }
        }
        require(candidates.isNotEmpty()) {
            "Episode target ${episode.target} resolves to no declared goal in scope. " +
                    "Available goals: ${agent.goals.joinToString { it.name }}"
        }
        val consumes = episode.consumes ?: inferConsumes(episode, candidates, agent)
        return ResolvedEpisode(
            episode = episode,
            goalsByName = candidates.associateBy { it.name },
            consumes = consumes,
        )
    }

    /**
     * Infer the consumed request type as the single input type on the goal path
     * that no scoped action produces. Such an off-chain input is an observation
     * rather than a plannable product, which is exactly what a request occurrence
     * is. Anything else is ambiguous and requires explicit consumeOnCompletion.
     */
    private fun inferConsumes(episode: Episode, candidates: List<Goal>, agent: Agent): Class<*> {
        val producedTypes = agent.actions.flatMap { action -> action.outputs.map { it.type } }.toSet()
        val offChainInputs = linkedSetOf<String>()
        val visited = mutableSetOf<String>()
        val toWalk = ArrayDeque(candidates.mapNotNull { (it.outputType as? JvmType)?.className })
        while (toWalk.isNotEmpty()) {
            val outputType = toWalk.removeFirst()
            if (!visited.add(outputType)) continue
            agent.actions
                .filter { action -> action.outputs.any { it.type == outputType } }
                .flatMap { it.inputs }
                .forEach { input ->
                    if (input.type in producedTypes) {
                        toWalk.add(input.type)
                    } else {
                        offChainInputs.add(input.type)
                    }
                }
        }
        require(offChainInputs.size == 1) {
            if (offChainInputs.isEmpty())
                "Cannot infer the consumed request for episode target ${episode.target}: " +
                        "the goal path has no off-chain input. Specify consumeOnCompletion explicitly"
            else
                "Cannot infer the consumed request for episode target ${episode.target}: " +
                        "the goal path has multiple off-chain inputs: ${offChainInputs.joinToString()}. " +
                        "Specify consumeOnCompletion explicitly"
        }
        val typeName = offChainInputs.single()
        return try {
            Class.forName(typeName, true, Thread.currentThread().contextClassLoader)
        } catch (e: ClassNotFoundException) {
            throw IllegalArgumentException(
                "Cannot infer the consumed request for episode target ${episode.target}: " +
                        "cannot load inferred input type $typeName. Specify consumeOnCompletion explicitly",
                e,
            )
        }
    }

}
