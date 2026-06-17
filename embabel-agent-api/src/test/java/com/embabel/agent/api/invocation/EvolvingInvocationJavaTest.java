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
package com.embabel.agent.api.invocation;

import com.embabel.agent.api.annotation.AchievesGoal;
import com.embabel.agent.api.annotation.Action;
import com.embabel.agent.api.annotation.Agent;
import com.embabel.agent.api.common.scope.AgentScopeBuilder;
import com.embabel.agent.api.evolution.ObjectiveAuthor;
import com.embabel.agent.api.evolution.ObjectivePlan;
import com.embabel.agent.core.AgendaCompletionMode;
import com.embabel.agent.core.AgendaEntry;
import com.embabel.agent.core.AgendaLane;
import com.embabel.agent.core.AgentProcessStatusCode;
import com.embabel.agent.core.Goal;
import com.embabel.agent.test.integration.IntegrationTestUtils;
import org.junit.jupiter.api.Test;

import java.util.List;

import static com.embabel.agent.core.support.Nirvana.NIRVANA;
import static org.assertj.core.api.Assertions.assertThat;

class EvolvingInvocationJavaTest {

    record JavaSampleAvailable(String zone) {
    }

    record JavaSampleStored(String zone) {
    }

    record JavaCollectSamplesUntil(String zone, int target) {
    }

    @Agent(description = "Java capabilities for evolving invocation")
    static class JavaCollectionCapabilities {

        @Action
        @AchievesGoal(description = "Store a Java sample")
        public JavaSampleStored collect(JavaSampleAvailable sample) {
            return new JavaSampleStored(sample.zone());
        }
    }

    @Test
    void javaCanRunEvolvingInvocationWithObjectiveAuthor() {
        var agentPlatform = IntegrationTestUtils.dummyAgentPlatform();
        var objective = new JavaCollectSamplesUntil("zone-a", 1);
        ObjectiveAuthor objectiveAuthor = request -> {
            var collectSamplesUntil = request.objectiveAs(JavaCollectSamplesUntil.class);
            Goal sampleStoredGoal = request.getScope().getGoals().stream()
                .filter(goal -> JavaSampleStored.class.getName().equals(goal.getOutputType().getName()))
                .findFirst()
                .orElseThrow();
            return new ObjectivePlan(
                "java-collect-zone-a",
                List.of(AgendaEntry.of("java-collect-sample", sampleStoredGoal)
                    .withLane(AgendaLane.ECONOMIC)
                    .withCompletionMode(AgendaCompletionMode.TERMINAL)),
                List.of(new JavaSampleAvailable(collectSamplesUntil.zone())),
                null
            );
        };

        var result = EvolvingInvocation.on(agentPlatform)
            .withScope(AgentScopeBuilder.fromInstance(new JavaCollectionCapabilities()))
            .withObjectiveAuthor(objectiveAuthor)
            .run(objective);

        assertThat(result.getStatus()).isEqualTo(AgentProcessStatusCode.COMPLETED);
        assertThat(result.lastResult()).isEqualTo(new JavaSampleStored("zone-a"));
        assertThat(NIRVANA.getName()).isEqualTo("Nirvana");
    }
}
