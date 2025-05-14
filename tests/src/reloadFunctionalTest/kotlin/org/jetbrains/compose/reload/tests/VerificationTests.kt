/*
 * Copyright 2024-2025 JetBrains s.r.o. and Compose Hot Reload contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
 */

package org.jetbrains.compose.reload.tests

import org.jetbrains.compose.reload.orchestration.OrchestrationMessage
import org.jetbrains.compose.reload.test.gradle.HotReloadTest
import org.jetbrains.compose.reload.test.gradle.HotReloadTestFixture
import org.jetbrains.compose.reload.test.gradle.checkScreenshot
import org.jetbrains.compose.reload.test.gradle.initialSourceCode
import org.jetbrains.compose.reload.test.gradle.replaceText
import org.jetbrains.compose.reload.utils.QuickTest
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class VerificationTests {

    @HotReloadTest
    @QuickTest
    fun `illegal code change - update compose entry function`(fixture: HotReloadTestFixture) = fixture.runTest {
        val d = "\$"
        val code = fixture.initialSourceCode(
            """
                import androidx.compose.material.Text
                import org.jetbrains.compose.reload.test.*
                
                fun main() {
                    screenshotTestApplication {
                        Text("Hello")
                    }
                }
            """.trimIndent()
        )

        fixture.checkScreenshot("0-initial")

        fixture.runTransaction {
            /*
            Legal Change: replace the code inside the composable function
            */
            code.replaceText("""Text("Hello")""", """Text("hello")""")
            requestReload()
            val request = skipToMessage<OrchestrationMessage.ReloadClassesRequest>()
            val result = skipToMessage<OrchestrationMessage.ReloadClassesResult>()
            assertEquals(request.messageId, result.reloadRequestId)
            assertTrue(result.isSuccess)
            fixture.checkScreenshot("1-correct-change")
        }

        fixture.runTransaction {
            /*
            Illegal Change: replace the code outside composable scope
            */
            code.replaceText(
                """
                fun main() {
                    screenshotTestApplication {
                        Text("hello")
                    }
                }""".trimIndent(),
                """
                fun main() {
                    var myVariable = 0
                    screenshotTestApplication {
                        myVariable = 1
                        Text("${d}myVariable")
                    }
                }""".trimIndent()
            )
            requestReload()
            val request = skipToMessage<OrchestrationMessage.ReloadClassesRequest>()
            val result = skipToMessage<OrchestrationMessage.ReloadClassesResult>()
            assertEquals(request.messageId, result.reloadRequestId)
            assertFalse(result.isSuccess)
            assertEquals(
                "Compose Hot Reload does not support the redefinition of the Compose entry method." +
                    " Please restart the App or revert the changes in 'MainKt.main ()V'.",
                result.errorMessage
            )
        }
    }
}
