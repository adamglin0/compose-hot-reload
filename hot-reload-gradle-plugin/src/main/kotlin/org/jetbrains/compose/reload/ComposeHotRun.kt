/*
 * Copyright 2024-2025 JetBrains s.r.o. and Compose Hot Reload contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
 */

package org.jetbrains.compose.reload

import org.gradle.api.Project
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.JavaExec
import org.gradle.api.tasks.options.Option
import org.gradle.kotlin.dsl.property
import org.gradle.kotlin.dsl.withType
import org.jetbrains.compose.reload.gradle.kotlinJvmOrNull
import org.jetbrains.compose.reload.gradle.kotlinMultiplatformOrNull
import org.jetbrains.compose.reload.gradle.string
import org.jetbrains.kotlin.gradle.plugin.KotlinCompilation
import org.jetbrains.kotlin.gradle.targets.jvm.KotlinJvmTarget

internal fun Project.setupComposeHotRunConventions() {
    project.composeHotReloadProcessManagerTask()

    tasks.withType<AbstractComposeHotRun>().configureEach { task ->
        task.group = "run"
        task.configureJavaExecTaskForHotReload(task.compilation)
    }

    tasks.withType<ComposeHotRun>().configureEach { task ->
        task.description = "Compose Application Run (Hot Reload enabled) | -PmainClass=..."

        task.mainClass.convention(
            project.providers.gradleProperty("mainClass")
                .orElse(project.providers.systemProperty("mainClass"))
        )

        task.compilation.convention(provider {
            kotlinMultiplatformOrNull?.let { kotlin ->
                return@provider kotlin.targets.withType<KotlinJvmTarget>()
                    .firstOrNull()?.compilations?.getByName("main")
            }

            kotlinJvmOrNull?.let { kotlin ->
                return@provider kotlin.target.compilations.getByName("main")
            }
            null
        })
    }

    /* Configure the dev run: Expect -DclassName and -DfunName */
    tasks.withType<ComposeDevRun>().configureEach { task ->
        task.description = "Compose Application Dev Run (Hot Reload enabled) | -PclassName=... -PfunName=..."
        task.mainClass.set("org.jetbrains.compose.reload.jvm.DevApplication")
        task.args("--className", task.className.string(), "--funName", task.funName.string())
    }
}

sealed class AbstractComposeHotRun : JavaExec() {
    @Transient
    @get:Internal
    val compilation = project.objects.property<KotlinCompilation<*>>()

    @get:Internal
    internal val pidFile = project.objects.fileProperty()
        .value(compilation.flatMap { compilation -> compilation.runBuildFile("$name.pid") })

    @get:InputFile
    internal val argFile = project.objects.fileProperty()
        .value(compilation.flatMap { compilation -> compilation.runBuildFile("$name.argfile") })

    @get:Internal
    internal val argFileTaskName = project.objects.property<String>()
}

/**
 * Registers a custom 'run' task
 * Example Usage
 *
 * ```
 * // build.gradle.kts
 *
 * tasks.register<ComposeHotRun>("runHot") {
 *     mainClass = "my.app.MainKt" // <- Optional: Can also be provided with -PmainClass
 * }
 *
 * ```
 */
open class ComposeHotRun : AbstractComposeHotRun() {
    @Suppress("unused")
    @Option(option = "mainClass", description = "Override the main class name")
    internal fun mainClas(mainClass: String) {
        this.mainClass.set(mainClass)
    }
}

/**
 * Default 'Dev' Run task which will use the 'DevApplication' to display a given composable
 * using the "className" and "funName" properties.
 */
internal open class ComposeDevRun : AbstractComposeHotRun() {

    @get:Internal
    internal val className = project.objects.property<String>().value(
        project.providers.systemProperty("className")
            .orElse(project.providers.gradleProperty("className"))
    )

    @get:Internal
    internal val funName = project.objects.property<String>().value(
        project.providers.systemProperty("funName")
            .orElse(project.providers.gradleProperty("funName"))
    )

    @Suppress("unused")
    @Option(option = "className", description = "Provide the name of the class to execute")
    internal fun className(className: String) {
        this.className.set(className)
    }

    @Suppress("unused")
    @Option(option = "funName", description = "Provide the name of the function to execute")
    internal fun funName(funName: String) {
        this.funName.set(funName)
    }
}
