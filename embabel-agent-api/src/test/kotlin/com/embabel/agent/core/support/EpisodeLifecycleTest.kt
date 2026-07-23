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

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertEquals

/**
 * The episode lifecycle is PENDING, ACTIVE, COMPLETED, in that order and
 * no other: the transitions themselves enforce it, so a runtime bug that
 * skipped or repeated a stage fails fast at the offending call site
 * instead of corrupting admission bookkeeping silently.
 */
class EpisodeLifecycleTest {

    @Test
    fun `an episode moves PENDING to ACTIVE to COMPLETED`() {
        val episode = Episode("request")
        assertEquals(EpisodeState.PENDING, episode.state)
        episode.activate()
        assertEquals(EpisodeState.ACTIVE, episode.state)
        episode.complete()
        assertEquals(EpisodeState.COMPLETED, episode.state)
    }

    @Test
    fun `completing a pending episode fails fast`() {
        assertThrows<IllegalStateException> { Episode("request").complete() }
    }

    @Test
    fun `activating an active episode fails fast`() {
        val episode = Episode("request").also(Episode::activate)
        assertThrows<IllegalStateException> { episode.activate() }
    }

    @Test
    fun `completing a completed episode fails fast`() {
        val episode = Episode("request").also(Episode::activate).also(Episode::complete)
        assertThrows<IllegalStateException> { episode.complete() }
    }
}
