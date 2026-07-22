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
package com.embabel.plan;

import com.embabel.agent.core.GoalTarget;
import com.embabel.agent.core.ProcessOptions;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * The evolving-mode declaration surface from Java: the mode declares once,
 * the objective rides inside it, and omitting the objective is the
 * intentionally infinite form.
 */
class GoalEvolvingJavaTest {

    @Test
    void evolvingDeclarationWithObjectiveFromJava() {
        ProcessOptions options = ProcessOptions.DEFAULT
                .withEvolving(GoalTarget.output(CalibrationCompleted.class));

        assertNotNull(options.getEvolving(), "The mode is declared");
        GoalTarget objective = options.getEvolving().getObjective();
        assertEquals(
                CalibrationCompleted.class,
                ((GoalTarget.Output) objective).getSatisfiedByType(),
                "The committed objective rides inside the declaration");
    }

    @Test
    void evolvingWithoutObjectiveIsIntentionallyInfinite() {
        ProcessOptions options = ProcessOptions.DEFAULT.withEvolving();

        assertNotNull(options.getEvolving());
        assertNull(options.getEvolving().getObjective(), "No objective means intentionally infinite");
    }
}
