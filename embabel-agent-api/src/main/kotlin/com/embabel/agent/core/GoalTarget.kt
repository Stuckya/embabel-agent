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
package com.embabel.agent.core


/**
 * Reference to one or more declared goals in the scope of an agent process.
 * Targets are resolved against the active scope at process creation and
 * never synthesize goals: they can only reference goals the agent declares.
 */
sealed interface GoalTarget {

    /**
     * All scoped declared goals whose output type is assignable to [satisfiedByType].
     * Several matches are alternative candidate goals: ordinary conditions and
     * planner selection choose among them, and the first completion counts.
     */
    data class Output(val satisfiedByType: Class<*>) : GoalTarget {
        override fun toString(): String = "output(${satisfiedByType.name})"
    }

    /**
     * Exactly one declared goal, referenced by stable name.
     */
    data class Named(val goalName: String) : GoalTarget {

        init {
            require(goalName.isNotBlank()) { "A named goal target requires a goal name" }
        }

        override fun toString(): String = "named($goalName)"
    }

    companion object {

        @JvmStatic
        fun output(satisfiedByType: Class<*>): GoalTarget = Output(satisfiedByType)

        @JvmStatic
        fun named(goalName: String): GoalTarget = Named(goalName)

    }

}
