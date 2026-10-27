pluginManagement {
    repositories { google(); mavenCentral(); gradlePluginPortal() }
}
dependencyResolutionManagement {
    repositories {
        exclusiveContent {
            forRepository {
                maven {
                    name = "spake2Candidate"
                    url = uri(providers.gradleProperty("spake2Repository").get())
                    metadataSources { gradleMetadata(); mavenPom() }
                }
            }
            filter { includeGroup("io.github.flyfish233") }
        }
        google()
        mavenCentral()
    }
}
rootProject.name = "spake2-independent-consumer"
include(":rfc-client", ":legacy-client")
