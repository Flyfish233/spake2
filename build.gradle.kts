import org.gradle.api.publish.PublishingExtension
import org.gradle.api.publish.maven.MavenPublication
import org.gradle.api.tasks.bundling.AbstractArchiveTask
import org.gradle.api.tasks.bundling.Jar
import org.gradle.api.tasks.bundling.Zip
import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi
import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension
import org.jetbrains.kotlin.gradle.plugin.KotlinSourceSetTree

plugins {
    kotlin("multiplatform") version "2.2.21"
    id("com.android.library") version "8.11.1" apply false
    `maven-publish`
}

// Complete buildable sources accompany each umbrella publication.
val sourceDistribution by tasks.registering(Zip::class) {
    archiveBaseName.set("spake2")
    archiveClassifier.set("source-distribution")
    from(rootDir) {
        include("build.gradle.kts", "settings.gradle.kts", "gradle.properties",
            "gradlew", "gradlew.bat", "gradle/**", "LICENSE", "THIRD_PARTY.md",
            "README.md", "SECURITY.md", ".github/workflows/**",
            "src/**", "primitives/build.gradle.kts", "primitives/src/**",
            "legacy/build.gradle.kts", "legacy/src/**", "tools/**",
            "test-vectors/**", "third-party/**", "docs/**",
            "kotlin-js-store/**", "integration/**")
        exclude("**/build/**", "**/.gradle/**", "**/.kotlin/**",
            "**/node_modules/**", "**/__pycache__/**", "**/*.pyc",
            "**/.venv/**", "**/.cache/**", "**/local.properties")
    }
}

