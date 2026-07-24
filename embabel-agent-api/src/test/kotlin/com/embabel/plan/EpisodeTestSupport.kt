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
package com.embabel.plan

import com.embabel.agent.api.event.AgentProcessEvent
import com.embabel.agent.api.event.AgenticEventListener
import com.embabel.agent.api.event.AbstractAgentProcessEvent
import com.embabel.agent.api.event.EpisodeFinishedEvent
import com.embabel.agent.api.event.EpisodeStartedEvent
import com.embabel.agent.core.AgentProcess
import java.util.concurrent.CopyOnWriteArrayList

internal class ProcessEventRecorder : AgenticEventListener {

    val events = CopyOnWriteArrayList<AgentProcessEvent>()

    override fun onProcessEvent(event: AgentProcessEvent) {
        events += event
    }

    fun episodeStarts(process: AgentProcess): List<EpisodeStartedEvent> =
        events.filterIsInstance<EpisodeStartedEvent>()
            .filter { it.agentProcess.id == process.id }

    fun episodeFinishes(process: AgentProcess): List<EpisodeFinishedEvent> =
        events.filterIsInstance<EpisodeFinishedEvent>()
            .filter { it.agentProcess.id == process.id }

    fun childProcesses(process: AgentProcess): List<AgentProcess> =
        events.asSequence()
            .filterIsInstance<AbstractAgentProcessEvent>()
            .map(AbstractAgentProcessEvent::agentProcess)
            .filter { it.parentId == process.id }
            .distinctBy(AgentProcess::id)
            .toList()
}
