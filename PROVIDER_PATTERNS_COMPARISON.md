# Provider Patterns Comparison: Three Approaches

**Date**: 2026-03-12
**Quick Reference**: Comparing Base Class, Interface Checking, and Current approaches

---

## TL;DR - Recommendation

✅ **Adopt**: Interface Checking Pattern (like Go SDK)
✅ **Adopt**: Package Splitting
❌ **Reject**: BaseFeatureProvider (base class approach)
❌ **Reject**: Moving status management to providers

---

## Three Approaches Compared

### Approach 1: Current Architecture

```kotlin
// Single interface with all methods
interface FeatureProvider {
    val hooks: List<Hook<*>>
    val metadata: ProviderMetadata

    // Lifecycle (has default implementations)
    suspend fun initialize(initialContext: EvaluationContext?)
    fun shutdown()
    suspend fun onContextSet(oldContext: EvaluationContext?, newContext: EvaluationContext)

    // Eventing (has default implementation)
    fun observe(): Flow<OpenFeatureProviderEvents> = emptyFlow()

    // Tracking (has default implementation)
    fun track(trackingEventName: String, context: EvaluationContext?, details: TrackingEventDetails?) {
        // no-op
    }

    // Evaluation (required, no defaults)
    fun getBooleanEvaluation(key: String, defaultValue: Boolean, context: EvaluationContext?): ProviderEvaluation<Boolean>
    fun getStringEvaluation(key: String, defaultValue: String, context: EvaluationContext?): ProviderEvaluation<String>
    fun getIntegerEvaluation(key: String, defaultValue: Int, context: EvaluationContext?): ProviderEvaluation<Int>
    fun getDoubleEvaluation(key: String, defaultValue: Double, context: EvaluationContext?): ProviderEvaluation<Double>
    fun getObjectEvaluation(key: String, defaultValue: Value, context: EvaluationContext?): ProviderEvaluation<Value>
}

// SDK calls methods directly
suspend fun initializeProvider(provider: FeatureProvider, ctx: EvaluationContext?) {
    provider.initialize(ctx)  // Always called, might be no-op
}

fun listenToEvents(provider: FeatureProvider) {
    provider.observe().collect { ... }  // Always called, might be emptyFlow()
}
```

**Pros**:
- ✅ Simple for SDK (just call methods)
- ✅ All in one place
- ✅ Works today

