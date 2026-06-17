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

import java.time.Duration
import java.util.function.Function

data class ActivationTrigger<T : Any> @JvmOverloads constructor(
    val key: String,
    val factType: Class<T>,
    val kind: ActivationTriggerKind,
    val mode: IngressMode = IngressMode.APPEND,
    val wake: IngressWake = IngressWake.NONE,
    val coalesceKey: String? = null,
    val ttl: Duration? = null,
    val occurrenceIdExtractor: Function<T, Any>? = null,
    val hideOnInactive: Boolean = false,
) {

    fun wake(wake: IngressWake): ActivationTrigger<T> =
        copy(wake = wake)

    fun latest(coalesceKey: String): ActivationTrigger<T> =
        copy(mode = IngressMode.LATEST, coalesceKey = coalesceKey)

    fun ttl(ttl: Duration?): ActivationTrigger<T> =
        copy(ttl = ttl)

    fun hideOnInactive(): ActivationTrigger<T> =
        copy(hideOnInactive = true)

    fun withHideOnInactive(hideOnInactive: Boolean): ActivationTrigger<T> =
        copy(hideOnInactive = hideOnInactive)

    fun occurrenceId(occurrenceIdExtractor: Function<T, Any>): ActivationTrigger<T> =
        copy(occurrenceIdExtractor = occurrenceIdExtractor)

    fun occurrenceId(fact: T): Any =
        requireNotNull(occurrenceIdExtractor) {
            "Occurrence activation trigger $key requires an occurrenceId extractor"
        }.apply(fact)

    fun toIngressOptions(): IngressOptions =
        IngressOptions(
            mode = mode,
            wake = wake,
            coalesceKey = coalesceKey,
            activationKey = key,
            ttl = ttl,
        )

    companion object {

        @JvmStatic
        fun <T : Any> level(
            key: String,
            factType: Class<T>,
        ): ActivationTrigger<T> =
            ActivationTrigger(
                key = key,
                factType = factType,
                kind = ActivationTriggerKind.LEVEL,
            )

        @JvmStatic
        fun <T : Any> occurrence(
            key: String,
            factType: Class<T>,
        ): ActivationTrigger<T> =
            ActivationTrigger(
                key = key,
                factType = factType,
                kind = ActivationTriggerKind.OCCURRENCE,
            )
    }
}

enum class ActivationTriggerKind {
    LEVEL,
    OCCURRENCE,
}
