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
        val localIde = providers.gradleProperty("localIdePath")
        if (localIde.isPresent) local(localIde.get()) else intellijIdea("2025.3.6.1")
        bundledPlugin("Git4Idea")
        testFramework(TestFrameworkType.Platform)
    }
}

intellijPlatform {
    pluginConfiguration {
        ideaVersion {
            sinceBuild = "253"
            untilBuild = provider { null }
        }
    }
    pluginVerification {
        ides {
            val verificationIde = providers.gradleProperty("verificationIdePath")
            if (verificationIde.isPresent) local(verificationIde.get()) else recommended()
        }
    }
}

kotlin { jvmToolchain(21) }
