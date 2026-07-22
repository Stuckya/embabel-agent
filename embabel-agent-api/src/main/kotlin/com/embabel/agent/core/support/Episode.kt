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
 * One occurrence of a goal episode at runtime: the request that begins it,
 * the consumables its chain has made, and where it stands in the
 * serial-admission lifecycle. The request is the episode's percept in the
 * sense of AIMA 3e chapter 2: its arrival begins the episode, and its
 * consumption ends it. Episode rules are derived from the goal graph at
 * construction; each occurrence arriving through evolve becomes an
 * Episode. At most one Episode per rule is ACTIVE. Later arrivals wait
 * PENDING, hidden until admitted, so each chain binds exactly its own
 * request.
 *
 * Consumables follow AIMA's consumable-resource idea: instances a chain
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
        state = EpisodeState.ACTIVE
    }

    fun complete() {
        state = EpisodeState.COMPLETED
    }

    fun record(actionName: String, instance: Any) {
        consumables += AttributedConsumable(actionName, instance)
    }

    /** Consumables made by the given chain actions, in production order */
    fun consumablesFrom(chainActionNames: Set<String>): List<Any> =
        consumables.filter { it.actionName in chainActionNames }.map { it.instance }

    /** Everything recorded on this episode, in production order */
    fun allConsumables(): List<Any> = consumables.map { it.instance }

    override fun toString(): String =
        "Episode(state=$state, request=$request, consumables=${consumables.size}" +
                (causedBy?.let { ", causedBy=${it.request}" } ?: "") + ")"

}

/**
 * The founding episode's request: the percept that began the process-episode.
 * In evolving mode the process itself is the outermost episode, terminal from
 * within and episodic from a parent's level (AIMA 3e ch 2 p. 45: many
 * environments are episodic at higher levels; the tournament is not one of
 * its games). Its percept is the process's initial observations.
 */
internal data class FoundingPercept(val seeds: List<Any>)

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
