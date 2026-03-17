# Logging Implementation - Testing Strategy

**Date**: 2026-03-12
**Branch**: feature/logging-pii-filtering (all logging features)

---

## Current Test Coverage

### ✅ Unit Tests (Comprehensive)

**Logger Tests** (`LoggerTests.kt` - 148 lines)
- NoOpLogger behavior
- TestLogger message capture (debug, info, warn, error)
- TestLogger throwable capture
- TestLogger thread safety
- TestLogger log filtering
- LoggerFactory platform-specific creation

**LoggingHook Tests** (`LoggingHookTests.kt` - 528 lines, 22 tests)
- Before hook logging
- After hook logging
- Error hook logging
- Finally hook logging
- Evaluation context logging
- PII filtering (includeAttributes, excludeAttributes)
- Targeting key filtering (logTargetingKey parameter)
- Hook hints override behavior
- Multiple hook stages
- Log level configuration
- Provider metadata logging
- Client name logging

**Test Coverage Summary:**
```
✅ Core Logger interface
✅ Platform-specific LoggerFactory
✅ TestLogger functionality
✅ LoggingHook lifecycle stages
✅ PII filtering
✅ Targeting key filtering
✅ Context attribute filtering
✅ Hook hints
✅ Thread safety
```

---

## Testing Options

### Option 1: Run Existing Unit Tests ⭐ (Recommended First Step)

**What it tests:**
- All logging functionality in isolation
- PII filtering logic
- Hook lifecycle
- Thread safety
- Edge cases

**How to run:**
```bash
cd /Users/tyler.potter/projects/OpenFeature/kotlin-sdk

# Run all logging tests
./gradlew :kotlin-sdk:test --tests "*Logger*" --tests "*LoggingHook*"

# Or run all tests
./gradlew :kotlin-sdk:test
```

**Pros:**
- ✅ Fast (seconds)
- ✅ Comprehensive coverage
- ✅ Already written
- ✅ CI-ready

**Cons:**
- ❌ Doesn't test real providers
- ❌ Doesn't test actual log output formatting
- ❌ Doesn't test Android Logcat integration

**Verdict:** **Start here** - if these pass, the core functionality is solid.

---

### Option 2: Sample App Integration (Quick Manual Test)

**Location:** `/Users/tyler.potter/projects/OpenFeature/kotlin-sdk/sampleapp/`

**What it tests:**
- Real Android app integration
- LoggingHook with actual provider
- Android Logcat output
- UI interaction with logging

**How to test:**

1. **Update sampleapp to use local SDK:**
```kotlin
// sampleapp/build.gradle.kts
dependencies {
    implementation(project(":kotlin-sdk"))
    // Add if testing framework adapters:
    // implementation("com.jakewharton.timber:timber:5.0.1")
}
```

2. **Add LoggingHook to MainActivity:**
```kotlin
// sampleapp/src/main/kotlin/.../MainActivity.kt
import dev.openfeature.kotlin.sdk.hooks.LoggingHook
import dev.openfeature.kotlin.sdk.logging.LoggerFactory

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Create logger
        val logger = LoggerFactory.getLogger("FeatureFlags")

        // Add LoggingHook globally
        OpenFeatureAPI.addHooks(listOf(LoggingHook<Any>(logger = logger)))

        // Set provider (uses ExampleProvider from the app)
        OpenFeatureAPI.setProvider(ExampleProvider())

        // Rest of existing code...
    }
}
```

3. **Run the app:**
```bash
# Build and install
./gradlew :sampleapp:installDebug

# Watch Logcat
adb logcat -s "FeatureFlags:*" "OpenFeature:*"
```

4. **Verify output:**
```
D/FeatureFlags: Flag evaluation starting: flag='example-flag', type=BOOLEAN, defaultValue=false, provider='ExampleProvider'
D/FeatureFlags: Flag evaluation completed: flag='example-flag', value=true, variant='on', reason='STATIC', provider='ExampleProvider'
D/FeatureFlags: Flag evaluation finalized: flag='example-flag'
```

**Pros:**
- ✅ Real Android environment
- ✅ Real Logcat output
- ✅ Quick to set up (~5 minutes)
- ✅ Visual confirmation

**Cons:**
- ❌ Requires Android device/emulator
- ❌ Manual testing
- ❌ Limited provider testing (only ExampleProvider)

**Verdict:** **Good for quick validation** of Android integration and log formatting.

---

### Option 3: kotlin-sdk-contrib Provider Testing

**Location:** `/Users/tyler.potter/projects/OpenFeature/kotlin-sdk-contrib/`

**Available Providers:**
- `env-var` - Simple environment variable provider
- `ofrep` - OpenFeature Remote Evaluation Protocol provider

