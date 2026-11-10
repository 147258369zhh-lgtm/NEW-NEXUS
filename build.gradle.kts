buildscript {
    repositories {
        google()
        mavenCentral()
    }
    dependencies {
        // 内置 Kotlin 与 Compose 编译插件共用版本目录，避免编译器版本不一致。
        classpath(libs.kotlin.gradle.plugin)
    }
}

plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.ksp) apply false
}

abstract class CheckNexusArchitecture : org.gradle.api.DefaultTask() {
    @get:org.gradle.api.tasks.InputFiles
    @get:org.gradle.api.tasks.PathSensitive(org.gradle.api.tasks.PathSensitivity.RELATIVE)
    abstract val sourceFiles: org.gradle.api.file.ConfigurableFileCollection

    @get:org.gradle.api.tasks.InputDirectory
    @get:org.gradle.api.tasks.PathSensitive(org.gradle.api.tasks.PathSensitivity.RELATIVE)
    abstract val appSourceRoot: org.gradle.api.file.DirectoryProperty

    @org.gradle.api.tasks.TaskAction
    fun checkArchitecture() {
        val forbiddenPrefixes = listOf(
            "android.",
            "androidx.",
            "io.github.mangi.eta.",
            "top.yukonga.miuix.",
        )
        val violations = sourceFiles.files.asSequence()
            .filter { it.isFile && it.extension == "kt" }
            .flatMap { file ->
                file.readLines().asSequence().mapIndexedNotNull { index, line ->
                    val imported = line.trim().removePrefix("import ").takeIf { line.trim().startsWith("import ") }
                    if (imported != null && forbiddenPrefixes.any(imported::startsWith)) {
                        "${file.absolutePath}:${index + 1}: $imported"
                    } else {
                        null
                    }
                }
            }
            .toList()
        check(violations.isEmpty()) {
            "kernel-api contains forbidden dependencies:\n${violations.joinToString("\n")}"
        }

        val appRoot = appSourceRoot.get().asFile
        val forbiddenFeatureImports = appRoot.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .flatMap { file ->
                val relative = file.relativeTo(appRoot).invariantSeparatorsPath
                val sourceSection = relative.substringAfter("io/github/mangi/eta/", "").substringBefore('/')
                val runtimeLayer = sourceSection == "agent" && relative.contains("/agent/runtime/")
                if (sourceSection !in setOf("data", "fork") && !runtimeLayer) emptySequence()
                else file.readLines().asSequence().mapIndexedNotNull { index, line ->
                    val imported = line.trim().removePrefix("import ").takeIf { line.trim().startsWith("import ") }
                    if (imported?.startsWith("io.github.mangi.eta.ui.") == true) {
                        "${file.absolutePath}:${index + 1}: $imported"
                    } else {
                        null
                    }
                }
            }
            .toList()
        check(forbiddenFeatureImports.isEmpty()) {
            "Data, feature and runtime code must not import presentation code:\n" +
                forbiddenFeatureImports.joinToString("\n")
        }
    }
}

tasks.register<CheckNexusArchitecture>("checkNexusArchitecture") {
    group = "verification"
    description = "Checks that the Nexus kernel contract module has no Android or app dependencies."
    sourceFiles.from(
        fileTree(layout.projectDirectory.dir("kernel-api/src/main/kotlin")) { include("**/*.kt") },
        fileTree(layout.projectDirectory.dir("application-api/src/main/kotlin")) { include("**/*.kt") },
    )
    appSourceRoot.set(layout.projectDirectory.dir("app/src/main/kotlin/io/github/mangi/eta"))
}
