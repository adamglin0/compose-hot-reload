/*
 * Copyright 2024-2025 JetBrains s.r.o. and Compose Hot Reload contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
 */

package org.jetbrains.compose.reload.test.gradle

import org.jetbrains.kotlin.tooling.core.extrasKeyOf

public enum class ApplicationLaunchMode : HotReloadTestDimension {
    GradleBlocking, Detached;

    override fun displayName(): String? {
        return if (this == default) null
        else "LaunchMode($name)"
    }

    public companion object {
        internal val key = extrasKeyOf<ApplicationLaunchMode>()
        public val default: ApplicationLaunchMode = Detached
    }
}
