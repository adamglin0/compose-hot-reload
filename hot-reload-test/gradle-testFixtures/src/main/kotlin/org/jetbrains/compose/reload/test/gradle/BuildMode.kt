/*
 * Copyright 2024-2025 JetBrains s.r.o. and Compose Hot Reload contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
 */

package org.jetbrains.compose.reload.test.gradle

import org.jetbrains.kotlin.tooling.core.extrasKeyOf

public enum class BuildMode : HotReloadTestDimension {
    Explicit,
    Continuous;

    public companion object {
        public val default: BuildMode get() = Explicit
        internal val key = extrasKeyOf<BuildMode>()
    }

    override fun displayName(): String? {
        if (this == default) return null
        return "BuildMode($name)"
    }
}
