# Kotlin SDK Event Observation Analysis

**Date**: 2026-03-12
**Analysis**: Verification of dual observation mechanism in Kotlin SDK

---

## Executive Summary

✅ **VERIFIED**: The Kotlin SDK has **two distinct observation mechanisms** with different event streams:

1. **`statusFlow`** - SDK-managed status stream (lifecycle events + filtered provider events)
2. **`observe()`** - Direct provider event pass-through (no lifecycle events)

This creates **ambiguity** for application developers about which to use.

---

## 1. StatusFlow Analysis

### 1.1 Definition
```kotlin
// OpenFeatureAPI.kt:38-44
private val _statusFlow: MutableSharedFlow<OpenFeatureStatus> =
    MutableSharedFlow<OpenFeatureStatus>(replay = 1, extraBufferCapacity = 5)
        .apply {
            tryEmit(OpenFeatureStatus.NotReady)
        }

val statusFlow: Flow<OpenFeatureStatus> get() = _statusFlow.distinctUntilChanged()
```

### 1.2 Event Sources

#### A. Lifecycle Events (SDK-injected)

| Event | Trigger | Location |
|-------|---------|----------|
| `NotReady` | Provider initialization starts | Line 127 |
| `Ready` | Provider initialization completes | Line 138 |
| `NotReady` | Provider cleared | Line 156 |
| `Reconciling` | Context change starts | Line 201 |
| `Ready` | Context reconciliation completes | Line 204 |
| `Error` | Lifecycle method throws | Lines 215-224 |

**Code Evidence**:
```kotlin
// OpenFeatureAPI.kt:126-139
private suspend fun setProviderInternal(...) {
    // ... swap provider ...

    // Emit NotReady status after swapping provider
    _statusFlow.emit(OpenFeatureStatus.NotReady)  // LINE 127

    // ... shutdown old provider ...

    tryWithStatusEmitErrorHandling {
        listenToProviderEvents(provider, dispatcher)
        getProvider().initialize(context)
        _statusFlow.emit(OpenFeatureStatus.Ready)  // LINE 138
    }
}

// OpenFeatureAPI.kt:197-207
private suspend fun setEvaluationContextInternal(evaluationContext: EvaluationContext) {
    val oldContext = context
    context = evaluationContext
    if (oldContext != evaluationContext) {
        _statusFlow.emit(OpenFeatureStatus.Reconciling)  // LINE 201
        tryWithStatusEmitErrorHandling {
            getProvider().onContextSet(oldContext, evaluationContext)
            _statusFlow.emit(OpenFeatureStatus.Ready)  // LINE 204
        }
    }
}
```

#### B. Provider Events (Filtered Subset)

Provider events are subscribed to at line 107:
```kotlin
// OpenFeatureAPI.kt:104-109
private fun listenToProviderEvents(provider: FeatureProvider, dispatcher: CoroutineDispatcher) {
    observeProviderEventsJob?.cancel(...)
    this.observeProviderEventsJob = CoroutineScope(SupervisorJob() + dispatcher).launch {
        provider.observe().collect(handleProviderEvents)  // LINE 107
    }
}
```

**Filtered translation** happens via `handleProviderEvents`:
```kotlin
// OpenFeatureAPI.kt:294-311
private val handleProviderEvents: FlowCollector<OpenFeatureProviderEvents> = FlowCollector { providerEvent ->
    when (providerEvent) {
        is OpenFeatureProviderEvents.ProviderReady -> {
            _statusFlow.emit(OpenFeatureStatus.Ready)  // ✅ INCLUDED
        }

        is OpenFeatureProviderEvents.ProviderStale -> {
            _statusFlow.emit(OpenFeatureStatus.Stale)  // ✅ INCLUDED
        }

        is OpenFeatureProviderEvents.ProviderError -> {
            _statusFlow.emit(providerEvent.toOpenFeatureStatusError())  // ✅ INCLUDED
        }

        else -> {  // ❌ ALL OTHER EVENTS IGNORED
            // ProviderConfigurationChanged - NOT emitted to statusFlow
        }
    }
}
```

### 1.3 StatusFlow Event Summary

| Event Type | Source | Included in statusFlow? |
|------------|--------|------------------------|
| `NotReady` | SDK lifecycle | ✅ Yes |
| `Ready` | SDK lifecycle + Provider | ✅ Yes |
| `Reconciling` | SDK lifecycle | ✅ Yes |
| `Stale` | Provider (filtered) | ✅ Yes |
| `Error` | SDK lifecycle + Provider | ✅ Yes |
| `ConfigurationChanged` | Provider | ❌ **No** |

**Key Insight**: `ProviderConfigurationChanged` is **NOT** in statusFlow!

---

## 2. Observe() Method Analysis

