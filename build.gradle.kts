plugins {
    id("java")
    id("org.jetbrains.kotlin.jvm") version "2.1.20"
    id("org.jetbrains.intellij.platform") version "2.10.2"
}

group = "com.monkops"      // <-- change from com.example
version = "1.0.0"          // <-- use a real release version for Marketplace

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    intellijPlatform {
        // Run/debug the plugin in GoLand
        goland("2025.2.4")

        // Compile against Go plugin APIs (bundled with GoLand)
        bundledPlugin("org.jetbrains.plugins.go")

        // Optional
        testFramework(org.jetbrains.intellij.platform.gradle.TestFrameworkType.Platform)
    }
}

intellijPlatform {
    pluginConfiguration {
        // IMPORTANT:
        // You want "2025.2+" support.
        // 2025.2 corresponds to branch build 252.*
        // Set sinceBuild to "252" (not a specific full build number),
        // and do NOT set untilBuild (open-ended).
        ideaVersion {
            sinceBuild = "252"
            // leave untilBuild unset => open-ended
        }
    }
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
}

kotlin {
    jvmToolchain(21)
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21)
    }
}
