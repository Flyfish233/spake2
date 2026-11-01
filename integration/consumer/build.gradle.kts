import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi
import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension
import org.jetbrains.kotlin.gradle.plugin.KotlinSourceSetTree

plugins {
    kotlin("multiplatform") version "2.2.21" apply false
    id("com.android.library") version "8.11.1" apply false
}
subprojects {
    apply(plugin = "org.jetbrains.kotlin.multiplatform")
    val enableAndroid = providers.gradleProperty("enableAndroid").orNull == "true"
    if (enableAndroid) apply(plugin = "com.android.library")
    val coordinate = if (name == "rfc-client") "spake2" else "spake2-legacy"
    val candidateVersion = providers.gradleProperty("spake2Version").get()
    val fixtureRoot = rootProject.file("../consumer-fixtures/$name/src")
    extensions.configure<KotlinMultiplatformExtension> {
        jvm { compilerOptions { jvmTarget.set(JvmTarget.JVM_1_8) } }
        linuxX64()
        macosArm64()
        macosX64()
        iosArm64()
        iosSimulatorArm64()
        iosX64()
        js(IR) { nodejs { testTask { useMocha { timeout = "600s" } } } }
        @OptIn(ExperimentalWasmDsl::class)
        wasmJs { nodejs() }
        if (enableAndroid) {
            @OptIn(ExperimentalKotlinGradlePluginApi::class)
            androidTarget {
                compilerOptions { jvmTarget.set(JvmTarget.JVM_1_8) }
                instrumentedTestVariant.sourceSetTree.set(KotlinSourceSetTree.test)
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
                named("androidInstrumentedTest") {
                    dependencies {
                        implementation(kotlin("test-junit"))
                        implementation("androidx.test:runner:1.6.2")
                    }
                }
            }
        }
    }
    if (enableAndroid) {
        extensions.configure<com.android.build.gradle.LibraryExtension> {
            namespace = "consumer." + project.name.replace("-", "")
            compileSdk = 35
            defaultConfig {
                minSdk = 21
                testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
            }
            compileOptions {
                sourceCompatibility = JavaVersion.VERSION_1_8
                targetCompatibility = JavaVersion.VERSION_1_8
            }
        }
    }
    apply(from = rootProject.file("../consumer-fixtures/verify-resolution.gradle.kts"))
    tasks.withType<Test>().configureEach {
        testLogging { events("passed", "failed", "skipped"); showStandardStreams = true }
    }
}
