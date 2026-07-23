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

import com.embabel.agent.core.OccurrenceId

/**
 * Runtime lifecycle record for one evolved occurrence.
 *
 * It deliberately contains no derived goal, action chain, consumable types,
 * planner score, or blocker analysis. Those are planning concerns.
 */
internal class Episode(
    val id: OccurrenceId,
    val request: Any,
    val causedBy: Episode? = null,
    val publishedBy: String? = null,
) {

    var state: EpisodeState = EpisodeState.PENDING
        private set

    var attemptCount: Int = 0
        private set

    var waitingSinceRevision: Long? = null
        private set

    fun run() {
        check(state == EpisodeState.PENDING || state == EpisodeState.STUCK) {
            "Only a PENDING or STUCK episode can run, not $state"
        }
        state = EpisodeState.RUNNING
        waitingSinceRevision = null
        attemptCount++
    }

    fun await(revision: Long) {
        check(state != EpisodeState.COMPLETED && state != EpisodeState.CANCELLED) {
            "A terminal episode cannot become STUCK: $state"
        }
        state = EpisodeState.STUCK
        waitingSinceRevision = revision
    }

    fun retry() {
        check(state == EpisodeState.RUNNING) { "Only a RUNNING episode can retry, not $state" }
        state = EpisodeState.PENDING
    }

    fun complete() {
        check(state == EpisodeState.RUNNING) { "Only a RUNNING episode can complete, not $state" }
        state = EpisodeState.COMPLETED
    }

    fun cancel() {
        check(state != EpisodeState.COMPLETED && state != EpisodeState.CANCELLED) {
            "A terminal episode cannot be cancelled: $state"
        }
        state = EpisodeState.CANCELLED
    }

    override fun toString(): String =
        "Episode(id=$id, state=$state, attempts=$attemptCount, request=$request" +
                (causedBy?.let { ", causedBy=${it.id}" } ?: "") + ")"
}

internal enum class EpisodeState {
    PENDING,
    RUNNING,
    STUCK,
    COMPLETED,
    CANCELLED,
}
