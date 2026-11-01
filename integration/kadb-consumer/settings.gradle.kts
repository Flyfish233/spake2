// SPDX-License-Identifier: AGPL-3.0-only
pluginManagement {
    repositories { google(); mavenCentral(); gradlePluginPortal() }
}

val candidateRepository = providers.gradleProperty("spake2Repository").orNull
    ?: error("Pass -Pspake2Repository=/absolute/path/to/the/isolated/maven/repository")
val candidateUri = uri(candidateRepository)
require(candidateUri.scheme == "file") { "The validation fixture requires a local file Maven repository" }
require(!providers.gradleProperty("spake2Version").orNull.isNullOrBlank()) {
    "Pass -Pspake2Version=<exact-published-candidate-version>"
}

dependencyResolutionManagement {
    repositories {
        exclusiveContent {
            forRepository {
                maven {
                    name = "spake2Candidate"
                    url = candidateUri
                    metadataSources { gradleMetadata(); mavenPom() }
                }
            }
            filter { includeGroup("io.github.flyfish233") }
        }
        google()
        mavenCentral()
    }
}
rootProject.name = "spake2-kadb-toolchain-consumer"
include(":rfc-client", ":legacy-client")
