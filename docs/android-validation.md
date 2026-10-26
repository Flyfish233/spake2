# Android build validation

## Toolchain prepared

| Component | Observed version |
| --- | --- |
| Java | Eclipse Temurin OpenJDK 17.0.21+9 |
| Android SDK platform | API 35, revision 2, extension level 13 |
| Android SDK build tools | 35.0.0 |
| `aapt2 version` | 2.19-11948202 |
| Platform tools | 37.0.1-15733141 |

The SDK was downloaded using Google's command-line tools. The installer
returned exit code zero but nested the platform and build-tool archive roots
inside their package directories. The original extracted directories were
preserved outside the repository; the correct package contents were placed
at their standard SDK paths. The `source.properties` metadata, `android.jar`,
and executable `aapt2`/`adb` were then verified. This was an environment setup
repair; no project source or SDK executable was changed.

The platform `android.jar` SHA-256 is
`4566663c3876e022b4fa4ced8c8697c4ab1688267f090114fd92d027b32e619b`.
The build-tools `aapt2` SHA-256 is
`d1096e11aba9c974644369ee3c50d239acac3f3428ffa928e5b9c14dfb7a57de`.

## Build result

Pending the module split and Android Gradle build. SDK installation and tool
execution alone do not constitute a successful Android build or device test.

## Device coverage

No Android device or emulator has been used in this environment. An AAR build
does not establish ART runtime behavior, device performance, timing properties,
or physical memory erasure.
