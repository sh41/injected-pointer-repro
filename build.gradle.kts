import org.gradle.api.tasks.testing.logging.TestExceptionFormat
import org.jetbrains.intellij.platform.gradle.TestFrameworkType

plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.intellij.platform")
    id("org.jetbrains.changelog")
}

dependencies {
    testImplementation("junit:junit:4.13.2")

    // IntelliJ Platform Gradle Plugin Dependencies Extension - read more: https://plugins.jetbrains.com/docs/intellij/tools-intellij-platform-gradle-plugin-dependencies-extension.html
    intellijPlatform {
        intellijIdea("2026.1.5")
        testFramework(TestFrameworkType.Platform)
    }
}

tasks.test {
    // Without this the console shows only "AssertionFailedError at <file>:<line>", and the message says which
    // injection failed to restore.
    testLogging {
        events("failed")
        exceptionFormat = TestExceptionFormat.FULL
        showStackTraces = false
    }
}
