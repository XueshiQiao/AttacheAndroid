# Repository Guidelines

## Project Structure & Module Organization

ConnectBot is a Gradle Android SSH client project with `oss` and `google` product flavors. The main app lives in `app/`, with Kotlin and Java sources under `app/src/main/java/org/connectbot`. UI code is grouped in `ui/`, data and Room code in `data/`, dependency injection in `di/`, terminal/service code in `service/`, and protocol logic in `transport/`. Native code is in `app/src/main/cpp`, Android resources in `app/src/main/res`, and Room schemas in `app/schemas`. Unit tests are in `app/src/test/kotlin`, instrumentation tests in `app/src/androidTest`, and shared test helpers in `app/src/sharedTest/kotlin`. Translation files are under `translations/` and `app/locale/`.

## Build, Test, and Development Commands

Use the checked-in Gradle wrapper:

- `./gradlew assemble` builds all variants.
- `./gradlew :app:assembleOssDebug` builds the open-source debug APK.
- `./gradlew test` runs local JVM/Robolectric tests.
- `./gradlew connectedAndroidTest` runs device/emulator instrumentation tests.
- `./gradlew connectedOssDebugAndroidTest` and `./gradlew connectedGoogleDebugAndroidTest` run flavor-specific instrumentation tests.
- `./gradlew lint` runs Android lint with `app/lint.xml`.
- `./gradlew spotlessCheck` verifies formatting; `./gradlew spotlessApply` formats supported files.
- `./gradlew check test` runs the standard verification set used before submitting changes.

## Coding Style & Naming Conventions

Target Java 17/JVM 17. Kotlin uses ktlint, including Compose rules; Java under `app/src/main/java/org/connectbot` uses Google Java Format through Spotless. Keep package names lowercase and aligned with existing feature areas. Name Kotlin and Java types in `PascalCase`, functions and properties in `camelCase`, and tests as `*Test`. Prefer existing Compose, Hilt, Room, and repository patterns over new abstractions. Declare Gradle dependency and plugin versions in `gradle/libs.versions.toml` and use version catalog aliases from build scripts. In Jetpack Compose, read UI strings from Android resources with `stringResource()`. Add XML comments directly before new `strings.xml` entries so translators understand the string's purpose and placement. For new project-owned source files, use the current-year copyright notice for Kenny Root unless the surrounding package uses a different established notice.

## Testing Guidelines

Add focused tests for behavior changes. Use Robolectric for local UI/ViewModel tests in `app/src/test/kotlin`; use Android instrumentation tests only when device APIs, Hilt runner behavior, or emulator state is required. Keep Compose business logic in ViewModels so it can be covered by local tests where practical. Follow existing Mockito guidance in `README_TESTING.md`: use `org.mockito.Mockito` imports rather than mockito-kotlin. For database changes, update Room schema JSON under `app/schemas` and test migrations when relevant. `OpenSSHContainerTest` exercises a real OpenSSH server in Docker; run it from an Android emulator, not a physical device, with Docker available on the host.

## Commit & Pull Request Guidelines

Commit history uses short imperative subjects, for example `Fix pubkey list item compose lint` or `Add unit tests for importKeyFromText`. Keep the subject at 50 characters or less when practical, add a wrapped body for context, and make each commit a logical unit. Before opening a PR, run `git diff --check`, `./gradlew lint`, and `./gradlew check test`. PRs should describe the change, link related issues, mention test coverage, and include screenshots or screen recordings for visible UI changes.

## Agent-Specific Instructions

Do not overwrite unrelated local changes. Keep edits scoped to the requested behavior, preserve generated schema and translation files unless intentionally updating them, and prefer Gradle tasks over ad hoc build commands. Never block the main UI thread; dispatch IPC, network, disk I/O, and other long-running work off the main thread. In injected classes and ViewModels, use the injected `CoroutineDispatchers` instead of hardcoded `Dispatchers.IO` or similar dispatchers so tests can control execution.

## APK Versioning, Naming, and Delivery Rules (Mandatory)

1. **App Name**: The Android application display name (`app_name` in `app/src/main/res/values/notrans.xml`) MUST always be `Attache`.
2. **Version & Build Number (`version.properties`)**:
   - Versioning is controlled by `version.properties` in the repository root (`VERSION_NAME=1.0.0`, `BUILD_NUMBER=<N>`).
   - **Before every APK build sent to the user**, you MUST increment `BUILD_NUMBER` by 1 in `version.properties` (or bump `VERSION_NAME` when requested).
   - `app/build.gradle.kts` maps this to `versionCode = 12_000_000 + BUILD_NUMBER` and `versionName = "<VERSION_NAME> (Build <BUILD_NUMBER>)"` so Android's system package installer always detects the package as a newer version upgrade without requiring uninstall.
3. **Strict APK Filename Convention**:
   - Every APK copied or sent to the user MUST use the exact filename format:
     `Attache-v<VERSION_NAME>-b<BUILD_NUMBER>.apk`
     (for example: `Attache-v1.0.0-b1.apk`, `Attache-v1.0.0-b2.apk`, `Attache-v1.0.0-b3.apk`).
   - NEVER invent arbitrary APK names (`app-oss-debug.apk`, `attache-latest.apk`, `attache-ui-v2.apk`, etc.) and NEVER omit the version/build number from the APK filename.
4. **Sending APK to User's Phone/Pad**:
   - Build and send with:
     ```bash
     ./gradlew assembleOssDebug
     cp -f app/build/outputs/apk/oss/debug/app-oss-debug.apk "/tmp/Attache-v${VERSION_NAME}-b${BUILD_NUMBER}.apk"
     ~/.local/bin/attache-send "/tmp/Attache-v${VERSION_NAME}-b${BUILD_NUMBER}.apk"
     ```
5. **What the debug APK is built with (the owner's choice, 2026-11-08)**:
   - The debug build type runs R8 code shrinking (`isMinifyEnabled = true`) and has no test coverage instrumentation, so the APK sent to the phone is about 28 MB instead of 61 MB. Measured from clean packages: 61.1 MB as it was, 50.2 MB without coverage, 28.4 MB with R8 too.
   - Resource shrinking (`isShrinkResources`) is left off on purpose: it only took the APK to 26.1 MB. Do not turn it on, and do not put coverage back, without the owner asking.
   - When measuring APK sizes, delete the old APK first: debug packaging edits the previous file in place and the space freed by removed entries stays in it, so an incremental build reads tens of MB too large.
