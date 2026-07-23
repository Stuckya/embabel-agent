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
package com.embabel.plan.common.condition

import com.embabel.agent.core.OccurrenceId
import com.embabel.plan.PlanningSystem
import com.embabel.plan.Planner

/**
 * Planner based on condition world states, such as GOAP.
 */
interface ConditionPlanner : Planner<ConditionPlanningSystem, ConditionWorldState, ConditionPlan> {

    /**
     * Capture the plans available before an occurrence is exposed to the
     * planner. The snapshot is planner-owned and opaque to the planning
     * session.
     */
    fun snapshot(system: PlanningSystem): ConditionPlanningSnapshot =
        ConditionPlanningSnapshot(emptySet())

    /**
     * Select the best plan enabled by the occurrence currently exposed in
     * the planner's world.
     *
     * This is an explicit planner decision. Evolving Mode never scans action
     * inputs, preconditions, effects, or goal graphs to infer relevance.
     */
    fun planForOccurrence(
        system: PlanningSystem,
        before: ConditionPlanningSnapshot,
        occurrenceId: OccurrenceId,
        occurrence: Any,
    ): ConditionPlan? = null
}

/**
 * Opaque planner state used to compare ordinary planning with planning in an
 * occurrence-specific world.
 */
class ConditionPlanningSnapshot internal constructor(
    internal val planIdentities: Set<ConditionPlanIdentity>,
)

internal data class ConditionPlanIdentity(
    val goalName: String,
    val actionNames: List<String>,
    val supportingEvidence: Map<String, Any>,
)
