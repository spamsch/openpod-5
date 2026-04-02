# UI Integration Test Plan

End-to-end UI testing strategy for OpenPod using Compose Testing, Gradle Managed Devices, and the Python pod emulator.

## Goals

- One command boots a virtual device, deploys the app, and runs all UI tests
- Tests exercise real navigation, Hilt DI, Room, DataStore — no mocks at the UI layer
- BLE dependency solved via the existing Python pod emulator in TCP mode
- Deterministic, repeatable runs suitable for CI

## Architecture

```
Makefile target (test-ui)
  |
  +-- Python pod emulator (TCP, seed 42)
  |
  +-- Gradle Managed Device (Pixel 6, API 34, aosp-atd)
        |
        +-- App APK (debug, -PuseEmulator=true)
        +-- Test APK (androidTest)
              |
              +-- Hilt test runner
              +-- Compose UI test rules
              +-- adb forward for emulator TCP port
```

## Phase 1: Gradle Infrastructure

### 1.1 Add dependencies to `gradle/libs.versions.toml`

```toml
[versions]
hilt-android-testing = "2.59.2"   # match existing hilt version
test-runner          = "1.6.2"
test-rules           = "1.6.2"

[libraries]
hilt-android-testing          = { module = "com.google.dagger:hilt-android-testing", version.ref = "hilt-android-testing" }
androidx-test-runner          = { module = "androidx.test:runner", version.ref = "test-runner" }
androidx-test-rules           = { module = "androidx.test:rules", version.ref = "test-rules" }

[bundles]
androidTest = ["hilt-android-testing", "androidx-test-runner", "androidx-test-rules", "compose-ui-test-junit4"]
```

### 1.2 Configure `app/build.gradle.kts`

```kotlin
android {
    defaultConfig {
        testInstrumentationRunner = "com.openpod.testing.HiltTestRunner"
    }

    testOptions {
        managedDevices {
            localDevices {
                create("pixel6Api34") {
                    device = "Pixel 6"
                    apiLevel = 34
                    systemImageSource = "aosp-atd"
                }
            }
            groups {
                create("phoneTests") {
                    targetDevices.add(devices["pixel6Api34"])
                }
            }
        }
    }
}

dependencies {
    androidTestImplementation(libs.bundles.androidTest)
    androidTestAnnotationProcessor(libs.hilt.compiler)  // or ksp
    debugImplementation(libs.compose.ui.test.manifest)
}
```

### 1.3 Create Hilt test runner

File: `app/src/androidTest/kotlin/com/openpod/testing/HiltTestRunner.kt`

```kotlin
class HiltTestRunner : AndroidJUnitRunner() {
    override fun newApplication(cl: ClassLoader?, name: String?, context: Context?): Application {
        return super.newApplication(cl, HiltTestApplication::class.java.name, context)
    }
}
```

### 1.4 Convention plugin (optional)

If feature modules also need `androidTest/`, add an `AndroidTestConventionPlugin` in `build-logic/` that applies the Hilt test runner and common androidTest dependencies. Not required for Phase 1 since E2E tests live in `app/`.

## Phase 2: Test Scenarios

All tests go in `app/src/androidTest/kotlin/com/openpod/`.

### 2.1 Onboarding flow (first priority)

**File:** `OnboardingE2ETest.kt`

| Step | Action | Assertion |
|------|--------|-----------|
| 1 | App launches fresh | Welcome screen displayed |
| 2 | Tap "Get Started" | Navigates to ready screen |
| 3 | Tap "I'm Ready" | Navigates to pairing wizard |
| 4 | Pod emulator advertises | "Pod found" shown |
| 5 | Complete pairing wizard | Dashboard displayed |
| 6 | Kill and relaunch | Dashboard shown directly (onboarding skipped) |

```kotlin
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class OnboardingE2ETest {

    @get:Rule(order = 0)
    val hiltRule = HiltAndroidRule(this)

    @get:Rule(order = 1)
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun fresh_install_shows_onboarding_and_completes_to_dashboard() {
        composeRule.onNodeWithText("Get Started").performClick()
        composeRule.onNodeWithText("I'm Ready").performClick()
        composeRule.waitUntil(5_000) {
            composeRule.onAllNodesWithText("Dashboard").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText("Dashboard").assertIsDisplayed()
    }
}
```

### 2.2 Bolus delivery flow

**File:** `BolusE2ETest.kt`

| Step | Action | Assertion |
|------|--------|-----------|
| 1 | Start from dashboard (pod paired) | Dashboard with FAB visible |
| 2 | Tap bolus FAB | Bolus calculator screen |
| 3 | Enter dose via NumberPad | Dose displayed correctly |
| 4 | Tap confirm | Delivery progress shown |
| 5 | Wait for completion | Success confirmation |
| 6 | Return to dashboard | IOB chip updated |
| 7 | Switch to History tab | Bolus entry present |

### 2.3 Settings round-trip

**File:** `SettingsE2ETest.kt`

| Step | Action | Assertion |
|------|--------|-----------|
| 1 | Navigate to Settings tab | Settings screen shown |
| 2 | Change a value (e.g. max bolus) | Value updates in UI |
| 3 | Navigate away and back | Value persisted |
| 4 | Kill and relaunch | Value still persisted (DataStore) |

### 2.4 Pod expiry / re-pairing

**File:** `PodExpiryE2ETest.kt`

| Step | Action | Assertion |
|------|--------|-----------|
| 1 | Pod emulator reports expired state | Alert / status bar shows expiry |
| 2 | Tap on pod status | Navigates to pairing wizard |
| 3 | Complete re-pairing | Dashboard with new pod |

