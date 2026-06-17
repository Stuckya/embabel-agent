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
package com.embabel.agent.api.evolution;

import com.embabel.agent.core.AgendaCompletionMode;
import com.embabel.agent.core.AgendaEntry;
import com.embabel.agent.core.AgendaEntryApprovalRequest;
import com.embabel.agent.core.AgendaEntryApproved;
import com.embabel.agent.core.AgendaEntryApprover;
import com.embabel.agent.core.AgendaLane;
import com.embabel.agent.core.EvolutionOptions;
import com.embabel.agent.core.Goal;
import com.embabel.agent.core.GoalAgenda;
import com.embabel.agent.core.ProcessOptions;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class EvolutionOptionsJavaTest {

    record JavaEvolutionResult(String name) {
    }

    @Test
    void javaCanConfigureEvolutionOptionsAndAgendaApproverLambda() {
        Goal goal = Goal.createInstance("Handle Java evolution result", JavaEvolutionResult.class, "java-evolution-goal");
        AgendaEntry entry = AgendaEntry.of("java-entry", goal)
            .withBindings(Map.of("tenant", "test"))
            .withSource("java-test")
            .withLane(AgendaLane.ECONOMIC)
            .withCompletionMode(AgendaCompletionMode.TERMINAL)
            .withActivationKey("java-activation");
        AgendaEntryApprover approver = request -> new AgendaEntryApproved(request);
        EvolutionOptions evolution = new EvolutionOptions(
            GoalAgenda.EMPTY.withEntry(entry),
            approver
        );

        ProcessOptions options = ProcessOptions.DEFAULT.withEvolution(evolution);
        AgendaEntryApprovalRequest request = new AgendaEntryApprovalRequest(
            entry,
            "source-fact",
            String.class.getName(),
            AgendaLane.ECONOMIC,
            Map.of(),
            GoalAgenda.EMPTY,
            null
        );

        assertThat(options.getEvolution()).isSameAs(evolution);
        assertThat(options.getEvolution().getAgendaCatalog().getEntries()).containsExactly(entry);
        assertThat(options.getEvolution().getAgendaEntryApprover().approve(request).getApproved()).isTrue();
    }
}
