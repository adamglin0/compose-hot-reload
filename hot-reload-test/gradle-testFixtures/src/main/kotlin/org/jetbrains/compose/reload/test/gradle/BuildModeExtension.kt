/*
 * Copyright 2024-2025 JetBrains s.r.o. and Compose Hot Reload contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
 */

package org.jetbrains.compose.reload.test.gradle

import org.jetbrains.compose.reload.core.HotReloadProperty
import org.junit.jupiter.api.extension.ExtensionContext

internal class BuildModeExtension : GradlePropertiesExtension {
    override fun properties(context: ExtensionContext): List<String> {
        val isContinuous = when (context.buildMode) {
            BuildMode.Explicit -> false
            BuildMode.Continuous -> true
        }

        if (HotReloadProperty.GradleBuildContinuous.default.toBoolean() == isContinuous) {
            return emptyList()
        }

        return listOf("${HotReloadProperty.GradleBuildContinuous.key}=${isContinuous}")
    }
}
