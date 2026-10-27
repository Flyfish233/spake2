import org.gradle.api.artifacts.Configuration
import org.gradle.api.artifacts.component.ModuleComponentIdentifier
import org.gradle.api.artifacts.component.ModuleComponentSelector
import org.gradle.api.artifacts.result.ResolvedDependencyResult

// Run after compilation/tests in the same invocation, so it records the actual
// resolved configurations without resolving disabled host targets.
tasks.register("verifyPublishedDependencies") {
    doLast {
        val expectedVersion = providers.gradleProperty("spake2Version").get()
        val legacyOnly = project.name == "legacy-client"
        val suffixes = listOf("android", "jvm", "js", "wasmjs", "linuxx64", "macosarm64",
            "macosx64", "iosarm64", "iossimulatorarm64", "iosx64")
        val bases = listOf(if (legacyOnly) "spake2-legacy" else "spake2", "spake2-primitives")
        val allowed = bases.flatMap { base -> listOf(base) + suffixes.map { "$base-$it" } }.toSet()
        fun validatePublishedId(id: ModuleComponentIdentifier, configurationName: String) {
            check(id.group == "io.github.flyfish233" && id.module in allowed && id.version == expectedVersion) {
                "Unexpected published dependency or protocol edge in $configurationName: $id"
            }
        }
        val rows = mutableListOf<String>()
        var selected = 0
        configurations.filter { it.isCanBeResolved && it.state != Configuration.State.UNRESOLVED }
            .sortedBy { it.name }.forEach { configuration ->
                val resolution = configuration.incoming.resolutionResult
                resolution.allDependencies.forEach { dependency ->
                    val requested = dependency.requested
                    if (requested is ModuleComponentSelector && requested.group == "io.github.flyfish233") {
                        check(dependency is ResolvedDependencyResult) {
                            "Published SPAKE2 dependency is unresolved in ${configuration.name}: $requested"
                        }
                        val selectedId = dependency.selected.id
                        check(selectedId is ModuleComponentIdentifier) {
                            "Published SPAKE2 dependency was substituted with a project in ${configuration.name}: $requested -> $selectedId"
                        }
                        validatePublishedId(selectedId, configuration.name)
                    }
                }
                // Check the graph before filtering files: a project substitution
                // must not disappear from the evidence merely because it has no
                // Maven component ID. Unrelated plugin metadata may legitimately
                // include fixture projects (Android tested components / SwiftPM).
                // Resolving their files here also creates Android secondary-variant
                // ambiguity. This strict view selects only the SPAKE2 artifacts;
                // missing or ambiguous published artifacts still fail resolution.
                configuration.incoming.artifactView {
                    componentFilter { id ->
                        id is ModuleComponentIdentifier && id.group == "io.github.flyfish233"
                    }
                }.artifacts.artifacts.forEach { artifact ->
                    val id = artifact.id.componentIdentifier
                    check(id is ModuleComponentIdentifier)
                    validatePublishedId(id, configuration.name)
                    selected++
                    rows += listOf(configuration.name, id.displayName,
                        artifact.variant.displayName, artifact.file.name).joinToString("\t")
                }
            }
        check(selected > 0) { "No published SPAKE2 artifacts were observed; run compilation/tests before this task" }
        val output = layout.buildDirectory.file("reports/published-dependencies.tsv").get().asFile
        output.parentFile.mkdirs()
        output.writeText("configuration\tcoordinate\tvariant\tartifact\n" +
            rows.distinct().sorted().joinToString("\n", postfix = "\n"))
        logger.lifecycle("Verified $selected Maven artifact selections for ${project.path}: $output")
    }
}
