/*
 * Copyright 2024-2025 JetBrains s.r.o. and Compose Hot Reload contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
 */

package org.jetbrains.compose.reload.gradle.idea

@Suppress("MayBeConstant")

/**
 * Defines known 'support levels' for any IDE plugin providing 'Compose Hot Reload' support.
 * It is expected that the IDE is exposing its support level to the Gradle plugin
 */
public object IdeaComposeHotReloadSupportVersions {
    public val supportReloadTasks: Int = 2
}