### 2.5 Navigation smoke test

**File:** `NavigationSmokeTest.kt`

| Step | Action | Assertion |
|------|--------|-----------|
| 1 | Tap each bottom nav item | Correct screen rendered |
| 2 | Press back from nested screen | Returns to parent |
| 3 | Rotate device | State preserved |

## Phase 3: Pod Emulator Integration

### 3.1 Port forwarding

The Python emulator listens on TCP. The AVD needs `adb forward` to reach it on the host:

```bash
adb forward tcp:19021 tcp:19021
```

This must happen after GMD boots the device but before tests run. Options:
- A `@BeforeClass` in a test base class that shells out via `UiDevice`
- A Gradle task that runs between device boot and test execution
- The Makefile orchestrating it externally

### 3.2 Deterministic emulator

Use `emulator-seed` (seed 42) so crypto handshakes produce identical outputs. This makes tests reproducible and avoids flaky timing from random key generation.

### 3.3 Emulator health check

Add a lightweight `/status` HTTP endpoint to the Python emulator (or a TCP ping) that the test setup can poll to confirm the emulator is ready before proceeding.

## Phase 4: Makefile Target

```makefile
.PHONY: test-ui test-ui-stop

EMU_PID_FILE := .emulator.pid

test-ui: ## Run full UI integration tests
	@echo "Starting pod emulator (seed 42)..."
	$(MAKE) emulator-seed & echo $$! > $(EMU_PID_FILE)
	@sleep 3
	@echo "Running UI tests on managed device..."
	./gradlew pixel6Api34DebugAndroidTest -PuseEmulator=true \
		--no-daemon \
		-Dandroid.testInstrumentationRunnerArguments.clearPackageData=true
	@$(MAKE) test-ui-stop

test-ui-stop:
	@if [ -f $(EMU_PID_FILE) ]; then \
		kill $$(cat $(EMU_PID_FILE)) 2>/dev/null || true; \
		rm -f $(EMU_PID_FILE); \
	fi
```

Usage: `make test-ui`

## Phase 5: Test Utilities

### 5.1 Test base class

File: `app/src/androidTest/kotlin/com/openpod/testing/BaseE2ETest.kt`

Encapsulates:
- Hilt rule + Compose rule ordering
- `IdlingResource` registration for coroutine dispatchers
- Common waiters (e.g., `waitForScreen("Dashboard")`)
- Screenshot capture on failure (for CI debugging)

### 5.2 Screen robot pattern

Keep tests readable by extracting screen interactions into robot objects:

```kotlin
class DashboardRobot(private val rule: AndroidComposeTestRule<*, *>) {
    fun tapBolusFab() = rule.onNodeWithContentDescription("Bolus").performClick()
    fun assertGlucoseDisplayed() = rule.onNodeWithTag("glucose_display").assertIsDisplayed()
    fun assertIob(value: String) = rule.onNodeWithTag("iob_chip").assertTextContains(value)
}

// In test:
val dashboard = DashboardRobot(composeRule)
dashboard.assertGlucoseDisplayed()
dashboard.tapBolusFab()
```

### 5.3 Test tags

Add `Modifier.testTag("...")` to key composables that lack unique text (FABs, chips, status bars). Keep tags in a shared `TestTags` object in `core:testing` so both production code and tests reference the same constants.

## File Structure (final)

```
app/src/androidTest/kotlin/com/openpod/
  testing/
    HiltTestRunner.kt
    BaseE2ETest.kt
    TestTags.kt
  robots/
    OnboardingRobot.kt
    DashboardRobot.kt
    BolusRobot.kt
    SettingsRobot.kt
    PairingRobot.kt
  e2e/
    OnboardingE2ETest.kt
    BolusE2ETest.kt
    SettingsE2ETest.kt
    PodExpiryE2ETest.kt
    NavigationSmokeTest.kt
```

## Implementation Order

| # | Task | Depends on | Effort |
|---|------|------------|--------|
| 1 | Add dependencies + GMD config to Gradle | — | Small |
| 2 | Create HiltTestRunner | #1 | Small |
| 3 | Add `testTag` modifiers to key composables | — | Small |
| 4 | Write NavigationSmokeTest | #1, #2 | Small |
| 5 | Write OnboardingE2ETest | #2, #3 | Medium |
| 6 | Add emulator port forwarding to test setup | — | Small |
| 7 | Write BolusE2ETest (needs pod emulator) | #5, #6 | Medium |
| 8 | Write SettingsE2ETest | #2, #3 | Small |
| 9 | Write PodExpiryE2ETest | #6 | Medium |
| 10 | Makefile `test-ui` target | #6 | Small |
| 11 | Robot pattern refactor | #5-#9 | Small |
| 12 | Screenshot-on-failure util | #2 | Small |

## Risks and Mitigations

| Risk | Mitigation |
|------|------------|
| GMD first boot downloads ~1 GB system image | CI caches `~/.android/avd/`; local runs cache after first use |
| Port forwarding timing | Health-check loop in `@BeforeClass` with 10s timeout |
| Flaky Compose animations | Use `composeRule.mainClock.autoAdvance = true` and `waitUntil` with generous timeouts |
| JUnit 4 vs JUnit 5 mismatch | Instrumented tests use JUnit 4 (Android requirement); unit tests stay on JUnit 5 — no conflict |
| BLE permission dialogs on API 31+ | Use `GrantPermissionRule` or UI Automator to auto-grant at test start |