**Cons**:
- ❌ Large interface (many methods providers don't need)
- ❌ Unclear which methods are required vs optional
- ❌ Default implementations hidden in interface
- ❌ Can't distinguish "provider doesn't support X" from "provider returned empty"

---

### Approach 2: Base Class Pattern

```kotlin
// Abstract base class handles status management
abstract class BaseFeatureProvider : FeatureProvider {
    private val lifecycleMutex = Mutex()
    private val _statusFlow = MutableStateFlow<ProviderStatus>(ProviderStatus.NotReady)

    // FINAL - provider can't override
    final override suspend fun initialize(initialContext: EvaluationContext?) {
        lifecycleMutex.withLock {
            try {
                doInitialize(initialContext)  // Provider implements this
                _statusFlow.value = ProviderStatus.Ready
            } catch (e: Exception) {
                _statusFlow.value = ProviderStatus.Error(...)
            }
        }
    }

    // FINAL - provider can't override
    final override suspend fun onContextSet(oldContext: EvaluationContext?, newContext: EvaluationContext) {
        lifecycleMutex.withLock {
            _statusFlow.value = ProviderStatus.Reconciling
            try {
                doOnContextSet(oldContext, newContext)  // Provider implements this
                _statusFlow.value = ProviderStatus.Ready
            } catch (e: Exception) {
                _statusFlow.value = ProviderStatus.Error(...)
            }
        }
    }

    // Provider implements these
    protected abstract suspend fun doInitialize(initialContext: EvaluationContext?)
    protected abstract suspend fun doOnContextSet(oldContext: EvaluationContext?, newContext: EvaluationContext)

    // Still abstract - provider must implement
    abstract override fun getBooleanEvaluation(...): ProviderEvaluation<Boolean>
    // ...
}

// Provider MUST extend base class
class MyProvider : BaseFeatureProvider() {
    override suspend fun doInitialize(initialContext: EvaluationContext?) {
        // Just do initialization, base class handles status
    }

    override fun getBooleanEvaluation(...) = ...
}
```

**Pros**:
- ✅ Consistent status management
- ✅ Less boilerplate for simple providers
- ✅ "Guard rails" prevent provider mistakes

**Cons**:
- ❌ **Forces inheritance** (can't extend another class)
- ❌ **Breaking change** (all providers must migrate)
- ❌ **Un-idiomatic Kotlin** (prefer composition)
- ❌ **Locked into hierarchy** (can't mix providers)
- ❌ **Hard to test** (must mock base class)
- ❌ Still doesn't clarify optional vs required capabilities

---

### Approach 3: Interface Checking Pattern (✅ RECOMMENDED)

```kotlin
// Minimal required interface
interface FeatureProvider {
    val metadata: ProviderMetadata
    val hooks: List<Hook<*>>

    // ONLY evaluation methods - these are required
    fun getBooleanEvaluation(key: String, defaultValue: Boolean, context: EvaluationContext?): ProviderEvaluation<Boolean>
    fun getStringEvaluation(key: String, defaultValue: String, context: EvaluationContext?): ProviderEvaluation<String>
    fun getIntegerEvaluation(key: String, defaultValue: Int, context: EvaluationContext?): ProviderEvaluation<Int>
    fun getDoubleEvaluation(key: String, defaultValue: Double, context: EvaluationContext?): ProviderEvaluation<Double>
    fun getObjectEvaluation(key: String, defaultValue: Value, context: EvaluationContext?): ProviderEvaluation<Value>
}

// OPTIONAL: Lifecycle management
interface StateHandler {
    suspend fun initialize(initialContext: EvaluationContext?)
    suspend fun onContextSet(oldContext: EvaluationContext?, newContext: EvaluationContext)
    fun shutdown()
}

// OPTIONAL: Event emission
interface EventEmitter {
    fun observe(): Flow<OpenFeatureProviderEvents>
}

// OPTIONAL: Tracking
interface Tracker {
    fun track(trackingEventName: String, context: EvaluationContext?, details: TrackingEventDetails?)
}

// Simple provider - just evaluation
class StaticProvider : FeatureProvider {
    override val metadata = ...
    override val hooks = emptyList()
    override fun getBooleanEvaluation(...) = ...
}

// Complex provider - all capabilities
class RemoteProvider : FeatureProvider, StateHandler, EventEmitter, Tracker {
    override suspend fun initialize(...) = ...
    override fun observe() = eventFlow
    override fun track(...) = ...
    override fun getBooleanEvaluation(...) = ...
}

// SDK checks for capabilities at runtime
suspend fun initializeProvider(provider: FeatureProvider, ctx: EvaluationContext?): OpenFeatureStatus {
    return when (provider) {
        is StateHandler -> {
            try {
                provider.initialize(ctx)
                OpenFeatureStatus.Ready
            } catch (e: OpenFeatureError) {
                OpenFeatureStatus.Error(e)
            }
        }
        else -> {
            // Provider doesn't need initialization - ready immediately
            OpenFeatureStatus.Ready
        }
    }
}

fun listenToEvents(provider: FeatureProvider) {
    if (provider is EventEmitter) {
        provider.observe().collect { ... }
    }
    // else: no events to listen to
}
```

**Pros**:
- ✅ **Clear separation**: Required vs optional explicit
- ✅ **No forced inheritance**: Composition-friendly
- ✅ **Flexible**: Provider chooses complexity
- ✅ **Backward compatible**: Can add new optional interfaces
- ✅ **Idiomatic Kotlin**: Prefer interfaces and composition
- ✅ **Easy to test**: Mock individual capabilities
- ✅ **Smart casts**: Kotlin automatically casts after `is` check
- ✅ **Proven pattern**: Works well in Go SDK

**Cons**:
- ⚠️ Runtime checks instead of compile-time (minor - Kotlin smart casts help)
- ⚠️ Provider might forget to implement optional interface (mitigated by documentation)

---

## Side-by-Side Example

### Scenario: Simple File-Based Provider

#### Current Approach
```kotlin
class FileProvider : FeatureProvider {
    override val metadata = ...
    override val hooks = emptyList()

    // Must implement (even though we don't need it)
    override suspend fun initialize(initialContext: EvaluationContext?) {
        // no-op but must be declared
    }

    // Must implement (even though we don't need it)
    override fun shutdown() {
        // no-op but must be declared
    }

    // Must implement (even though we don't need it)
    override suspend fun onContextSet(oldContext: EvaluationContext?, newContext: EvaluationContext) {
        // no-op but must be declared
    }

    // Must implement (even though we don't need it)
    override fun observe() = emptyFlow<OpenFeatureProviderEvents>()

    // Must implement (even though we don't need it)
    override fun track(trackingEventName: String, context: EvaluationContext?, details: TrackingEventDetails?) {
        // no-op but must be declared
    }

    // Actually needed
    override fun getBooleanEvaluation(...) = loadFromFile(...)
}
```
**Lines of code**: ~20 (with no-ops)

#### Base Class Approach
```kotlin
class FileProvider : BaseFeatureProvider() {
    // Must implement (but we don't need lifecycle)
    override suspend fun doInitialize(initialContext: EvaluationContext?) {
        // no-op
    }

    // Must implement (but we don't need context changes)
    override suspend fun doOnContextSet(oldContext: EvaluationContext?, newContext: EvaluationContext) {
        // no-op
    }

    // Actually needed
    override fun getBooleanEvaluation(...) = loadFromFile(...)
}
```
**Lines of code**: ~15 (less no-ops, but locked into inheritance)

#### Interface Checking Approach
```kotlin
class FileProvider : FeatureProvider {
    override val metadata = ...
    override val hooks = emptyList()

    // Actually needed - that's it!
    override fun getBooleanEvaluation(...) = loadFromFile(...)
}
```
**Lines of code**: ~8 (minimal!)

---

### Scenario: Full-Featured Remote Provider

#### Current Approach
```kotlin
class RemoteProvider : FeatureProvider {
    private val eventFlow = MutableSharedFlow<OpenFeatureProviderEvents>()
    private var pollingJob: Job? = null

    override suspend fun initialize(initialContext: EvaluationContext?) {
        fetchFlags()
        startPolling()
        eventFlow.emit(ProviderReady())
    }

    override fun shutdown() {
        pollingJob?.cancel()
    }

    override suspend fun onContextSet(old: EvaluationContext?, new: EvaluationContext) {
        eventFlow.emit(ProviderStale())
        fetchFlags()
        eventFlow.emit(ProviderReady())
    }

    override fun observe() = eventFlow

    override fun track(...) {
        sendToServer(...)
    }

    override fun getBooleanEvaluation(...) = ...
}
```
**Clarity**: ⚠️ Not obvious which methods are optional vs required

#### Base Class Approach
```kotlin
class RemoteProvider : BaseFeatureProvider() {
    private var pollingJob: Job? = null

    override suspend fun doInitialize(initialContext: EvaluationContext?) {
        fetchFlags()
        startPolling()
        // Base class emits ProviderReady automatically
    }

    override suspend fun doOnContextSet(old: EvaluationContext?, new: EvaluationContext) {
        // Base class emits Reconciling automatically
        fetchFlags()
        // Base class emits Ready automatically
    }

    override fun doShutdown() {
        pollingJob?.cancel()
    }

    // Problem: How do we send events? How do we track?
    // Base class doesn't help with these...
}
```
**Clarity**: ⚠️ Lifecycle is clearer, but eventing/tracking still unclear

#### Interface Checking Approach
```kotlin
class RemoteProvider :
    FeatureProvider,      // Required
    StateHandler,         // We need lifecycle
    EventEmitter,         // We emit events
    Tracker {             // We support tracking

    private val eventFlow = MutableSharedFlow<OpenFeatureProviderEvents>()
    private var pollingJob: Job? = null

    // StateHandler implementation
    override suspend fun initialize(initialContext: EvaluationContext?) {
        fetchFlags()
        startPolling()
        eventFlow.emit(ProviderReady())
    }

    override suspend fun onContextSet(old: EvaluationContext?, new: EvaluationContext) {
        eventFlow.emit(ProviderStale())
        fetchFlags()
        eventFlow.emit(ProviderReady())
    }

    override fun shutdown() {
        pollingJob?.cancel()
    }

    // EventEmitter implementation
    override fun observe() = eventFlow

    // Tracker implementation
    override fun track(...) {
        sendToServer(...)
    }

    // FeatureProvider implementation
    override fun getBooleanEvaluation(...) = ...
}
```
**Clarity**: ✅ Crystal clear what capabilities this provider has!

---

## Decision Matrix

| Criteria | Current | Base Class | Interface Checking |
|----------|---------|------------|-------------------|
| **Simplicity for simple providers** | ⚠️ Medium (many no-ops) | ⚠️ Medium (some no-ops) | ✅ High (minimal) |
| **Clarity of capabilities** | ❌ Low (everything looks optional) | ⚠️ Medium (lifecycle clear, rest unclear) | ✅ High (explicit interfaces) |
| **Flexibility** | ✅ High (can override any method) | ❌ Low (locked into base class) | ✅ High (pick interfaces) |
| **Backward compatibility** | ✅ No change | ❌ Breaking (must extend base) | ✅ Can add gradually |
| **Kotlin idioms** | ⚠️ Okay | ❌ Anti-pattern (favor composition) | ✅ Idiomatic |
| **Testing ease** | ⚠️ Medium (mock big interface) | ❌ Hard (mock base class) | ✅ Easy (mock small interfaces) |
| **Compile-time safety** | ⚠️ Medium (defaults can hide bugs) | ✅ High (enforced hierarchy) | ⚠️ Medium (runtime checks) |
| **Provider author experience** | ⚠️ Confusing (what's required?) | ⚠️ Restrictive (forced structure) | ✅ Clear (explicit intent) |
| **SDK maintenance** | ⚠️ Medium (defaults scattered) | ❌ High (base class complexity) | ✅ Low (simple checks) |
| **Proven in production** | ✅ Yes (current Kotlin SDK) | ⚠️ No (new approach) | ✅ Yes (Go SDK) |

---

## Migration Comparison

### From Current → Base Class
**Impact**: ❌ **BREAKING CHANGE**

```kotlin
// Before
class MyProvider : FeatureProvider {
    override suspend fun initialize(...) = ...
}

// After
class MyProvider : BaseFeatureProvider() {  // Must extend
    override suspend fun doInitialize(...) = ...  // Different method!
}
```
**Effort**: High - All providers break

---

### From Current → Interface Checking
**Impact**: ✅ **NON-BREAKING (with deprecation period)**

```kotlin
// Before (v0.8.0)
class MyProvider : FeatureProvider {
    override suspend fun initialize(...) = ...
    override fun observe() = eventFlow
}

// Transition (v0.9.0) - Both work!
class MyProvider :
    FeatureProvider,      // Keep this
    StateHandler,         // Add this (new)
    EventEmitter {        // Add this (new)

    // Old method (deprecated but still works)
    @Deprecated("Use StateHandler.initialize")
    override suspend fun initialize(...) = ...

    // OR new method (preferred)
    override suspend fun initialize(...) = ...  // Same signature!

    override fun observe() = eventFlow
}

// After (v1.0.0) - Clean
class MyProvider : FeatureProvider, StateHandler, EventEmitter {
    override suspend fun initialize(...) = ...
    override fun observe() = eventFlow
}
```
**Effort**: Low - Add interface declarations, same method implementations

---

## Final Recommendation

### Adopt Interface Checking

**Why**:
1. ✅ Most flexible (provider chooses capabilities)
2. ✅ Clearest intent (explicit interfaces)
3. ✅ Easiest migration (non-breaking with deprecation)
4. ✅ Most idiomatic Kotlin (composition over inheritance)
5. ✅ Proven pattern (Go SDK)
6. ✅ Best for testing (mock small interfaces)
7. ✅ Future-proof (can add new optional interfaces without breaking)

**Implementation Plan**:
1. **v0.9.0**: Add optional interfaces (`StateHandler`, `EventEmitter`, `Tracker`)
2. **v0.9.0**: Deprecate methods in main `FeatureProvider` interface
3. **v0.9.0**: SDK checks for new interfaces first, falls back to deprecated
4. **v1.0.0**: Remove deprecated methods from `FeatureProvider`
5. **v1.0.0**: Providers must explicitly implement optional interfaces

**Combined with**:
- ✅ Package splitting (from previous analysis)
- ❌ NOT moving status to providers (SDK keeps control)

---

## Example Provider Signatures

### Simple Static Provider
```kotlin
class StaticProvider(private val flags: Map<String, Any>) : FeatureProvider {
    // That's it! Just 3 required members + evaluation methods
}
```

### Provider with Lifecycle
```kotlin
class LifecycleProvider : FeatureProvider, StateHandler {
    // Required + lifecycle
}
```

### Provider with Events
```kotlin
class EventingProvider : FeatureProvider, EventEmitter {
    // Required + events
}
```

### Full-Featured Provider
```kotlin
class FullProvider : FeatureProvider, StateHandler, EventEmitter, Tracker {
    // All capabilities
}
```

### Multi-Provider (Meta-provider)
```kotlin
class MultiProvider(children: List<FeatureProvider>) :
    FeatureProvider,    // Always
    StateHandler,       // Aggregates child lifecycle
    EventEmitter {      // Aggregates child events

    // Delegates to children based on THEIR capabilities
}
```

---

## References

- **Go SDK Pattern**: `/Users/tyler.potter/projects/OpenFeature/go-sdk/openfeature/provider.go`
- **Go SDK Checking**: `/Users/tyler.potter/projects/OpenFeature/go-sdk/openfeature/openfeature_api.go:366`
- **Full Analysis**: `/Users/tyler.potter/projects/OpenFeature/kotlin-sdk/INTERFACE_CHECKING_PATTERN_ANALYSIS.md`
- **Architecture Analysis**: `/Users/tyler.potter/projects/OpenFeature/kotlin-sdk/EVENTS_AND_STATUS_ARCHITECTURE_ANALYSIS.md`
- **Recommendations**: `/Users/tyler.potter/projects/OpenFeature/kotlin-sdk/PROVIDER_ARCHITECTURE_RECOMMENDATIONS.md`
