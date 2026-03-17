# Provider Architecture Recommendations Summary

**Date**: 2026-03-12
**Status**: Proposal
**For**: OpenFeature Kotlin SDK

---

## TL;DR

✅ **DO: Split Provider API into separate package (v0.9.0)**
⚠️ **MAYBE: Add BaseFeatureProvider helper (post-1.0, if requested)**
❌ **DON'T: Move status management to providers (not needed)**

---

## Decision Matrix

| Proposal | Decision | Timeline | Rationale |
|----------|----------|----------|-----------|
| **Package Splitting** | ✅ Adopt | v0.9.0 | Reduces dependency size for vendors, enables independent versioning, proven pattern |
| **BaseFeatureProvider** | ⚠️ Consider Later | Post-1.0 | Wait for evidence it's needed, can add as opt-in helper without breaking changes |
| **Provider-Owned Status** | ❌ Reject | Not planned | Current event system works well, high migration cost, adds complexity |

---

## Recommended Architecture Evolution

### Current State (v0.8.0)

```
┌─────────────────────────────────────────┐
│    kotlin-sdk (single artifact)        │
│                                         │
│  ┌─────────────────────────────────┐   │
│  │  OpenFeatureAPI                 │   │
│  │  - Manages global status        │   │
│  │  - Listens to provider events   │   │
│  │  - Translates events → status   │   │
│  └─────────────────────────────────┘   │
│               ↓                         │
│  ┌─────────────────────────────────┐   │
│  │  FeatureProvider (interface)    │   │
│  │  - observe(): Flow<Events>      │   │
│  │  - initialize()                 │   │
│  │  - evaluate flags               │   │
│  └─────────────────────────────────┘   │
└─────────────────────────────────────────┘
         ↑
         │ depends on entire SDK
         │
    ┌────────────────┐
    │   Providers    │
    │  (contrib)     │
    └────────────────┘
```

**Problem**: Providers must depend on entire SDK (large transitive dependency)

---

### Target State (v0.9.0 → v1.0.0)

```
┌──────────────────────────────────────┐
│   provider-api (minimal artifact)   │  ← NEW: Stable 1.x versioning
│                                      │
│  ┌────────────────────────────────┐ │
│  │  FeatureProvider (interface)   │ │
│  │  ProviderEvaluation<T>         │ │
│  │  EvaluationContext             │ │
│  │  ProviderEvents (optional)     │ │
│  │  ErrorCode                     │ │
│  └────────────────────────────────┘ │
└──────────────────────────────────────┘
         ↑                      ↑
         │                      │
         │ api                  │ api
         │                      │
┌────────┴──────────┐    ┌──────┴──────────────────┐
│   kotlin-sdk      │    │   Vendor Providers      │
│  (orchestration)  │    │  (LaunchDarkly, etc.)   │
│                   │    │                         │
│ - OpenFeatureAPI  │    │  Smaller dependency!    │
│ - Client          │    │  Independent versioning!│
│ - Status mgmt     │    └─────────────────────────┘
└───────────────────┘
```

**Benefits**:
- Vendors depend only on ~10 types (not entire SDK)
- Provider API can stay stable 1.x while SDK evolves
- Better dependency resolution in Android/Gradle

---

## Implementation Phases

### Phase 1: Package Split (v0.9.0)

**Goal**: Create provider-api module without breaking existing code

**Changes**:

1. **Create new module** `provider-api/`
   ```kotlin
   // New package: dev.openfeature.kotlin.provider
   interface FeatureProvider { ... }
   data class ProviderEvaluation<T> { ... }
   interface EvaluationContext { ... }
   sealed class ProviderEvents { ... }
   ```

2. **Update SDK to depend on provider-api**
   ```kotlin
   // kotlin-sdk/build.gradle.kts
   dependencies {
       api(project(":provider-api"))
   }
   ```

3. **Add backward-compatible typealiases**
   ```kotlin
   // In kotlin-sdk module (temporary)
   package dev.openfeature.kotlin.sdk

   @Deprecated("Use dev.openfeature.kotlin.provider.FeatureProvider")
   typealias FeatureProvider = dev.openfeature.kotlin.provider.FeatureProvider
   ```

4. **Update contrib providers** to use new imports
   ```kotlin
   // Before
   import dev.openfeature.kotlin.sdk.FeatureProvider

   // After
   import dev.openfeature.kotlin.provider.FeatureProvider
   ```

**Testing**:
- All existing tests pass
- Providers work with both old and new imports
- No behavioral changes

**Published Artifacts**:
- `dev.openfeature:kotlin-provider-api:1.0.0-beta1`
- `dev.openfeature:kotlin-sdk:0.9.0`

---

### Phase 2: Stabilization (v1.0.0)

**Goal**: Commit to stable API contract

**Changes**:

1. **Provider API reaches 1.0.0**
   - Semantic versioning commitment
   - API stability guarantee
   - Documentation finalized

2. **SDK reaches 1.0.0**
   - Remove typealiases (breaking change)
   - Stabilize client API
   - Production-ready

