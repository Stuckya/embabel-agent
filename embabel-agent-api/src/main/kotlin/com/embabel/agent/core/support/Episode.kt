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

/**
 * One run of an episode at runtime: the request that begins it, the
 * objects its actions have made, and where it stands in its lifecycle.
 * The request's arrival begins the episode and its consumption ends it.
 * Episode rules are derived from the goal graph at construction; each
 * fact arriving through evolve becomes an Episode. At most one Episode
 * per rule is ACTIVE. Later arrivals wait PENDING, hidden until admitted,
 * so each chain reads exactly its own request.
 *
 * Consumables are the per-run products: instances a chain
 * action made for this occurrence, recorded by identity as they appear.
 * Completion consumes the request and the completed candidate's consumables,
 * exactly what this occurrence made and nothing else. Off-chain inputs and
 * standing state are used, not consumed, and survive.
 */
internal class Episode(
    val request: Any,
    val causedBy: Episode? = null,
    val publishedBy: String? = null,
) {

    var state: EpisodeState = EpisodeState.PENDING
        private set

    private val consumables = mutableListOf<AttributedConsumable>()

    fun activate() {
        check(state == EpisodeState.PENDING) { "Only a PENDING episode can activate, not $state" }
        state = EpisodeState.ACTIVE
    }

    fun complete() {
        check(state == EpisodeState.ACTIVE) { "Only an ACTIVE episode can complete, not $state" }
        state = EpisodeState.COMPLETED
    }

    fun record(actionName: String, instance: Any) {
        consumables += AttributedConsumable(actionName, instance)
    }

    /** Consumables made by the given chain actions, in production order */
    fun consumablesFrom(chainActionNames: Set<String>): List<Any> =
        consumables.filter { it.actionName in chainActionNames }.map { it.instance }

    override fun toString(): String =
        "Episode(state=$state, request=$request, consumables=${consumables.size}" +
                (causedBy?.let { ", causedBy=${it.request}" } ?: "") + ")"

}

/**
 * The founding episode's request: the facts already on the blackboard when
 * the process was created. In evolving mode the whole process is treated
 * as one outermost episode, so even work belonging to no smaller episode
 * has an episode to answer for it, and a parent process can treat this
 * entire process as a single episode of its own.
 */
internal data class FoundingFacts(val seeds: List<Any>)

/**
 * A consumable attributed to the chain action that made it, so completion
 * can consume the completed candidate's consumables and no other's.
 */
internal data class AttributedConsumable(
    val actionName: String,
    val instance: Any,
)

internal enum class EpisodeState {
    PENDING,
    ACTIVE,
    COMPLETED,
}
