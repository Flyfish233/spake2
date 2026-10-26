import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension

plugins {
    kotlin("multiplatform") version "2.2.21"
    id("com.android.library") version "8.11.1" apply false
}

allprojects {
    group = "io.github.flyfish233"
    version = "0.1.0-dev"
    apply(plugin = "org.jetbrains.kotlin.multiplatform")
    val moduleProject = this
    val enableAndroid = providers.gradleProperty("enableAndroid").orNull == "true"
    if (enableAndroid) apply(plugin = "com.android.library")
    extensions.configure<KotlinMultiplatformExtension> {
        jvm { compilerOptions { jvmTarget.set(JvmTarget.JVM_1_8) } }
        if (enableAndroid) androidTarget { compilerOptions { jvmTarget.set(JvmTarget.JVM_1_8) } }
        linuxX64()
        iosArm64()
        iosSimulatorArm64()
        iosX64()
        js(IR) { nodejs { testTask { useMocha { timeout = "600s" } } } }
        @OptIn(ExperimentalWasmDsl::class)
        wasmJs { nodejs { testTask { useMocha { timeout = "600s" } } } }
        sourceSets {
            all { languageSettings.optIn("io.github.flyfish233.spake2.InternalSpake2Api") }
            commonTest.dependencies { implementation(kotlin("test")) }
            if (moduleProject.name != "primitives") {
                commonMain.dependencies { api(project(":primitives")) }
            }
            if (moduleProject == rootProject) {
                // Integration tests exercise both explicit APIs; production RFC has no Legacy dependency.
                commonTest.dependencies { implementation(project(":legacy")) }
            }
        }
    }
    if (enableAndroid) {
        extensions.configure<com.android.build.gradle.LibraryExtension> {
            namespace = "io.github.flyfish233.spake2" +
                if (moduleProject == rootProject) "" else "." + moduleProject.name
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
}