**What it tests:**
- LoggingHook with real contrib providers
- Multiple provider types
- Provider-specific event logging

**How to test:**

1. **Update contrib provider build.gradle.kts:**
```kotlin
// providers/env-var/build.gradle.kts (or ofrep)
dependencies {
    // Point to local SDK
    api(project(":kotlin-sdk")) // If in same workspace
    // OR
    // api("dev.openfeature.sdk:kotlin-sdk:0.8.0-SNAPSHOT") // If publishing locally
}
```

2. **Publish SDK locally:**
```bash
cd /Users/tyler.potter/projects/OpenFeature/kotlin-sdk

# Publish to local Maven
./gradlew publishToMavenLocal

# This creates:
# ~/.m2/repository/dev/openfeature/sdk/kotlin-sdk/0.8.0-SNAPSHOT/
```

3. **Update contrib settings.gradle.kts:**
```kotlin
dependencyResolutionManagement {
    repositories {
        mavenLocal() // Add this
        mavenCentral()
    }
}
```

4. **Write test in contrib provider:**
```kotlin
// providers/env-var/src/test/kotlin/LoggingIntegrationTest.kt
class LoggingIntegrationTest {
    @Test
    fun `logging hook captures env-var provider evaluations`() {
        val logger = TestLogger()
        val hook = LoggingHook<Any>(logger = logger)

        OpenFeatureAPI.addHooks(listOf(hook))
        OpenFeatureAPI.setProvider(EnvVarProvider())

        val client = OpenFeatureAPI.getClient()
        client.getBooleanValue("MY_FLAG", false)

        // Verify logging occurred
        assertTrue(logger.debugMessages.any {
            it.message.contains("Flag evaluation starting")
        })
    }
}
```

**Pros:**
- ✅ Tests with real providers
- ✅ Multiple provider types
- ✅ Can be automated

**Cons:**
- ❌ Requires local Maven publishing
- ❌ More setup complexity
- ❌ Limited provider variety (only 2)

**Verdict:** **Good for provider compatibility** testing, but more setup than needed for initial validation.

---

### Option 4: dd-sdk-android Integration (Most Realistic)

**Location:** Your Datadog SDK with feature flags module

**What it tests:**
- Real production-like environment
- Datadog provider with LoggingHook
- Full Android app context
- Actual use case

**How to test:**

1. **Publish kotlin-sdk locally:**
```bash
cd /Users/tyler.potter/projects/OpenFeature/kotlin-sdk
./gradlew publishToMavenLocal
```

2. **Update dd-sdk-android build.gradle:**
```kotlin
// features/dd-sdk-android-flags/build.gradle.kts
dependencies {
    // Temporarily use local SDK
    api("dev.openfeature.sdk:kotlin-sdk:0.8.0-SNAPSHOT")
    // Comment out: api("dev.openfeature.sdk:kotlin-sdk:0.7.1")
}
```

3. **Add LoggingHook in your example app:**
```kotlin
// In your DatadogFeatureFlagsProvider initialization
import dev.openfeature.kotlin.sdk.hooks.LoggingHook
import dev.openfeature.kotlin.sdk.logging.adapters.TimberLoggerAdapter
import timber.log.Timber

// Plant Timber if not already done
Timber.plant(Timber.DebugTree())

// Create logging hook
val logger = TimberLoggerAdapter()
val loggingHook = LoggingHook<Any>(
    logger = logger,
    logEvaluationContext = false // Don't log PII in production
)

// Add to OpenFeature
OpenFeatureAPI.addHooks(listOf(loggingHook))
```

4. **Run your example app and check logs:**
```bash
adb logcat -s "FeatureFlags:*" "Datadog:*"
```

**Pros:**
- ✅ Most realistic testing
- ✅ Real Datadog provider
- ✅ Production-like environment
- ✅ Tests Timber adapter
- ✅ Validates actual use case

**Cons:**
- ❌ Requires dd-sdk-android setup
- ❌ Most time-consuming
- ❌ Dependent on Datadog provider

**Verdict:** **Best for final validation** before shipping, but overkill for initial PR verification.

---

## Recommended Testing Workflow

### Phase 1: Quick Validation (5 minutes)
```bash
cd /Users/tyler.potter/projects/OpenFeature/kotlin-sdk

# Run unit tests
./gradlew :kotlin-sdk:test --tests "*Logger*" --tests "*LoggingHook*"

# Expected: All tests pass ✅
```

**If tests pass:** Core functionality is solid, proceed to Phase 2.
**If tests fail:** Fix issues before proceeding.

---

### Phase 2: Visual Confirmation (15 minutes)