### 2.1 Definition
```kotlin
// OpenFeatureAPI.kt:287-288
@OptIn(ExperimentalCoroutinesApi::class)
inline fun <reified T : OpenFeatureProviderEvents> observe(): Flow<T> = providersFlow
    .flatMapLatest { it.observe() }.filterIsInstance()
```

### 2.2 Behavior

**Direct pass-through** to provider's `observe()` method:
1. `providersFlow` emits current provider
2. `flatMapLatest` switches to new provider's observe() when provider changes
3. `filterIsInstance<T>()` filters by event type
4. **No transformation, no filtering, no lifecycle events added**

### 2.3 Observe() Event Summary

| Event Type | Source | Included in observe()? |
|------------|--------|----------------------|
| `ProviderReady` | Provider | ✅ Yes |
| `ProviderError` | Provider | ✅ Yes |
| `ProviderConfigurationChanged` | Provider | ✅ Yes |
| `ProviderStale` | Provider | ✅ Yes |
| `NotReady` | SDK lifecycle | ❌ **No** |
| `Reconciling` | SDK lifecycle | ❌ **No** |

**Key Insight**: SDK lifecycle events (NotReady, Reconciling) are **NOT** in observe()!

---

## 3. Side-by-Side Comparison

### 3.1 Event Availability Matrix

| Event | statusFlow | observe() | Notes |
|-------|-----------|-----------|-------|
| `NotReady` | ✅ | ❌ | Only via statusFlow (SDK-injected during setProvider) |
| `Ready` | ✅ | ✅* | statusFlow: SDK + filtered provider; observe: provider only |
| `Reconciling` | ✅ | ❌ | Only via statusFlow (SDK-injected during setContext) |
| `Stale` | ✅ | ✅ | statusFlow: filtered from provider; observe: direct |
| `Error` | ✅ | ✅ | statusFlow: SDK + filtered provider; observe: provider only |
| `ConfigurationChanged` | ❌ | ✅ | **Only via observe()** (filtered out of statusFlow) |

\* *Different types: statusFlow has `OpenFeatureStatus.Ready`, observe has `ProviderReady`*

### 3.2 Key Differences

| Aspect | statusFlow | observe() |
|--------|-----------|-----------|
| **Event types** | `OpenFeatureStatus` enum | `OpenFeatureProviderEvents` sealed class |
| **Lifecycle events** | ✅ Includes NotReady, Reconciling | ❌ Excludes lifecycle |
| **ConfigurationChanged** | ❌ Filtered out | ✅ Included |
| **Transformation** | ✅ Translates provider events | ❌ Direct pass-through |
| **Provider switching** | ✅ Seamless | ✅ Seamless (flatMapLatest) |

---

## 4. The Ambiguity Problem

### 4.1 Application Developer Confusion

**Scenario**: Developer wants to react when flags change

**Option 1**: Use statusFlow
```kotlin
OpenFeatureAPI.statusFlow.collect { status ->
    when (status) {
        is OpenFeatureStatus.Ready -> // Flags ready (but did they change?)
        // ❌ No ConfigurationChanged event!
    }
}
```

**Option 2**: Use observe()
```kotlin
OpenFeatureAPI.observe<ProviderConfigurationChanged>().collect { event ->
    // ✅ Flags changed
    // ❌ But no NotReady/Reconciling events
}
```

**Problem**: Developer must use **both** to get complete picture!

### 4.2 Missing Events per Stream

**If you only use statusFlow**:
- ❌ Miss `ConfigurationChanged` events
- Cannot distinguish between "ready from init" vs "flags changed"

