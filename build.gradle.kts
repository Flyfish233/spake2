import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    kotlin("multiplatform") version "2.2.21"
    id("com.android.library") version "8.11.1" apply false
}

group = "io.github.flyfish233"
version = "0.1.0-dev"

val enableAndroid = providers.gradleProperty("enableAndroid").orNull == "true"
if (enableAndroid) apply(plugin = "com.android.library")

kotlin {
    jvm { compilerOptions { jvmTarget.set(JvmTarget.JVM_1_8) } }
    if (enableAndroid) androidTarget { compilerOptions { jvmTarget.set(JvmTarget.JVM_1_8) } }
    linuxX64()
    iosArm64()
    iosSimulatorArm64()
    iosX64()
    js(IR) { nodejs() }
    @OptIn(ExperimentalWasmDsl::class)
    wasmJs { nodejs() }
    sourceSets {
        commonTest.dependencies { implementation(kotlin("test")) }
    }
}

if (enableAndroid) {
    extensions.configure<com.android.build.gradle.LibraryExtension> {
        namespace = "io.github.flyfish233.spake2"
        compileSdk = 35
        defaultConfig { minSdk = 21 }
        compileOptions {
            sourceCompatibility = JavaVersion.VERSION_1_8
            targetCompatibility = JavaVersion.VERSION_1_8
        }
    }
}

tasks.withType<Test>().configureEach {
    testLogging { events("passed", "skipped", "failed"); showStandardStreams = true }
}
