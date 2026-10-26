import org.gradle.api.artifacts.Configuration
import org.gradle.api.artifacts.component.ModuleComponentIdentifier
import org.gradle.api.artifacts.component.ModuleComponentSelector
import org.gradle.api.artifacts.component.ProjectComponentIdentifier
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
        fun validatePublishedId(id: ModuleComponentIdentifier) {
            check(id.group == "io.github.flyfish233" && id.module in allowed && id.version == expectedVersion) {
                "Unexpected published dependency or protocol edge: $id"
            }
        }
        val rows = mutableListOf<String>()
        var selected = 0
        configurations.filter { it.isCanBeResolved && it.state != Configuration.State.UNRESOLVED }
            .sortedBy { it.name }.forEach { configuration ->
                val resolution = configuration.incoming.resolutionResult
                val ownComponent = resolution.rootComponent.get().id
                resolution.allDependencies.filterIsInstance<ResolvedDependencyResult>().forEach { dependency ->
                    val requested = dependency.requested
                    if (requested is ModuleComponentSelector && requested.group == "io.github.flyfish233") {
                        val selectedId = dependency.selected.id
                        check(selectedId is ModuleComponentIdentifier) {
                            "Published SPAKE2 dependency was substituted with a project: $requested -> $selectedId"
                        }
                        validatePublishedId(selectedId)
                    }
                }
                configuration.incoming.artifacts.artifacts.forEach { artifact ->
                    val id = artifact.id.componentIdentifier
                    // Instrumentation may reference its own tested component.
                    // No other project, including an included producer, is allowed.
                    check(id !is ProjectComponentIdentifier || id == ownComponent) {
                        "Project dependency substitution is forbidden in this Maven consumer: $id"
                    }
                    if (id is ModuleComponentIdentifier && id.group == "io.github.flyfish233") {
                        validatePublishedId(id)
                        selected++
                        rows += listOf(configuration.name, id.displayName,
                            artifact.variant.displayName, artifact.file.name).joinToString("\t")
                    }
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
