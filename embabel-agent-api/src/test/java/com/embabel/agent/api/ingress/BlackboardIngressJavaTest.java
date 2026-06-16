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
package com.embabel.agent.api.ingress;

import com.embabel.agent.api.annotation.AchievesGoal;
import com.embabel.agent.api.annotation.Action;
import com.embabel.agent.api.annotation.Agent;
import com.embabel.agent.api.annotation.support.AgentMetadataReader;
import com.embabel.agent.api.event.BlackboardIngressDrainedEvent;
import com.embabel.agent.core.AgentProcess;
import com.embabel.agent.core.AgentProcessStatusCode;
import com.embabel.agent.core.IngressMode;
import com.embabel.agent.core.IngressOptions;
import com.embabel.agent.core.IngressWake;
import com.embabel.agent.core.ProcessOptions;
import com.embabel.agent.core.support.InMemoryBlackboard;
import com.embabel.agent.core.support.SimpleAgentProcess;
import com.embabel.agent.spi.support.DefaultPlannerFactory;
import com.embabel.agent.test.common.EventSavingAgenticEventListener;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static com.embabel.agent.test.integration.IntegrationTestUtils.dummyPlatformServices;
import static org.assertj.core.api.Assertions.assertThat;

class BlackboardIngressJavaTest {

    record JavaIngressFact(String name) {
    }

    record JavaIngressResult(String name) {
    }

    @Agent(description = "Java agent that consumes ingress facts")
    static class JavaIngressAgent {

        @Action
        @AchievesGoal(description = "Handle a Java ingress fact")
        public JavaIngressResult handle(JavaIngressFact fact) {
            return new JavaIngressResult(fact.name());
        }
    }

    @Test
    void javaCanPublishFactThroughProcessContextIngress() {
        var listener = new EventSavingAgenticEventListener();
        var platformServices = dummyPlatformServices(listener);
        var blackboard = new InMemoryBlackboard();
        var reader = new AgentMetadataReader();
        var agent = (com.embabel.agent.core.Agent) reader.createAgentMetadata(new JavaIngressAgent());
        var agentProcess = new SimpleAgentProcess(
            "test-java-ingress",
            null,
            agent,
            ProcessOptions.DEFAULT,
            blackboard,
            platformServices,
            DefaultPlannerFactory.INSTANCE,
            Instant.now()
        );
        var fact = new JavaIngressFact("Duke");

        var receipt = agentProcess.getProcessContext().getIngress().publish(
            fact,
            new IngressOptions(
                IngressMode.APPEND,
                IngressWake.NONE,
                "java-fact",
                "java-activation",
                null
            )
        );

        assertThat(blackboard.getObjects()).doesNotContain(fact);

        AgentProcess result = agentProcess.run();

        assertThat(result.getStatus()).isEqualTo(AgentProcessStatusCode.COMPLETED);
        assertThat(blackboard.getObjects()).contains(fact);
        assertThat(blackboard.getCondition("java-activation")).isEqualTo(true);
        assertThat((JavaIngressResult) blackboard.lastResult()).isEqualTo(new JavaIngressResult("Duke"));

        var drainedEvents = listener.getProcessEvents().stream()
            .filter(BlackboardIngressDrainedEvent.class::isInstance)
            .map(BlackboardIngressDrainedEvent.class::cast)
            .toList();
        assertThat(drainedEvents).hasSize(1);
        assertThat(drainedEvents.getFirst().getReceipt()).isEqualTo(receipt);
        assertThat(drainedEvents.getFirst().getFact()).isEqualTo(fact);
    }
}