**Option A: Use sampleapp (Recommended)**
1. Add LoggingHook to sampleapp/MainActivity.kt
2. Run on emulator: `./gradlew :sampleapp:installDebug`
3. Watch logs: `adb logcat -s "FeatureFlags:*"`
4. Interact with app, verify log output

**Option B: Use dd-sdk-android example app**
1. Publish SDK locally: `./gradlew publishToMavenLocal`
2. Update dd-sdk-android to use 0.8.0-SNAPSHOT
3. Add LoggingHook with Timber adapter
4. Run example app, verify Timber logs

**Expected Results:**
- Log messages appear in Logcat
- Format matches documentation examples
- Before/After/Finally stages log correctly
- No crashes or exceptions

---

### Phase 3: PR Submission (Automated)

Once Phases 1 & 2 pass:
1. Push branches
2. Create draft PRs
3. CI will run all unit tests automatically
4. Review PR output and tests in GitHub Actions

---

## Testing Matrix

| Test Type | Coverage | Speed | Setup | Realism | Recommendation |
|-----------|----------|-------|-------|---------|----------------|
| Unit Tests | ⭐⭐⭐⭐⭐ | ⚡⚡⚡ | ✅ None | ⭐⭐ | **Do First** |
| Sampleapp | ⭐⭐⭐ | ⚡⚡ | ⭐⭐⭐ | ⭐⭐⭐⭐ | **Do Second** |
| contrib | ⭐⭐⭐⭐ | ⚡ | ⭐ | ⭐⭐⭐ | Optional |
| dd-sdk-android | ⭐⭐⭐⭐⭐ | ⚡ | ⭐ | ⭐⭐⭐⭐⭐ | **Do Before Release** |

---

## My Recommendation

### For PR Creation: **Option 1 + Option 2A**

1. **Run unit tests** (5 min)
   ```bash
   ./gradlew :kotlin-sdk:test --tests "*Logger*" --tests "*LoggingHook*"
   ```

2. **Quick sampleapp test** (15 min)
   - Add LoggingHook to MainActivity
   - Run on emulator
   - Verify logs appear in Logcat
   - Test flag evaluation

3. **Create PRs**
   - Unit tests prove functionality
   - Manual test proves integration
   - CI will validate on push

### For Final Validation: **Option 4**

Before merging the final PR (#5 - PII Filtering):
- Integrate into dd-sdk-android example app
- Test with Datadog provider
- Verify Timber adapter works
- Confirm PII filtering works
- Test in production-like environment

---

## Quick Start Commands

```bash
# 1. Run unit tests
cd /Users/tyler.potter/projects/OpenFeature/kotlin-sdk
./gradlew :kotlin-sdk:test --tests "*Logger*" --tests "*LoggingHook*"

# 2. Build sampleapp
./gradlew :sampleapp:installDebug

# 3. Watch logs
adb logcat -s "FeatureFlags:*" "OpenFeature:*"

# 4. If needed: publish locally for dd-sdk-android
./gradlew publishToMavenLocal
```

---

## Expected Test Results

### Unit Tests
```
LoggerTests
  ✓ NoOpLogger executes without throwing
  ✓ TestLogger captures debug messages
  ✓ TestLogger captures info messages
  ✓ TestLogger captures warn messages
  ✓ TestLogger captures error messages
  ✓ TestLogger captures throwables
  ✓ TestLogger thread safety
  ✓ LoggerFactory creates platform logger

LoggingHookTests (22 tests)
  ✓ before hook logs flag evaluation start
  ✓ after hook logs successful evaluation
  ✓ error hook logs evaluation errors
  ✓ finally hook logs finalization
  ✓ logs evaluation context when enabled
  ✓ filters sensitive attributes by default
  ✓ filters targeting key when disabled
  ✓ hook hints override default behavior
  ... (14 more tests)
```

### Sampleapp Logcat
```
03-12 14:30:45.123 D/FeatureFlags: Flag evaluation starting: flag='show-new-feature', type=BOOLEAN, defaultValue=false, provider='ExampleProvider'
03-12 14:30:45.124 D/FeatureFlags: Flag evaluation completed: flag='show-new-feature', value=true, variant='enabled', reason='STATIC', provider='ExampleProvider'
03-12 14:30:45.125 D/FeatureFlags: Flag evaluation finalized: flag='show-new-feature'
```

---

## Conclusion

**For creating the PRs:** Run unit tests + quick sampleapp validation (20 minutes total)

**For shipping to production:** Add dd-sdk-android integration testing

The unit tests are comprehensive (22 tests, 528 lines) and cover all the logging functionality including PII filtering. If those pass, you can confidently create the PRs. The sampleapp provides quick visual confirmation that logging works in a real Android environment.
