plugins {
    id("java")
    id("org.jetbrains.kotlin.jvm") version "2.1.20"
    id("org.jetbrains.intellij.platform") version "2.10.2"
}

group = "com.example"
version = "1.0-SNAPSHOT"

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    intellijPlatform {
        // Run/debug the plugin in GoLand (not IntelliJ IDEA)
        goland("2025.2.4")

        // Compile against the Go plugin APIs (bundled with GoLand)
        bundledPlugin("org.jetbrains.plugins.go")

        // Test framework (optional but fine)
        testFramework(org.jetbrains.intellij.platform.gradle.TestFrameworkType.Platform)
    }
}

intellijPlatform {
    pluginConfiguration {
        ideaVersion {
            sinceBuild = "252.27397"   // matches GoLand 2025.2.4 build line
            // untilBuild = "253.*"     // optional: allow 2025.3 too
        }
    }
}

java {
    // Ensures Gradle compiles with Java 21 bytecode even if your project SDK differs
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
}

tasks.withType<JavaCompile> {
    // Keep these aligned with the toolchain
    sourceCompatibility = "21"
    targetCompatibility = "21"
}

kotlin {
    // Keep Kotlin bytecode aligned too
    jvmToolchain(21)
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21)
    }
}