**If you only use observe()**:
- ❌ Miss `NotReady` status (don't know provider is initializing)
- ❌ Miss `Reconciling` status (don't know context is changing)
- Cannot know SDK lifecycle state

### 4.3 Example: Detecting Flag Changes

**Current SDK** (ambiguous):
```kotlin
// Attempt 1: statusFlow - doesn't work
statusFlow.filterIsInstance<OpenFeatureStatus.Ready>().collect {
    // Is this ready from init, or flags changed? Can't tell!
}

// Attempt 2: observe() - missing lifecycle
observe<ProviderConfigurationChanged>().collect {
    // Flags changed, but is provider ready? Don't know!
}

// Required: Use both ❌
launch { statusFlow.collect { /* handle status */ } }
launch { observe<ProviderConfigurationChanged>().collect { /* handle config */ } }
```

---

## 5. Verification Summary

### 5.1 Community Member's Insight

> `statusFlow` is a filtered subset of events emitted from the provider (subscribed via `provider.observe()`) plus lifecycle events
>
> `observe` is just a straight pass through into the provider's `observe` method, so the lifecycle events normally emitted around `setProvider` and `setContext` are not in this stream

### 5.2 Code Verification

✅ **100% ACCURATE**

**Evidence**:

1. ✅ **statusFlow filters provider events**
   - Only Ready, Stale, Error included (lines 296-310)
   - ConfigurationChanged filtered out (line 308)

2. ✅ **statusFlow includes lifecycle events**
   - NotReady emitted at line 127, 156
   - Reconciling emitted at line 201
   - Ready emitted at line 138, 204
   - Error emitted at lines 215-224

3. ✅ **observe() is direct pass-through**
   - Line 287-288: `providersFlow.flatMapLatest { it.observe() }`
   - No transformation, just filtering by type
   - No lifecycle events added

4. ✅ **Lifecycle events NOT in observe()**
   - NotReady: SDK-only (not provider event)
   - Reconciling: SDK-only (not provider event)
   - Provider never emits these

---

## 6. Recommendations

### 6.1 For Current SDK Users

**Best Practice**: Use **both** streams for complete observability

```kotlin
// Track SDK status
launch {
    OpenFeatureAPI.statusFlow.collect { status ->
        when (status) {
            is NotReady -> // SDK initializing or cleared
            is Ready -> // SDK ready for evaluation
            is Reconciling -> // SDK updating context
            is Stale -> // Provider flagged as stale
            is Error -> // Error occurred
        }
    }
}

// Track flag configuration changes
launch {
    OpenFeatureAPI.observe<ProviderConfigurationChanged>().collect {
        // Flags changed, re-evaluate
    }
}
```

### 6.2 For Future Architecture

This analysis supports the **optimal wrapper architecture** proposal:

1. **Single observation point**: SDK should provide one unified stream
2. **Complete events**: Include both lifecycle and configuration changes
3. **Clear semantics**: Each event has clear meaning
4. **No ambiguity**: Developers don't need to use two APIs

**Proposed unified approach**:
```kotlin
// Future: Single stream with all events
OpenFeatureAPI.events().collect { event ->
    when (event) {
        is NotReady -> // SDK lifecycle
        is Ready -> // SDK lifecycle
        is Reconciling -> // SDK lifecycle
        is ConfigurationChanged -> // Provider event
        is Stale -> // Provider event
        is Error -> // Both sources
    }
}
```

---

## 7. Architectural Impact

### 7.1 Why This Matters for Provider Redesign

The current dual observation mechanism shows:

1. **SDK needs to aggregate** lifecycle + provider events
2. **Filtering is necessary** (not all provider events → status)
3. **Transformation is necessary** (provider events → SDK status)
4. **Provider shouldn't know** about SDK lifecycle events

This validates the **wrapper approach**:
- Wrapper handles lifecycle → status translation
- Provider emits raw events
- SDK aggregates and exposes unified stream
- Application developers use single API

### 7.2 Current Code Already Does Wrapping

The current SDK **already wraps** provider events:
```kotlin
listenToProviderEvents(provider, dispatcher)  // Wraps provider
provider.observe().collect(handleProviderEvents)  // Filters/transforms
_statusFlow.emit(...)  // Exposes to SDK
```

**Insight**: The architecture we proposed is **already partially implemented**! Just needs:
- ✅ Wrapping exists (line 107)
- ✅ Filtering exists (lines 294-311)
- ✅ Transformation exists (event → status)
- ❌ Status state machine logic scattered across SDK
- ❌ Two observation APIs instead of one

---

## 8. Conclusion

### 8.1 Verification Result

The community member's insight is **completely accurate**:

| Claim | Verified? | Evidence |
|-------|-----------|----------|
| statusFlow = filtered provider events | ✅ Yes | Lines 294-311 (filters ConfigurationChanged) |
| statusFlow includes lifecycle events | ✅ Yes | Lines 127, 138, 156, 201, 204 |
| observe() = direct pass-through | ✅ Yes | Lines 287-288 (flatMapLatest + filter) |
| observe() excludes lifecycle events | ✅ Yes | No lifecycle events added |

### 8.2 Implications

1. **For current users**: Must use both APIs for complete observability
2. **For SDK design**: Dual APIs create confusion
3. **For future work**: Wrapper approach should unify these streams
4. **For providers**: Current pattern (emit events, SDK translates) works well

### 8.3 Next Steps

The optimal provider architecture proposal addresses this by:
1. Keeping provider event emission simple
2. Wrapper manages status state machine
3. SDK exposes single unified observation API
4. Clear semantics: events = what happened, status = current state

---

## References

All line numbers reference: `/Users/tyler.potter/projects/OpenFeature/kotlin-sdk/kotlin-sdk/src/commonMain/kotlin/dev/openfeature/kotlin/sdk/OpenFeatureAPI.kt`

- statusFlow definition: Lines 38-44
- Provider event subscription: Line 107
- Event filtering: Lines 294-311
- observe() method: Lines 287-288
- Lifecycle event emissions: Lines 127, 138, 156, 201, 204
