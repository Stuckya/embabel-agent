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

import com.embabel.agent.core.EpisodePolicy;
import com.embabel.agent.core.GoalTarget;
import com.embabel.agent.core.ProcessOptions;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Pins the Java surface of the episode API. Subsequent episodes are exposed to
 * Java as addEpisode(...) because the instance method would otherwise clash
 * with the static entry point at the JVM level.
 */
public class GoalEpisodePolicyJavaTest {

    @Test
    void fluentPolicyConstructionFromJava() {
        var policy = EpisodePolicy
                .episode(GoalTarget.output(String.class))
                .consumeOnCompletion(Integer.class)
                .addEpisode(GoalTarget.named("secondary"))
                .consumeOnCompletion(Long.class);

        assertEquals(2, policy.getEpisodes().size());
        assertEquals(Integer.class, policy.getEpisodes().get(0).getConsumes());
        assertEquals(GoalTarget.named("secondary"), policy.getEpisodes().get(1).getTarget());

        var options = ProcessOptions.DEFAULT.withEpisodes(policy);
        assertEquals(policy, options.getEpisodes());
    }
}
