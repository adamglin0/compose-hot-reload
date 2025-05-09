/*
 * Copyright 2024-2025 JetBrains s.r.o. and Compose Hot Reload contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
 */

@file:OptIn(ExperimentalCoroutinesApi::class)

package org.jetbrains.compose.reload.jvm

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.graphics.toComposeImageBitmap
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.selects.onTimeout
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.yield
import org.jetbrains.compose.reload.InternalHotReloadApi
import org.jetbrains.compose.reload.agent.orchestration
import org.jetbrains.compose.reload.agent.send
import org.jetbrains.compose.reload.core.createLogger
import org.jetbrains.compose.reload.orchestration.OrchestrationClientRole
import org.jetbrains.compose.reload.orchestration.OrchestrationMessage
import org.jetbrains.compose.reload.orchestration.OrchestrationMessage.ShutdownRequest
import org.jetbrains.compose.reload.orchestration.asChannel
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeoutException
import javax.imageio.ImageIO
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.ExperimentalTime

private val logger = createLogger()

internal data class SilenceTimeout(val timeout: Duration) : CoroutineContext.Element {
    override val key: CoroutineContext.Key<*> = Key

    companion object Key : CoroutineContext.Key<SilenceTimeout>
}

@OptIn(ExperimentalTime::class)
@InternalHotReloadApi
suspend fun runHeadlessApplication(
    timeout: Duration, width: Int, height: Int,
    content: @Composable () -> Unit
): Unit = coroutineScope {
    val applicationScope = this
    val messages = orchestration.asChannel()

    val scene = ImageComposeScene(width, height, coroutineContext = applicationScope.coroutineContext)
    scene.setContent {
        Box(modifier = Modifier.fillMaxSize().background(Color.White)) {
            DevelopmentEntryPoint {
                content()
            }
        }
    }

    applicationScope.launch {
        delay(timeout)
        logger.error("Application timed out...")
        applicationScope.cancel("Application timed out...")
    }

    /* Main loop */
    applicationScope.launch {
        var time = 0L
        val delay = 256.milliseconds
        var silence = 0.milliseconds
        var previousSilenceWarning = Clock.System.now()

        while (isActive) {
            time += delay.inWholeNanoseconds

            if (scene.hasInvalidations()) {
                scene.render(time)
                continue
            }

            val message = select {
                messages.onReceive { it }
                onTimeout(delay) { null }
            }

            if (message == null) {
                silence += delay
                coroutineContext[SilenceTimeout]?.let { timeout ->
                    if (silence >= timeout.timeout) throw TimeoutException(
                        "No message received for $silence within timeout $timeout )"
                    )
                }
                if (Clock.System.now() - previousSilenceWarning > 5.seconds) {
                    logger.warn("No message received for $silence")
                    previousSilenceWarning = Clock.System.now()
                }

                continue
            }

            silence = 0.milliseconds

            if (message is ShutdownRequest) {
                applicationScope.coroutineContext.job.cancelChildren()
                return@launch
            }

            if (message !is OrchestrationMessage.Ack && message !is OrchestrationMessage.LogMessage) {
                orchestration.sendMessage(OrchestrationMessage.Ack(message.messageId)).get()
            }

            /* Break out for TestEvents and give the main thread time to handle that */
            if (message is OrchestrationMessage.TestEvent) {
                yield()
            }

            if (message is OrchestrationMessage.TakeScreenshotRequest) {
                logger.info("Taking screenshot: '${message.messageId}'")
                val baos = ByteArrayOutputStream()
                ImageIO.write(scene.render(time).toComposeImageBitmap().toAwtImage(), "png", baos)
                orchestration.sendMessage(OrchestrationMessage.Screenshot("png", baos.toByteArray())).get()
                logger.debug("Sent screenshot: '${message.messageId}'")
            }
        }

    }
}

/**
 * Runs a 'headless' compose application which will participate in the orchestration.
 * This method will block until the application is finished.
 */
@InternalHotReloadApi
fun runHeadlessApplicationBlocking(
    timeout: Duration,
    silenceTimeout: Duration? = null,
    width: Int, height: Int,
    content: @Composable () -> Unit
) {
    runBlocking(
        Dispatchers.Main + Job() + CoroutineName("HeadlessApplication") +
            (if (silenceTimeout != null) SilenceTimeout(silenceTimeout) else EmptyCoroutineContext) +
            CoroutineExceptionHandler { context, throwable ->
                OrchestrationMessage.CriticalException(
                    clientRole = OrchestrationClientRole.Application,
                    message = throwable.message,
                    exceptionClassName = throwable::class.qualifiedName,
                    stacktrace = throwable.stackTrace.toList()
                ).send()
            }) {

        runHeadlessApplication(timeout, width, height, content)
    }
}