3. **Versioning Policy**
   ```
   provider-api: 1.0.0 → 1.1.0 → 1.2.0 → 2.0.0
                  ↑       ↑       ↑       ↑
                 stable  minor   minor   breaking

   kotlin-sdk:   1.0.0 → 1.5.0 → 2.0.0 → 2.1.0
                  ↑       ↑       ↑       ↑
                 stable  SDK     SDK     still uses
                        changes changes  provider-api 1.x
   ```

**Migration**:
- Provider authors update imports (one-time)
- SDK consumers see no changes
- Vendors can depend on provider-api directly

---

### Phase 3: Optional Helpers (Post-1.0)

**Goal**: Reduce boilerplate IF providers request it

**Potential Additions** (all opt-in):

1. **ProviderStatusManager** (composition helper)
   ```kotlin
   // In provider-api module
   class ProviderStatusManager {
       val statusFlow: StateFlow<ProviderStatus>

       suspend fun transitionToReady()
       suspend fun transitionToError(error: ProviderError)
       suspend fun withLifecycleLock(block: suspend () -> Unit)
   }

   // Provider uses it
   class MyProvider : FeatureProvider {
       private val statusMgr = ProviderStatusManager()

       override suspend fun initialize(ctx: EvaluationContext?) {
           statusMgr.withLifecycleLock {
               loadFlags()
               statusMgr.transitionToReady()
           }
       }
   }
   ```

2. **BaseFeatureProvider** (inheritance helper)
   ```kotlin
   // In provider-api module
   abstract class BaseFeatureProvider : FeatureProvider {
       // Handles status state machine
       protected abstract suspend fun doInitialize(ctx: EvaluationContext?)

       final override suspend fun initialize(ctx: EvaluationContext?) {
           try {
               doInitialize(ctx)
               setReady()
           } catch (e: Exception) {
               setError(e)
           }
       }
   }

   // Simple provider
   class MyProvider : BaseFeatureProvider() {
       override suspend fun doInitialize(ctx: EvaluationContext?) {
           loadFlags() // Just implement business logic!
       }
   }
   ```

**Decision Criteria**:
- ✅ Add if 3+ providers duplicate status management logic
- ✅ Add if community requests it
- ❌ Don't add if not needed (YAGNI)

---

## What We're NOT Doing

### ❌ Moving Status to Providers

**Rejected because**:
- Current event system works well
- High migration cost (all providers break)
- Increases provider complexity
- SDK loses lifecycle guarantees
- No clear user benefit

**Current system**:
```kotlin
// Provider emits events (simple)
class MyProvider : FeatureProvider {
    private val events = MutableSharedFlow<OpenFeatureProviderEvents>()

    override suspend fun initialize(ctx: EvaluationContext?) {
        loadFlags()
        events.emit(ProviderReady())  // Simple!
    }

    override fun observe() = events
}

// SDK manages status (centralized)
OpenFeatureAPI.statusFlow  // Single source of truth
```

**Alternative (rejected) would require**:
```kotlin
// Provider manages status (complex)
class MyProvider : FeatureProvider {
    private val status = MutableStateFlow(ProviderStatus.NotReady)
    private val mutex = Mutex()

    override suspend fun initialize(ctx: EvaluationContext?) {
        mutex.withLock {  // Provider must handle concurrency
            try {
                loadFlags()
                status.emit(ProviderStatus.Ready)  // Provider must track state
            } catch (e: Exception) {
                status.emit(ProviderStatus.Error(e))  // More code!
            }
        }
    }

    override val statusFlow: Flow<ProviderStatus> = status
}
```

**Verdict**: Complexity increase not justified by benefits.

---

## Comparison to Other SDKs

| SDK | Split Package? | Base Provider? | Status Owner |
|-----|---------------|----------------|--------------|
| **Java** | ✅ Yes (`sdk` + `javasdk`) | ❌ No | SDK |
| **.NET** | ✅ Yes (`OpenFeature` + `OpenFeature.Sdk`) | ❌ No | SDK |
| **Go** | ❌ No (single module) | ❌ No | SDK |
| **JS** | ❌ No | ❌ No | SDK |
| **Python** | ❌ No | ❌ No | SDK |
| **Kotlin (proposed)** | ✅ Yes (`provider-api` + `kotlin-sdk`) | ⚠️ Maybe (post-1.0) | SDK |

**Pattern**: Mature SDKs (Java, .NET) split packages for provider independence. No SDK moves status to providers.

---

## Migration Guide for Provider Authors

### For Contrib Providers (kotlin-sdk-contrib)

**Before (v0.8.0)**:
```kotlin
// ofrep-provider/build.gradle.kts
dependencies {
    api("dev.openfeature:kotlin-sdk:0.8.0")  // ~400KB, many types
}

// OfrepProvider.kt
import dev.openfeature.kotlin.sdk.FeatureProvider
import dev.openfeature.kotlin.sdk.ProviderEvaluation
```

