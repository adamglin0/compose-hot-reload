/*
 * Copyright 2024-2025 JetBrains s.r.o. and Compose Hot Reload contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
 */

package org.jetbrains.compose.reload.orchestration

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.callbackFlow
import org.jetbrains.compose.reload.core.Disposable
import java.lang.ref.WeakReference
import java.util.concurrent.BlockingQueue
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.AtomicReference

public fun OrchestrationHandle.asBlockingQueue(): BlockingQueue<OrchestrationMessage> {
    val queue = LinkedBlockingQueue<OrchestrationMessage>()
    val queueReference = WeakReference(queue)
    val registration = AtomicReference<Disposable>(null)
    registration.set(invokeWhenMessageReceived { message ->
        val queue = queueReference.get()
        if (queue == null) {
            registration.getAndSet(null)?.dispose()
        } else {
            queue.put(message)
        }
    })
    return queue
}

public fun OrchestrationHandle.asFlow(): Flow<OrchestrationMessage> = callbackFlow {
    val registration = invokeWhenMessageReceived { message ->
        val result = trySend(message)
        if (!result.isClosed) {
            result.getOrThrow()
        }
    }

    invokeWhenClosed {
        close()
    }

    awaitClose {
        registration.dispose()
    }
}.buffer(Channel.UNLIMITED)

public fun OrchestrationHandle.asChannel(): ReceiveChannel<OrchestrationMessage> {
    val channel = Channel<OrchestrationMessage>(Channel.UNLIMITED)
    val registration = invokeWhenMessageReceived { message ->
        val result = channel.trySend(message)
        if (!result.isClosed) {
            result.getOrThrow()
        }
    }
    channel.invokeOnClose { registration.dispose() }
    invokeWhenClosed { channel.close() }
    return channel
}
