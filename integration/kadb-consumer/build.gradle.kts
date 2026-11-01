// SPDX-License-Identifier: AGPL-3.0-only
import com.android.build.api.dsl.KotlinMultiplatformAndroidLibraryTarget
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension

plugins {
    kotlin("multiplatform") version "2.4.20" apply false
    id("com.android.kotlin.multiplatform.library") version "9.4.0" apply false
}

subprojects {
    apply(plugin = "org.jetbrains.kotlin.multiplatform")
    val enableAndroid = providers.gradleProperty("enableAndroid").orNull == "true"
    if (enableAndroid) apply(plugin = "com.android.kotlin.multiplatform.library")
    val coordinate = when (name) {
        "rfc-client" -> "spake2"
        "legacy-client" -> "spake2-legacy"
        else -> error("Unexpected consumer module: $name")
    }
    val candidateVersion = providers.gradleProperty("spake2Version").get()
    val fixtureRoot = rootProject.file("../consumer-fixtures/$name/src")

    extensions.configure<KotlinMultiplatformExtension> {
        linuxX64()
        macosArm64()
        macosX64()
        iosArm64()
        iosSimulatorArm64()
        // AGP creates this target when the new Android KMP plugin is applied.
        // Both test compilations explicitly share commonTest: neither is a smoke subset.
        targets.withType<KotlinMultiplatformAndroidLibraryTarget>().configureEach {
            namespace = "consumer.kadb." + project.name.replace("-", "")
            compileSdk = 37
            minSdk = 23
            compilerOptions { jvmTarget.set(JvmTarget.JVM_1_8) }
            withHostTestBuilder { sourceSetTreeName = "test" }.configure { }
            withDeviceTestBuilder { sourceSetTreeName = "test" }.configure {
                instrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
            }
        }
        sourceSets {
            commonMain {
                kotlin.srcDir(fixtureRoot.resolve("commonMain/kotlin"))
                dependencies { implementation("io.github.flyfish233:$coordinate:$candidateVersion") }
            }
            commonTest {
                kotlin.srcDir(fixtureRoot.resolve("commonTest/kotlin"))
                dependencies { implementation(kotlin("test")) }
            }
            if (enableAndroid) {
                named("androidHostTest") {
                    dependencies { implementation(kotlin("test-junit")) }
                }
                named("androidDeviceTest") {
                    dependencies {
                        implementation(kotlin("test-junit"))
                        implementation("androidx.test:runner:1.6.2")
                    }
                }
            }
        }
    }
    apply(from = rootProject.file("../consumer-fixtures/verify-resolution.gradle.kts"))
    tasks.withType<Test>().configureEach {
        testLogging { events("passed", "failed", "skipped"); showStandardStreams = true }
    }
}