**After (v0.9.0)**:
```kotlin
// ofrep-provider/build.gradle.kts
dependencies {
    api("dev.openfeature:kotlin-provider-api:1.0.0-beta1")  // ~50KB, minimal types
}

// OfrepProvider.kt
import dev.openfeature.kotlin.provider.FeatureProvider
import dev.openfeature.kotlin.provider.ProviderEvaluation
```

**Migration Steps**:
1. Update `build.gradle.kts` dependency
2. Find-replace imports: `sdk.FeatureProvider` → `provider.FeatureProvider`
3. Run tests (behavior unchanged)
4. Publish new version

---

### For Vendor Providers (Native SDKs)

**Example: LaunchDarkly Kotlin Provider**

**Before**: Had to depend on entire OpenFeature SDK
```kotlin
dependencies {
    api("dev.openfeature:kotlin-sdk:0.8.0")
    api("com.launchdarkly:launchdarkly-android-client-sdk:4.2.0")
}
// Problem: Transitive dependency conflict if app uses different OpenFeature version
```

**After**: Depend only on provider interface
```kotlin
dependencies {
    api("dev.openfeature:kotlin-provider-api:1.0.0")  // Stable 1.x!
    api("com.launchdarkly:launchdarkly-android-client-sdk:4.2.0")
}
// Benefit: App can use any kotlin-sdk version that supports provider-api 1.x
```

---

## FAQ

### Q: Why not move status to providers like the proposals suggest?

**A**: The current event-based system works well and is proven. Moving status to providers:
- Breaks ALL existing providers (high migration cost)
- Increases provider complexity (every provider implements state machine)
- Loses SDK lifecycle guarantees
- Doesn't solve any reported user problems

The proposals (BaseFeatureProvider, package splitting) can be adopted WITHOUT moving status to providers.

---

### Q: What if we want BaseFeatureProvider later?

**A**: We can add it post-1.0 as an **opt-in helper** in the `provider-api` package. This doesn't break existing providers:

```kotlin
// Simple providers can extend base class
class SimpleProvider : BaseFeatureProvider() { ... }

// Complex providers implement interface directly
class ComplexProvider : FeatureProvider { ... }

// Both work!
```

---

### Q: Will package splitting break existing providers?

**A**: Not immediately. We'll use typealiases for backward compatibility in v0.9.0:

```kotlin
// Old code still works
import dev.openfeature.kotlin.sdk.FeatureProvider  // Deprecated but works

// New code uses new package
import dev.openfeature.kotlin.provider.FeatureProvider  // Recommended
```

In v1.0.0 we remove typealiases (breaking change), but provider migration is just updating imports.

---

### Q: How does versioning work with split packages?

**A**: Independent semantic versioning:

- **provider-api**: 1.0.0 → 1.x.x → 2.0.0 (only breaks on major)
- **kotlin-sdk**: 1.0.0 → 2.0.0 → 3.0.0 (can break more frequently)

Example:
- kotlin-sdk v2.0.0 can still use provider-api v1.x
- Providers built for provider-api v1.0 work with sdk v1.x, v2.x, v3.x
- Only when provider-api goes 1.x → 2.0 do providers need updates

---

### Q: What about Kotlin Multiplatform publishing?

**A**: Both modules will be published as KMP artifacts supporting:
- Android
- JVM
- JS (Node.js + Browser)
- Linux (Native)

Build configuration is straightforward (already proven in current SDK).

---

## Next Steps

### Immediate (Now)
- [ ] Review this proposal with SDK team
- [ ] Decide: Proceed with package split for v0.9.0?
- [ ] Create GitHub issue/RFC for community feedback

### v0.9.0 (If Approved)
- [ ] Create `provider-api` module
- [ ] Add typealiases for backward compatibility
- [ ] Update kotlin-sdk-contrib providers
- [ ] Publish beta versions for testing
- [ ] Write migration guide

### v1.0.0 (Stability)
- [ ] Remove typealiases
- [ ] Finalize API contracts
- [ ] Commit to semantic versioning
- [ ] Production release

### Post-1.0 (As Needed)
- [ ] Monitor provider author feedback
- [ ] Consider BaseFeatureProvider if requested
- [ ] Iterate on provider experience

---

## References

- **Full Analysis**: [EVENTS_AND_STATUS_ARCHITECTURE_ANALYSIS.md](./EVENTS_AND_STATUS_ARCHITECTURE_ANALYSIS.md)
- **OpenFeature Spec - Events**: `/Users/tyler.potter/projects/OpenFeature/spec/specification/sections/05-events.md`
- **OpenFeature Spec - Providers**: `/Users/tyler.potter/projects/OpenFeature/spec/specification/sections/02-providers.md`
- **Java SDK Split Pattern**: https://github.com/open-feature/java-sdk
- **.NET SDK Split Pattern**: https://github.com/open-feature/dotnet-sdk

---

**Document Owner**: OpenFeature Kotlin SDK Team
**Last Updated**: 2026-03-12
**Status**: Proposal for Review
