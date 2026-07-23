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
package architecture

import com.tngtech.archunit.base.DescribedPredicate
import com.tngtech.archunit.core.domain.JavaClass
import com.tngtech.archunit.core.importer.ImportOption
import com.tngtech.archunit.junit.AnalyzeClasses
import com.tngtech.archunit.junit.ArchTest
import com.tngtech.archunit.lang.ArchRule
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses
import org.junit.jupiter.api.Tag

@Tag("architecture")
@AnalyzeClasses(
    packages = ["com.embabel.agent.core.support"],
    importOptions = [ImportOption.DoNotIncludeTests::class],
)
class EvolvingArchitectureRulesTest {

    private val plannerGraphTypes =
        object : DescribedPredicate<JavaClass>("are planner graph or condition-planner types") {
            override fun test(javaClass: JavaClass): Boolean =
                javaClass.name in forbiddenPlannerTypes ||
                        javaClass.packageName.startsWith("com.embabel.plan.common.condition")
        }

    @ArchTest
    val evolvingRuntimeMustNotDependOnPlannerGraphTypes: ArchRule =
        noClasses()
            .that().haveSimpleName("EpisodeRuntime")
            .or().haveSimpleName("EpisodeExecutor")
            .should().dependOnClassesThat(plannerGraphTypes)
            .because(
                "the evolving runtime may execute planner directives but must never inspect " +
                        "actions, goals, plans, planning systems, or condition-planner internals"
            )

    private companion object {

        val forbiddenPlannerTypes = setOf(
            "com.embabel.agent.core.Goal",
            "com.embabel.plan.Action",
            "com.embabel.plan.Goal",
            "com.embabel.plan.Plan",
            "com.embabel.plan.PlanningSystem",
        )
    }
}
