/*
 * Copyright 2024-2025 JetBrains s.r.o. and Compose Hot Reload contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
 */

package org.jetbrains.compose.reload.orchestration

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.toList
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.jetbrains.compose.reload.orchestration.OrchestrationClientRole.Unknown
import org.jetbrains.compose.reload.orchestration.OrchestrationMessage.ClientConnected
import org.jetbrains.compose.reload.orchestration.OrchestrationMessage.ClientDisconnected
import org.jetbrains.compose.reload.orchestration.OrchestrationMessage.LogMessage
import org.jetbrains.compose.reload.orchestration.OrchestrationMessage.TestEvent
import org.junit.jupiter.api.AfterEach
import java.util.Collections.synchronizedList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.fail

class ServerClientTest {

    private val resources = mutableListOf<AutoCloseable>()

    fun <T : AutoCloseable> use(value: T): T {
        resources.add(value)
        return value
    }

    @AfterEach
    fun cleanup() {
        resources.forEach { it.close() }
    }

    @Test
    fun `test - simple ping pong`() = runTest {
        val server = use(startOrchestrationServer())
        val serverMessages = server.asChannel()

        val client = use(connectOrchestrationClient(Unknown, server.port))

        val clientMessages = client.asChannel()

        val serverReceivedMessages = mutableListOf<OrchestrationMessage>()
        val clientReceivedMessages = mutableListOf<OrchestrationMessage>()

        server.invokeWhenMessageReceived { message ->
            if (message is ClientConnected) return@invokeWhenMessageReceived
            serverReceivedMessages.add(message)
        }

        client.invokeWhenMessageReceived { message ->
            if (message is ClientConnected) return@invokeWhenMessageReceived
            clientReceivedMessages.add(message)
        }

        client.sendMessage(LogMessage("A")).get()

        while (true) {
            if (clientMessages.receive() is LogMessage) break
        }

        while (true) {
            if (serverMessages.receive() is LogMessage) break
        }

        orchestrationThread.submit {
            assertEquals(listOf(LogMessage("A")), serverReceivedMessages.toList())
            assertEquals(listOf(LogMessage("A")), clientReceivedMessages.toList())
        }.get()
    }


    @Test
    fun `test - single shot request`() = runTest {
        val server = use(startOrchestrationServer())
        val client = use(connectOrchestrationClient(Unknown, server.port))
        val serverMessages = server.asChannel()

        val serverReceivedMessages = synchronizedList(mutableListOf<OrchestrationMessage>())
        val clientReceivedMessages = synchronizedList(mutableListOf<OrchestrationMessage>())

        server.invokeWhenMessageReceived { message ->
            if (message is ClientConnected) return@invokeWhenMessageReceived
            if (message is ClientDisconnected) return@invokeWhenMessageReceived
            serverReceivedMessages.add(message)
        }

        client.invokeWhenMessageReceived { message ->
            if (message is ClientConnected) return@invokeWhenMessageReceived
            if (message is ClientDisconnected) return@invokeWhenMessageReceived
            clientReceivedMessages.add(message)
        }

        client.use { client ->
            client.sendMessage(LogMessage("A"))
        }

        val logMessage = serverMessages.receiveAsFlow().filterIsInstance<LogMessage>().first()
        assertEquals("A", logMessage.message)

        orchestrationThread.submit {
            assertEquals(listOf(LogMessage("A")), serverReceivedMessages.toList())
        }.get()
    }


    @Test
    fun `test - multiple messages`() = runTest {
        val server = use(startOrchestrationServer())
        val client = use(connectOrchestrationClient(Unknown, server.port))
        val serverChannel = server.asChannel()
        val clientChannel = client.asChannel()

        launch {
            val firstLog = serverChannel.receiveAsFlow().filterIsInstance<LogMessage>().first()
            assertEquals("A", firstLog.message)

            val secondLog = serverChannel.receiveAsFlow().filterIsInstance<LogMessage>().first()
            assertEquals("B", secondLog.message)
        }


        launch {
            val firstLog = clientChannel.receiveAsFlow().filterIsInstance<LogMessage>().first()
            assertEquals("A", firstLog.message)

            val secondLog = clientChannel.receiveAsFlow().filterIsInstance<LogMessage>().first()
            assertEquals("B", secondLog.message)
        }

        client.sendMessage(LogMessage("A"))
        client.sendMessage(LogMessage("B"))
    }

    @Test
    fun `test - client connected and client disconnected messages`() = runTest {
        val server = use(startOrchestrationServer())
        val serverChannel = server.asChannel()

        val client = use(connectOrchestrationClient(Unknown, server.port))

        val connected = serverChannel.receiveAsFlow().filterIsInstance<ClientConnected>().first()
        assertEquals(client.clientId, connected.clientId)
        assertEquals(Unknown, connected.clientRole)

        client.close()

        val disconnected = serverChannel.receiveAsFlow().filterIsInstance<ClientDisconnected>().first()
        assertEquals(client.clientId, disconnected.clientId)
        assertEquals(Unknown, disconnected.clientRole)
    }

    @Test
    fun `stress test - incoming message flood`() = runTest {
        repeat(8) { iteration ->
            val senderCoroutines = 8
            val messagesPerSender = 512
            val elements = senderCoroutines * messagesPerSender

            val server = use(startOrchestrationServer())
            val client = use(connectOrchestrationClient(Unknown, server.port))

            val incomingFlow = client.asFlow()
            val incomingChannel = client.asChannel()
            val incomingQueue = client.asBlockingQueue()

            val flowAllowCollecting = Job()
            val fromFlowAsync = async {
                incomingFlow.map { message ->
                    flowAllowCollecting.join()
                    message
                }.toList()
            }

            /* Ensure all registrations are present */
            testScheduler.advanceUntilIdle()

            coroutineScope {
                repeat(senderCoroutines) { coroutineId ->
                    launch(Dispatchers.IO) {
                        repeat(messagesPerSender) { iterationId ->
                            val event = TestEvent(listOf(coroutineId, iterationId))
                            server.sendMessage(event).get()
                        }
                    }
                }
            }

            /* Allow the flow to collect messages */
            flowAllowCollecting.complete()

            /* Sync client and server */
            run {
                val syncChannel = client.asChannel()
                val ping = OrchestrationMessage.Ping()
                server.sendMessage(ping)
                syncChannel.receiveAsFlow().first { it == ping }
            }

            server.close()

            val fromChannel = incomingChannel.toList()
            val fromQueue = buildList(elements) {
                while (true) {
                    incomingQueue.poll()?.let(::add) ?: break
                }
            }

            val expectedEvents = (0 until senderCoroutines).flatMap { coroutineId ->
                (0 until messagesPerSender).map { iterationId ->
                    TestEvent(listOf(coroutineId, iterationId))
                }
            }.toSet()

            fromChannel.containsAll(expectedEvents)
                || fail("Channel (${fromChannel.size}) did not receive all messages | iteration : $iteration")

            fromQueue.containsAll(expectedEvents)
                || fail("Queue (${fromQueue.size})  did not receive all messages | iteration : $iteration")

            fromFlowAsync.await().containsAll(expectedEvents)
                || fail("Flow(${fromFlowAsync.await().size}) did not receive all messages | iteration : $iteration")

            client.close()
            testScheduler.advanceUntilIdle()
        }
    }
}