allprojects {
    group = "io.github.flyfish233"
    version = providers.gradleProperty("spake2Version").orElse("0.1.0-dev").get()
    apply(plugin = "org.jetbrains.kotlin.multiplatform")
    apply(plugin = "maven-publish")
    val moduleProject = this
    val artifactBase = if (moduleProject == rootProject) "spake2" else "spake2-${moduleProject.name}"
    // Distinct paths avoid duplicate Java resources when an application combines
    // the protocol and primitives artifacts. Native KLIBs retain companions.
    val publicationNotices = tasks.register<Sync>("publicationNotices") {
        into(layout.buildDirectory.dir("generated/publicationNotices"))
        into("META-INF/spake2/$artifactBase") {
            from(rootProject.file("LICENSE"), rootProject.file("THIRD_PARTY.md"))
            from(rootProject.file("third-party")) { into("third-party") }
        }
    }
    val revision = providers.gradleProperty("spake2Revision").orElse("WORKTREE").get()
    val enableAndroid = providers.gradleProperty("enableAndroid").orNull == "true"
    if (enableAndroid) apply(plugin = "com.android.library")
    extensions.configure<KotlinMultiplatformExtension> {
        jvm { compilerOptions { jvmTarget.set(JvmTarget.JVM_1_8) } }
        if (enableAndroid) {
            @OptIn(ExperimentalKotlinGradlePluginApi::class)
            androidTarget {
                compilerOptions { jvmTarget.set(JvmTarget.JVM_1_8) }
                publishLibraryVariants("release")
                instrumentedTestVariant.sourceSetTree.set(KotlinSourceSetTree.test)
            }
        }
        linuxX64()
        macosArm64()
        macosX64()
        iosArm64()
        iosSimulatorArm64()
        iosX64()
        js(IR) { nodejs { testTask { useMocha { timeout = "600s" } } } }
        @OptIn(ExperimentalWasmDsl::class)
        wasmJs { nodejs() }
        targets.configureEach {
            // KGP assigns target defaults after publication creation. The target
            // callback runs afterwards and also feeds its exported GMM/POM coordinates.
            val suffix = name.lowercase()
            mavenPublication { artifactId = "$artifactBase-$suffix" }
        }
        sourceSets {
            commonMain { resources.srcDir(publicationNotices) }
            all { languageSettings.optIn("io.github.flyfish233.spake2.InternalSpake2Api") }
            commonTest.dependencies { implementation(kotlin("test")) }
            if (enableAndroid) {
                named("androidInstrumentedTest") {
                    dependencies {
                        implementation(kotlin("test-junit"))
                        implementation("androidx.test:runner:1.6.2")
                    }
                }
            }
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
        // AGP's legacy source-set bridge flattens a TaskProvider into paths.
        // Retain the generation dependency explicitly for every Java-resource variant.
        tasks.matching { it.name.startsWith("process") && it.name.endsWith("JavaRes") }.configureEach {
            dependsOn(publicationNotices)
        }
        extensions.configure<com.android.build.gradle.LibraryExtension> {
            sourceSets.getByName("main").resources.srcDir(publicationNotices)
            namespace = "io.github.flyfish233.spake2" +
                if (moduleProject == rootProject) "" else "." + moduleProject.name
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
    // KGP publishes source JARs. This documentation artifact contains the
    // maintained API/lifecycle guide, not generated Javadoc.
    val documentationJar = tasks.register<Jar>("documentationJar") {
        archiveBaseName.set(artifactBase)
        archiveClassifier.set("javadoc")
        from(rootProject.file("docs/API.md"))
        from(rootProject.file("docs/conformance.md"))
        from(rootProject.file("docs/LEGACY_COMPATIBILITY.md"))
        from(rootProject.file("README.md"))
        from(rootProject.file("SECURITY.md"))
        from(rootProject.file("LICENSE"))
        from(rootProject.file("THIRD_PARTY.md"))
        from(rootProject.file("third-party")) { into("third-party") }
    }
    tasks.withType<AbstractArchiveTask>().configureEach {
        isPreserveFileTimestamps = false
        isReproducibleFileOrder = true
        // KGP source archives are not consistently Gradle Jar tasks.
        if (name.endsWith("SourcesJar", ignoreCase = true)) {
            from(rootProject.file("LICENSE")) { into("META-INF/spake2") }
            from(rootProject.file("THIRD_PARTY.md")) { into("META-INF/spake2") }
            from(rootProject.file("third-party")) { into("META-INF/spake2/third-party") }
        }
    }
    extensions.configure<PublishingExtension> {
        // No remote repository or credentials. Validation publishes only to
        // Maven Local, isolated with -Dmaven.repo.local.
        publications.withType<MavenPublication>().configureEach {
            if (name == "kotlinMultiplatform") artifactId = artifactBase
            artifact(documentationJar)
            if (name == "kotlinMultiplatform") artifact(rootProject.tasks.named("sourceDistribution"))
            pom {
                name.set(artifactBase)
                description.set("Experimental pure Kotlin Multiplatform SPAKE2. " +
                    if (moduleProject.name == "legacy") "Explicit legacy BoringSSL SPAKE25519 compatibility."
                    else if (moduleProject.name == "primitives") "Shared cryptographic implementation."
                    else "RFC 9382 with mutual key confirmation.")
                url.set("https://github.com/Flyfish233/spake2")
                licenses {
                    license {
                        name.set("GNU Affero General Public License, version 3 only (AGPL-3.0-only)")
                        url.set("https://www.gnu.org/licenses/agpl-3.0.html")
                        distribution.set("repo")
                    }
                }
                developers { developer { id.set("Flyfish233"); name.set("Flyfish233") } }
                scm {
                    url.set("https://github.com/Flyfish233/spake2")
                    connection.set("scm:git:https://github.com/Flyfish233/spake2.git")
                    developerConnection.set("scm:git:ssh://git@github.com/Flyfish233/spake2.git")
                    tag.set(revision)
                }
                properties.put("spake2.sourceRevision", revision)
                properties.put("spake2.securityStatus", "experimental-not-externally-audited")
            }
        }
    }
    tasks.withType<Test>().configureEach {
        testLogging { events("passed", "skipped", "failed"); showStandardStreams = true }
    }
}
