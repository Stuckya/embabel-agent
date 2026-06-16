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

import org.jetbrains.annotations.ApiStatus

/**
 * Pollable view of process cancellation state for blocking action code.
 */
interface ProcessCancellationToken {

    /**
     * True when the current process or action has been asked to stop at a bounded checkpoint.
     */
    val isCancellationRequested: Boolean

    /**
     * Human-readable cancellation reason, when one is available.
     */
    val reason: String?

    companion object {

        @JvmField
        val NONE: ProcessCancellationToken = object : ProcessCancellationToken {
            override val isCancellationRequested: Boolean
                get() = false

            override val reason: String?
                get() = null
        }
    }
}

@ApiStatus.Internal
interface ProcessCancellationTokenProvider {

    val cancellationToken: ProcessCancellationToken
}
