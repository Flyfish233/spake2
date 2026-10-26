# Captured Gradle 9.6 / Kotlin 2.4.20 Native test XML

These four XML files are unmodified test reports from the independent Maven
consumer, not generated expectations from the XML checker.

- SPAKE2 source: `027d6dc00f2c92502ca98b878d1940a358590be5`.
- GitHub Actions run: <https://github.com/Flyfish233/spake2/actions/runs/40384173898>.
- Modern macOS Arm64 job: `121789389622`.
- Artifact: `kadb-toolchain-consumer-darwin-arm64`, ID `12680820690`.
- Downloaded ZIP SHA-256: `fdcd24757ed76514eea26834a15fc748053478a6819ab492f0a1858f8c31ad7b`.
- Toolchain: Kotlin 2.4.20, Gradle 9.6.0, Temurin JDK 25.

Each of `macosArm64Test` and `iosSimulatorArm64Test` executed the five expected
consumer methods with zero failures, errors or skips. The overall CI job later
failed in `verifyPublishedDependencies`; these reports do not establish a
successful workflow acceptance. The complete failure log, environment evidence
and XML hashes are retained under `docs/round2/evidence/ci/027d6dc`.

This Gradle test report format prefixes each test class with its owning task.
The regression checks accept only that exact requested task prefix and continue
to reject other classes, wrong task prefixes, missing methods and duplicates.
