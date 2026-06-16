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
package com.embabel.agent.api.termination;

import com.embabel.agent.api.annotation.AchievesGoal;
import com.embabel.agent.api.annotation.Action;
import com.embabel.agent.api.annotation.Agent;
import com.embabel.agent.api.annotation.support.AgentMetadataReader;
import com.embabel.agent.api.common.ActionContext;
import com.embabel.agent.core.AgentProcess;
import com.embabel.agent.core.AgentProcessStatusCode;
import com.embabel.agent.core.ProcessOptions;
import com.embabel.agent.core.support.InMemoryBlackboard;
import com.embabel.agent.core.support.SimpleAgentProcess;
import com.embabel.agent.domain.io.UserInput;
import com.embabel.agent.spi.support.DefaultPlannerFactory;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static com.embabel.agent.test.integration.IntegrationTestUtils.dummyPlatformServices;
import static org.assertj.core.api.Assertions.assertThat;

class ProcessCancellationTokenJavaTest {

    record PollingResult(String status) {
    }

    @Agent(description = "Java agent that polls cancellation from blocking action code")
    static class JavaCancellationPollingAgent {

        final CountDownLatch actionStarted = new CountDownLatch(1);
        final CountDownLatch cancellationObserved = new CountDownLatch(1);
        final AtomicBoolean observedCancellation = new AtomicBoolean(false);
        final AtomicReference<String> observedReason = new AtomicReference<>();

        @Action
        @AchievesGoal(description = "Observe cancellation")
        public PollingResult waitForCancellation(UserInput input, ActionContext context) throws InterruptedException {
            actionStarted.countDown();

            var token = context.getProcessContext().getCancellationToken();
            var deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (System.nanoTime() < deadline) {
                if (token.isCancellationRequested()) {
                    observedCancellation.set(true);
                    observedReason.set(token.getReason());
                    cancellationObserved.countDown();
                    return new PollingResult("cancelled");
                }
                Thread.sleep(10);
            }

            return new PollingResult("completed");
        }
    }

    @Test
    void javaActionCanPollCancellationTokenAfterAgentTerminationRequest() throws Exception {
        var platformServices = dummyPlatformServices();
        var blackboard = new InMemoryBlackboard();
        blackboard.addObject(new UserInput("Start blocking work"));

        var fixture = new JavaCancellationPollingAgent();
        var reader = new AgentMetadataReader();
        var agent = (com.embabel.agent.core.Agent) reader.createAgentMetadata(fixture);

        var agentProcess = new SimpleAgentProcess(
            "test-java-cancellation-token",
            null,
            agent,
            ProcessOptions.DEFAULT,
            blackboard,
            platformServices,
            DefaultPlannerFactory.INSTANCE,
            Instant.now()
        );

        var executor = Executors.newSingleThreadExecutor();
        try {
            var run = executor.submit(agentProcess::run);

            assertThat(fixture.actionStarted.await(1, TimeUnit.SECONDS)).isTrue();
            agentProcess.terminateAgent("host requested stop");

            assertThat(fixture.cancellationObserved.await(1, TimeUnit.SECONDS)).isTrue();
            AgentProcess result = run.get(2, TimeUnit.SECONDS);

            assertThat(result.getStatus()).isEqualTo(AgentProcessStatusCode.TERMINATED);
            assertThat(fixture.observedCancellation.get()).isTrue();
            assertThat(fixture.observedReason.get()).isEqualTo("host requested stop");
        } finally {
            executor.shutdownNow();
        }
    }
}
