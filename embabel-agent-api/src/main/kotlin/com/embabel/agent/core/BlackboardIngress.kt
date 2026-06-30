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
import java.time.Instant
import java.util.UUID
import java.util.function.Supplier

interface BlackboardIngress {

    fun publish(
        fact: Any,
        options: IngressOptions = IngressOptions(),
    ): IngressReceipt

    fun publish(fact: Any): IngressReceipt = publish(fact, IngressOptions())

    fun <T : Any> update(
        trigger: ActivationTrigger<T>,
        active: Boolean,
        factSupplier: Supplier<T>,
    ): IngressReceipt? =
        if (active) {
            publish(factSupplier.get(), trigger.toIngressOptions())
        } else {
            clearActivationKey(trigger.key)
            null
        }

    fun <T : Any> occurred(
        trigger: ActivationTrigger<T>,
        fact: T,
    ): IngressReceipt? =
        publish(fact, trigger.toIngressOptions())

    fun clearActivationKey(activationKey: String) {}

    companion object {

        @JvmField
        val NONE: BlackboardIngress = object : BlackboardIngress {

            override fun publish(
                fact: Any,
                options: IngressOptions,
            ): IngressReceipt =
                IngressReceipt(
                    id = UUID.randomUUID().toString(),
                    factType = fact.javaClass.name,
                    publishedAt = Instant.now(),
                    options = options,
                )
        }
    }
}

data class IngressOptions @JvmOverloads constructor(
    val mode: IngressMode = IngressMode.APPEND,
    val wake: IngressWake = IngressWake.NONE,
    val coalesceKey: String? = null,
    val activationKey: String? = null,
    val ttl: Duration? = null,
)

enum class IngressMode {
    APPEND,
    LATEST,
}

enum class IngressWake {
    NONE,
    WAKE,
}

data class IngressReceipt(
    val id: String,
    val factType: String,
    val publishedAt: Instant,
    val options: IngressOptions,
)
