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
        ideaVersion {
            sinceBuild = "252"
        }
    }

    // REQUIRED for :verifyPlugin through IDE

    pluginVerification {
        ides {
            // Verify against GoLand 2025.2.x and 2025.3.x (adjust as you like)
            ide("GO", "2025.2.4")
            ide("GO", "2025.3") // optional, catches upcoming breakages early
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
