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
package com.embabel.agent.observability.observation;

import com.embabel.agent.api.common.PlannerType;
import com.embabel.agent.api.event.AgentProcessCreationEvent;
import com.embabel.agent.api.event.AgentProcessTerminatedEvent;
import com.embabel.agent.core.Agent;
import com.embabel.agent.core.AgentProcess;
import com.embabel.agent.core.Blackboard;
import com.embabel.agent.core.Goal;
import com.embabel.agent.core.ProcessOptions;
import com.embabel.agent.observability.ObservabilityProperties;
import io.micrometer.common.KeyValue;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationHandler;
import io.micrometer.observation.ObservationRegistry;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;

class EmbabelFullObservationEventListenerTest {

    @Test
    void terminatedAgentProcessStopsObservationWithTerminatedStatusWithoutError() {
        var registry = ObservationRegistry.create();
        var handler = new StoppedObservationHandler();
        registry.observationConfig().observationHandler(handler);
        var listener = new EmbabelFullObservationEventListener(registry, new ObservabilityProperties());
        var process = createMockAgentProcess("run-1", "TestAgent");

        listener.onProcessEvent(new AgentProcessCreationEvent(process));
        listener.onProcessEvent(new AgentProcessTerminatedEvent(process));

        assertThat(handler.stoppedContexts).hasSize(1);
        var stopped = handler.stoppedContexts.get(0);
        KeyValue status = stopped.getLowCardinalityKeyValue("embabel.agent.status");
        assertThat(status).isNotNull();
        assertThat(status.getValue()).isEqualTo("terminated");
        assertThat(stopped.getError()).isNull();
    }

    private static AgentProcess createMockAgentProcess(String runId, String agentName) {
        AgentProcess process = mock(AgentProcess.class);
        Agent agent = mock(Agent.class);
        Blackboard blackboard = mock(Blackboard.class);
        ProcessOptions processOptions = mock(ProcessOptions.class);
        Goal goal = mock(Goal.class);

        lenient().when(process.getId()).thenReturn(runId);
        lenient().when(process.getAgent()).thenReturn(agent);
        lenient().when(process.getBlackboard()).thenReturn(blackboard);
        lenient().when(process.getProcessOptions()).thenReturn(processOptions);
        lenient().when(process.getParentId()).thenReturn(null);
        lenient().when(process.getGoal()).thenReturn(goal);
        lenient().when(process.getFailureInfo()).thenReturn("cancelled by host");

        lenient().when(agent.getName()).thenReturn(agentName);
        lenient().when(agent.getGoals()).thenReturn(Set.of(goal));
        lenient().when(goal.getName()).thenReturn("TestGoal");

        lenient().when(blackboard.getObjects()).thenReturn(Collections.emptyList());
        lenient().when(blackboard.lastResult()).thenReturn(null);
        lenient().when(processOptions.getPlannerType()).thenReturn(PlannerType.GOAP);

        return process;
    }

    private static class StoppedObservationHandler implements ObservationHandler<Observation.Context> {

        private final List<Observation.Context> stoppedContexts = new ArrayList<>();

        @Override
        public boolean supportsContext(Observation.Context context) {
            return true;
        }

        @Override
        public void onStop(Observation.Context context) {
            stoppedContexts.add(context);
        }
    }
}
