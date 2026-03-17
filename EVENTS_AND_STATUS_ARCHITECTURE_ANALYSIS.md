# Architectural Analysis: Moving Event and Status Handling to Providers

## Executive Summary

This document analyzes the current event handling and status management architecture in the OpenFeature Kotlin SDK and evaluates what would be required to move these responsibilities from the SDK layer down to individual vendor Providers.

**Current Architecture**: The SDK maintains a centralized status management system (`OpenFeatureAPI`) that orchestrates provider lifecycle, listens to provider events, and translates them into SDK-level status transitions.

**Proposed Change**: Move status and event handling responsibilities down to providers, making each provider independently responsible for its own state management.

## Table of Contents

1. [Current Event System Architecture](#1-current-event-system-architecture)
2. [Current Provider Interface & Contract](#2-current-provider-interface--contract)
3. [Current Status Management System](#3-current-status-management-system)
4. [Dependencies and Coupling](#4-dependencies-and-coupling)
5. [What Would Need to Change](#5-what-would-need-to-change)
6. [Impact Analysis](#6-impact-analysis)
7. [Recommendation](#7-recommendation)

---

## 1. Current Event System Architecture

### 1.1 Event Flow

The current architecture follows a **centralized event aggregation pattern**:

```
Provider (emits events)
    ↓
    ↓ via observe(): Flow<OpenFeatureProviderEvents>
    ↓
OpenFeatureAPI (listens and translates)
    ↓
    ↓ updates _statusFlow: MutableSharedFlow<OpenFeatureStatus>
    ↓
Clients (observe statusFlow)
```

### 1.2 Provider Event Types

Providers can emit the following events (defined in `/Users/tyler.potter/projects/OpenFeature/kotlin-sdk/kotlin-sdk/src/commonMain/kotlin/dev/openfeature/kotlin/sdk/events/OpenFeatureProviderEvents.kt`):

```kotlin
sealed class OpenFeatureProviderEvents {
    data class ProviderReady(...)
    data class ProviderError(...)
    data class ProviderConfigurationChanged(...)
    data class ProviderStale(...)
    @Deprecated data object ProviderNotReady
}
```

Each event includes optional `EventDetails`:
- `flagsChanged: Set<String>` - list of changed flag keys
- `message: String?` - informational message
- `errorCode: ErrorCode?` - error classification
- `eventMetadata: Map<String, Any>` - arbitrary metadata

### 1.3 SDK Status Types

The SDK translates provider events into status states (defined in `/Users/tyler.potter/projects/OpenFeature/kotlin-sdk/kotlin-sdk/src/commonMain/kotlin/dev/openfeature/kotlin/sdk/OpenFeatureStatus.kt`):

```kotlin
sealed interface OpenFeatureStatus {
    object NotReady        // Provider not initialized
    object Ready           // Provider ready for evaluations
    class Error(...)       // Recoverable error state
    class Fatal(...)       // Irrecoverable error state
    object Stale           // Cached state may be outdated
    object Reconciling     // Context change in progress
}
```

### 1.4 Event Translation Logic

The SDK handles provider events in `OpenFeatureAPI.handleProviderEvents` (lines 294-311 in `/Users/tyler.potter/projects/OpenFeature/kotlin-sdk/kotlin-sdk/src/commonMain/kotlin/dev/openfeature/kotlin/sdk/OpenFeatureAPI.kt`):

```kotlin
private val handleProviderEvents: FlowCollector<OpenFeatureProviderEvents> = FlowCollector { providerEvent ->
    when (providerEvent) {
        is OpenFeatureProviderEvents.ProviderReady -> {
            _statusFlow.emit(OpenFeatureStatus.Ready)
        }
        is OpenFeatureProviderEvents.ProviderStale -> {
            _statusFlow.emit(OpenFeatureStatus.Stale)
        }
        is OpenFeatureProviderEvents.ProviderError -> {
            _statusFlow.emit(providerEvent.toOpenFeatureStatusError())
        }
        else -> { } // ConfigurationChanged passes through
    }
}
```

### 1.5 Event Subscription Lifecycle

The SDK manages event subscription in `setProviderInternal()`:

1. **Provider Swap**: Old provider is atomically replaced with new provider (lines 117-124)
2. **Status Emission**: `NotReady` status emitted (line 127)
3. **Old Provider Shutdown**: Previous provider cleaned up (lines 130-132)
4. **Event Listener Setup**: New provider's event flow is subscribed (line 136)
5. **Provider Initialization**: New provider initialized (line 137)
6. **Status Update**: `Ready` status emitted on success (line 138)

The event listener is set up via `listenToProviderEvents()` (lines 104-109):

```kotlin
private fun listenToProviderEvents(provider: FeatureProvider, dispatcher: CoroutineDispatcher) {
    observeProviderEventsJob?.cancel(...)
    this.observeProviderEventsJob = CoroutineScope(SupervisorJob() + dispatcher).launch {
        provider.observe().collect(handleProviderEvents)
    }
}
```

---

## 2. Current Provider Interface & Contract

### 2.1 Provider Interface Definition

From `/Users/tyler.potter/projects/OpenFeature/kotlin-sdk/kotlin-sdk/src/commonMain/kotlin/dev/openfeature/kotlin/sdk/FeatureProvider.kt`:

```kotlin
interface FeatureProvider {
    val hooks: List<Hook<*>>
    val metadata: ProviderMetadata

    // Lifecycle methods
    suspend fun initialize(initialContext: EvaluationContext?)
    fun shutdown()
    suspend fun onContextSet(oldContext: EvaluationContext?, newContext: EvaluationContext)

    // Flag evaluation methods (5 typed methods)
    fun getBooleanEvaluation(...)
    fun getStringEvaluation(...)
    fun getIntegerEvaluation(...)
    fun getDoubleEvaluation(...)
    fun getObjectEvaluation(...)

    // Optional tracking
    fun track(trackingEventName: String, context: EvaluationContext?, details: TrackingEventDetails?) {
        // default no-op
    }

    // Optional event emission
    fun observe(): Flow<OpenFeatureProviderEvents> {
        return emptyFlow() // default no events
    }
}
```

### 2.2 Provider Responsibilities

**Current Responsibilities:**
1. **Flag Evaluation**: Resolve flag values from backing store
2. **Lifecycle Management**: Initialize, shutdown, handle context changes
3. **Event Emission (Optional)**: Publish state changes via Flow
4. **Hook Registration (Optional)**: Provide provider-level hooks
5. **Tracking (Optional)**: Handle tracking events

**Not Provider Responsibilities:**
1. Status state machine management (handled by SDK)
2. Event-to-status translation (handled by SDK)
3. Global status aggregation (handled by SDK)
4. Client notification (handled by SDK)

### 2.3 Provider Implementation Examples

#### NoOpProvider (Minimal Implementation)
From `/Users/tyler.potter/projects/OpenFeature/kotlin-sdk/kotlin-sdk/src/commonMain/kotlin/dev/openfeature/kotlin/sdk/NoOpProvider.kt`:

- No events emitted (`observe()` not overridden, returns `emptyFlow()`)
- No-op lifecycle methods
- Returns default values for all evaluations

#### OfrepProvider (Full-Featured Implementation)
From `/Users/tyler.potter/projects/OpenFeature/kotlin-sdk-contrib/providers/ofrep/src/commonMain/kotlin/dev/openfeature/kotlin/contrib/providers/ofrep/OfrepProvider.kt`:

- **Maintains internal state**: `@Volatile private var inMemoryCache`, `retryAfter: Instant?`
- **Emits events**: via `private val statusFlow = MutableSharedFlow<OpenFeatureProviderEvents>()`
- **Lifecycle management**:
  - `initialize()`: Fetches initial flags, emits `ProviderReady` or `ProviderError`
  - `startPolling()`: Background polling job for flag updates
  - `onContextSet()`: Re-evaluates on context changes, emits `ProviderStale` then `ProviderReady`
  - `shutdown()`: Cancels polling job

#### MultiProvider (Composite Pattern)
From `/Users/tyler.potter/projects/OpenFeature/kotlin-sdk/kotlin-sdk/src/commonMain/kotlin/dev/openfeature/kotlin/sdk/multiprovider/MultiProvider.kt`:

- **Aggregates child provider statuses**: Maintains `childProviderStatuses: MutableMap<ChildFeatureProvider, OpenFeatureStatus>`
- **Implements status precedence logic**: Maps events to statuses, picks highest precedence
- **Re-emits events**: Forwards child provider events as own events
- **Manages child lifecycle**: Initializes all children, forwards context changes

---

## 3. Current Status Management System

### 3.1 Status State Machine

The SDK implements a centralized state machine in `OpenFeatureAPI`:

```
     ┌──────────────┐
     │   NotReady   │ ← Initial state
     └──────┬───────┘
            │ setProvider() called
            ↓
     ┌──────────────┐
     │ initialize() │
     └──────┬───────┘
            │
      ┌─────┴─────┐
      │ Success   │ Failure
      ↓           ↓
┌──────────┐  ┌───────┐
│  Ready   │  │ Error │
└────┬─────┘  └───┬───┘
     │            │
     │ setEvaluationContext() called
     ↓            ↓
┌──────────────┐  │
│ Reconciling  │  │
└──────┬───────┘  │
       │          │
   ┌───┴──┐       │
   │Success│ Failure
   ↓      ↓       ↓
┌──────┐ ┌───────┐
│Ready │ │ Error │ ← Can also transition to Fatal
└──────┘ └───────┘
     │       │
     │       │ Provider emits ProviderStale
     ↓       ↓
  ┌──────────┐
  │  Stale   │
  └──────────┘
```

### 3.2 Status Transition Triggers

| Transition | Trigger | Location in Code |
|------------|---------|------------------|
| `NotReady` → `Ready` | `initialize()` success | `OpenFeatureAPI.setProviderInternal()` line 138 |
| `NotReady` → `Error` | `initialize()` failure | `OpenFeatureAPI.tryWithStatusEmitErrorHandling()` line 215 |
| `Ready` → `Reconciling` | `setEvaluationContext()` | `OpenFeatureAPI.setEvaluationContextInternal()` line 201 |
| `Reconciling` → `Ready` | `onContextSet()` success | `OpenFeatureAPI.setEvaluationContextInternal()` line 204 |
| `Reconciling` → `Error` | `onContextSet()` failure | `OpenFeatureAPI.tryWithStatusEmitErrorHandling()` line 215 |
| Any → `Stale` | Provider emits `ProviderStale` | `OpenFeatureAPI.handleProviderEvents` line 301 |
| Any → `Error`/`Fatal` | Provider emits `ProviderError` | `OpenFeatureAPI.handleProviderEvents` line 305 |

### 3.3 Status Observation

Clients observe status via two mechanisms:

1. **Direct Status Check**: `OpenFeatureAPI.getStatus()` - returns current status from replay cache
2. **Status Flow**: `OpenFeatureAPI.statusFlow` - reactive stream of status changes

From `/Users/tyler.potter/projects/OpenFeature/kotlin-sdk/kotlin-sdk/src/commonMain/kotlin/dev/openfeature/kotlin/sdk/Client.kt`:

```kotlin
interface Client : Features, Tracking {
    val statusFlow: Flow<OpenFeatureStatus>
    // ...
}
```

Clients receive status updates via the inherited `statusFlow` property (line 27 in `OpenFeatureClient.kt`):

```kotlin
override val statusFlow = openFeatureAPI.statusFlow
```

### 3.4 Status-Based Behavior

The SDK uses status to control flag evaluation behavior:

From `OpenFeatureClient.evaluateFlag()` (lines 221-228):

```kotlin
private fun shortCircuitIfNotReady() {
    val providerStatus = openFeatureAPI.getStatus()
    if (providerStatus == OpenFeatureStatus.NotReady) {
        throw OpenFeatureError.ProviderNotReadyError()
    } else if (providerStatus is OpenFeatureStatus.Fatal) {
        throw OpenFeatureError.ProviderFatalError()
    }
}
```

This is called before every flag evaluation (line 192 in `evaluateFlag()`).

---

## 4. Dependencies and Coupling

### 4.1 SDK → Provider Dependencies

The SDK depends on providers for:

1. **Event Emission**: `provider.observe(): Flow<OpenFeatureProviderEvents>`
2. **Lifecycle Execution**: `initialize()`, `onContextSet()`, `shutdown()`
3. **Flag Resolution**: `get*Evaluation()` methods

### 4.2 Provider → SDK Dependencies

Providers depend on the SDK for:

1. **Event Type Definitions**: `OpenFeatureProviderEvents` sealed class
2. **Error Code Enums**: `ErrorCode` for error classification
3. **Context Types**: `EvaluationContext` for targeting
4. **Value Types**: `Value`, `ProviderEvaluation` for flag results

### 4.3 Tight Coupling Points

#### 4.3.1 Event-to-Status Translation

The mapping from provider events to SDK status is hardcoded in `handleProviderEvents` and `toOpenFeatureStatusError()`:

From `/Users/tyler.potter/projects/OpenFeature/kotlin-sdk/kotlin-sdk/src/commonMain/kotlin/dev/openfeature/kotlin/sdk/events/OpenFeatureProviderEvents.kt` (lines 50-73):

```kotlin
internal fun OpenFeatureProviderEvents.ProviderError.toOpenFeatureStatusError(): OpenFeatureStatus {
    return when {
        eventDetails?.errorCode != null -> {
            val openFeatureError = OpenFeatureError.fromMessageAndErrorCode(...)
            if (eventDetails.errorCode == ErrorCode.PROVIDER_FATAL) {
                OpenFeatureStatus.Fatal(openFeatureError)
            } else {
                OpenFeatureStatus.Error(openFeatureError)
            }
        }
        error != null -> { /* Deprecated path */ }
        else -> OpenFeatureStatus.Error(OpenFeatureError.GeneralError("Unspecified error"))
    }
}
```

This logic **cannot be customized by providers** - the SDK owns the translation.

#### 4.3.2 Status State Machine

The state machine logic is entirely in `OpenFeatureAPI`:

- `Reconciling` state is **never emitted by providers** - it's injected by the SDK during `setEvaluationContext()`
- Providers cannot directly set status to `NotReady`, `Reconciling`, or `Fatal` (except via error code)
- The SDK controls when `Ready` is emitted (after initialization success)

#### 4.3.3 Global Status Aggregation

The SDK maintains a **single global status** via `_statusFlow`. This creates coupling because:

- Multiple providers cannot have different statuses simultaneously (no named clients/domains support)
- Provider status is lost when provider is swapped
- MultiProvider has to re-implement status aggregation logic

### 4.4 Shared State Management

From `OpenFeatureAPI` (lines 28-42):

```kotlin
object OpenFeatureAPI {
    private var setProviderJob: Job? = null
    private var setEvaluationContextJob: Job? = null
    private var observeProviderEventsJob: Job? = null

    private val providerMutex = Mutex()
    private var provider: FeatureProvider = NOOP_PROVIDER
    private var context: EvaluationContext? = null
    val providersFlow: MutableStateFlow<FeatureProvider> = MutableStateFlow(NOOP_PROVIDER)

    private val _statusFlow: MutableSharedFlow<OpenFeatureStatus> = ...
    val statusFlow: Flow<OpenFeatureStatus> get() = _statusFlow.distinctUntilChanged()
```

The SDK manages:
- Provider lifecycle jobs
- Thread safety (mutex)
- Current provider reference
- Global evaluation context
- Status broadcasting

---

## 5. What Would Need to Change

### 5.1 Provider Interface Changes

#### Option A: Providers Own Status Completely

Providers would expose their own status:

```kotlin
interface FeatureProvider {
    // New: Provider exposes its own status
    val statusFlow: Flow<ProviderStatus>
    fun getStatus(): ProviderStatus

    // Remove: No more SDK-level events
    // fun observe(): Flow<OpenFeatureProviderEvents>

    // Existing methods unchanged
    suspend fun initialize(initialContext: EvaluationContext?)
    // ... etc
}

// New status type owned by provider
sealed interface ProviderStatus {
    object NotReady
    object Ready
    data class Error(val error: ProviderError)
    data class Fatal(val error: ProviderError)
    object Stale
    object Reconciling
}
```

**Implications:**
- Every provider must implement status management
- NoOpProvider must track status (currently doesn't)
- Breaking change for all existing providers
- Provider authors have more implementation burden

#### Option B: Hybrid - SDK Observes Provider Status

SDK listens to provider status instead of events:

```kotlin
interface FeatureProvider {
    // Providers expose status
    fun observeStatus(): Flow<ProviderStatus>

    // SDK no longer interprets events
    // fun observe(): Flow<OpenFeatureProviderEvents>
}
```

OpenFeatureAPI would subscribe to `provider.observeStatus()` and:
- Maintain global status as aggregation of provider status
- Handle transitions (NotReady → Reconciling, etc.)
- Provide backward-compatible `statusFlow` for clients

**Implications:**
- Less breaking than Option A
- SDK still manages some lifecycle (Reconciling state)
- Providers still implement status state machine
- MultiProvider status aggregation becomes simpler

### 5.2 SDK Layer Changes

#### 5.2.1 Remove Event Translation

Current code to remove:
- `OpenFeatureAPI.handleProviderEvents` (lines 294-311)
- `OpenFeatureAPI.listenToProviderEvents()` (lines 104-109)
- `toOpenFeatureStatusError()` extension function
- Event-related job management (`observeProviderEventsJob`)

#### 5.2.2 Status Aggregation Logic

The SDK would need to decide how to handle:

1. **Provider Swap**: What happens to status during provider change?
   - Current: SDK emits `NotReady` during swap
   - New: Provider reports its own status, SDK observes

2. **Context Reconciliation**: Who emits `Reconciling`?
   - Current: SDK injects `Reconciling` before calling `onContextSet()`
   - New: Provider must emit `Reconciling` when reconciliation starts

3. **Global vs. Per-Provider Status**:
   - Current: Single global status
   - New: Each provider has status (requires named clients/domains)

#### 5.2.3 Client Interface

From `/Users/tyler.potter/projects/OpenFeature/kotlin-sdk/kotlin-sdk/src/commonMain/kotlin/dev/openfeature/kotlin/sdk/Client.kt`:

Current:
```kotlin
interface Client {
    val statusFlow: Flow<OpenFeatureStatus>  // SDK-managed status
}
```

Would need to become:
```kotlin
interface Client {
    val statusFlow: Flow<ProviderStatus>  // Direct provider status
    // or
    val providerStatusFlow: Flow<ProviderStatus>  // Provider status
    val sdkStatusFlow: Flow<SDKStatus>             // SDK orchestration status
}
```

### 5.3 Provider Implementation Changes

#### 5.3.1 Status State Machine

Each provider would implement its own state machine. Example for OfrepProvider:

```kotlin
class OfrepProvider : FeatureProvider {
    private val _status = MutableStateFlow<ProviderStatus>(ProviderStatus.NotReady)
    override val statusFlow: Flow<ProviderStatus> = _status

    override suspend fun initialize(initialContext: EvaluationContext?) {
        try {
            val result = evaluateFlags(initialContext ?: ImmutableContext())
            if (result == BulkEvaluationStatus.RATE_LIMITED) {
                _status.emit(ProviderStatus.Error(...))
            } else {
                _status.emit(ProviderStatus.Ready)
            }
        } catch (e: Exception) {
            _status.emit(ProviderStatus.Error(...))
        }
    }

    override suspend fun onContextSet(old: EvaluationContext?, new: EvaluationContext) {
        _status.emit(ProviderStatus.Reconciling)  // NEW: Provider must emit this
        try {
            evaluateFlags(new)
            _status.emit(ProviderStatus.Ready)
        } catch (e: Exception) {
            _status.emit(ProviderStatus.Error(...))
        }
    }
}
```

**New Responsibilities for Providers:**
1. Maintain `MutableStateFlow<ProviderStatus>`
2. Emit `Reconciling` when context changes
3. Emit `NotReady` → `Ready` on initialization
4. Manage error states (`Error` vs `Fatal`)
5. Emit `Stale` when cache is outdated

#### 5.3.2 NoOpProvider Changes

Currently NoOpProvider is trivial (no state, no events). Would need:

```kotlin
class NoOpProvider : FeatureProvider {
    private val _status = MutableStateFlow<ProviderStatus>(ProviderStatus.Ready)
    override val statusFlow: Flow<ProviderStatus> = _status

    override suspend fun initialize(initialContext: EvaluationContext?) {
        _status.emit(ProviderStatus.Ready)  // Immediately ready
    }

    override suspend fun onContextSet(...) {
        // No-op, stay Ready
    }
}
```

#### 5.3.3 MultiProvider Changes

MultiProvider currently implements status aggregation (lines 203-231). This would become:

```kotlin
class MultiProvider(...) : FeatureProvider {
    private val childStatuses = mutableMapOf<ChildFeatureProvider, ProviderStatus>()
    private val _status = MutableStateFlow<ProviderStatus>(ProviderStatus.NotReady)

    override val statusFlow: Flow<ProviderStatus> = _status

    override suspend fun initialize(initialContext: EvaluationContext?) {
        // Subscribe to each child provider's status
        childFeatureProviders.forEach { child ->
            child.statusFlow.onEach { childStatus ->
                childStatuses[child] = childStatus
                _status.emit(calculateAggregateStatus())
            }.launchIn(scope)
        }
        // ... initialize children
    }

    private fun calculateAggregateStatus(): ProviderStatus {
        // Precedence: Fatal > NotReady > Error > Reconciling > Stale > Ready
        return childStatuses.values.maxByOrNull { it.precedence } ?: ProviderStatus.NotReady
    }
}
```

### 5.4 Breaking Changes Summary

| Component | Current Behavior | New Behavior | Breaking? |
|-----------|------------------|--------------|-----------|
| `FeatureProvider.observe()` | Returns `Flow<OpenFeatureProviderEvents>` | Removed or returns `Flow<ProviderStatus>` | ✅ Yes |
| `OpenFeatureProviderEvents` | SDK-defined event types | Deprecated or removed | ✅ Yes |
| `Client.statusFlow` | Returns `Flow<OpenFeatureStatus>` | Returns `Flow<ProviderStatus>` | ✅ Yes |
| `OpenFeatureAPI.statusFlow` | SDK-managed global status | Provider-driven status | ⚠️ Possibly |
| Provider implementations | Optional event emission | Required status management | ✅ Yes |
| `OpenFeatureAPI.observe()` | Observe provider events | Deprecated or removed | ✅ Yes |

### 5.5 Migration Path

To avoid breaking all existing code simultaneously:

#### Phase 1: Deprecation (v0.8.0)
1. Add `FeatureProvider.statusFlow` as optional (default implementation returns converted events)
2. Deprecate `FeatureProvider.observe()` for events
3. Add adapter: `eventFlowToStatusFlow()` helper
4. Update MultiProvider to use status if available, fall back to events

#### Phase 2: Dual Support (v0.9.0)
1. SDK supports both status and event flows
2. Prefer status flow if provider implements it
3. Update documentation encouraging status-based implementations
4. Migrate contrib providers to status-based

#### Phase 3: Full Migration (v1.0.0)
1. Remove event-based system
2. Make `statusFlow` required
3. Remove `OpenFeatureProviderEvents`
4. Update all examples and documentation

---

## 6. Impact Analysis

### 6.1 Benefits of Moving Status to Providers

#### 6.1.1 Clearer Responsibility Boundaries
- Providers own their complete lifecycle
- No translation layer between provider intent and SDK status
- Easier to reason about provider state

#### 6.1.2 Simplified MultiProvider
- MultiProvider becomes simpler - just aggregate child statuses
- No need to re-interpret events
- Consistent with single-provider behavior

#### 6.1.3 Better Support for Named Clients/Domains
- Each provider can have independent status
- Aligns with future named clients feature
- Clients bound to specific providers see accurate status

#### 6.1.4 Fewer SDK Responsibilities
- SDK becomes more of a coordinator than controller
- Less logic in OpenFeatureAPI
- Fewer state transitions to test

### 6.2 Drawbacks of Moving Status to Providers

#### 6.2.1 Increased Provider Complexity
- Every provider must implement status state machine
- More boilerplate for simple providers
- Provider authors need to understand state transitions

#### 6.2.2 Inconsistent Provider Behavior
- Different providers might implement status differently
- Potential for bugs in status management
- Harder to enforce correct behavior

#### 6.2.3 Breaking Changes
- All existing providers need updates
- Community providers in kotlin-sdk-contrib break
- Migration burden on provider authors

#### 6.2.4 Loss of SDK Control
- SDK cannot inject lifecycle events (like `Reconciling`)
- Harder to ensure spec compliance
- Status behavior varies by provider

### 6.3 Alternative: Improve Current System

Instead of moving status to providers, enhance current architecture:

#### Option 1: Better Event Types
Add more granular events that map directly to status:

```kotlin
sealed class OpenFeatureProviderEvents {
    data class ProviderInitializing(...)      // NEW
    data class ProviderReady(...)
    data class ProviderReconciling(...)       // NEW (provider-emitted)
    data class ProviderContextChanged(...)    // NEW
    data class ProviderError(...)
    data class ProviderStale(...)
}
```

#### Option 2: Provider Status Hints
Let providers suggest status without owning it:

```kotlin
data class ProviderReady(
    override val eventDetails: EventDetails? = null,
    val statusHint: ProviderStatus = ProviderStatus.Ready  // NEW
)
```

SDK still manages status but providers have more input.

### 6.4 Compliance with OpenFeature Spec

From the specification analysis:

#### Events (section 5)
- **Spec Requirement 5.1.1**: Providers **MAY** define event mechanism
  - Moving to status is compatible (status is a form of events)

- **Spec Requirement 5.1.2**: Client/API handlers must run on events
  - Would need to support handlers on status changes

- **Spec Requirement 5.3.1**: `PROVIDER_READY` handlers run on successful init
  - Compatible: Providers emit `Ready` status after init

#### Providers (section 2)
- **Spec Requirement 2.4.1**: Providers **MAY** define initialization function
  - No change required

- **Section 2.4**: No requirement that SDK manages status
  - Moving status to providers is spec-compliant

**Conclusion**: Moving status to providers does **not violate** the OpenFeature specification, but requires careful handling of event handlers.

---

## 7. Recommendation

### 7.1 Recommended Approach: Hybrid Evolution

**Do NOT move status management to providers immediately.** Instead:

#### Phase 1: Enhance Current System (Low Risk)
1. Keep event-based architecture
2. Add more granular event types (e.g., `ProviderReconciling`)
3. Improve MultiProvider status aggregation
4. Document provider event best practices

**Why:**
- Minimal breaking changes
- Builds on proven architecture
- Provider authors already understand events
- Maintains SDK control over lifecycle

#### Phase 2: Investigate Named Clients/Domains (Medium Term)
1. Implement named clients feature (currently not supported)
2. Each client can observe its provider's status independently
3. This naturally separates concerns without breaking changes

**Why:**
- Named clients is on roadmap anyway
- Solves multi-provider status problem
- Aligns with spec requirements
- Provides per-provider observability

#### Phase 3: Optional Provider Status (Future)
1. Add `FeatureProvider.providerStatus` as optional property
2. Providers can expose fine-grained status for observability
3. SDK still manages global status via events
4. Advanced providers can provide richer status information

**Why:**
- Opt-in for complex providers
- Backward compatible
- SDK maintains coordination role
- Providers get flexibility

### 7.2 Reasons Against Full Migration

1. **High Migration Cost**: All providers (community + vendor) break
2. **Increased Complexity**: Simple providers become complex
3. **Inconsistent Behavior**: Status behavior varies by provider quality
4. **Loss of SDK Guarantees**: SDK cannot ensure correct lifecycle
5. **Limited Benefit**: Current system works well, events are flexible

### 7.3 When to Reconsider

Full migration to provider-owned status makes sense **only if**:

1. Named clients/domains are implemented (prerequisite)
2. Multiple provider authors request more control
3. Current event system proves insufficient
4. Breaking change window (e.g., v2.0.0)

### 7.4 Specific Action Items

**Immediate (v0.8.0)**:
- [ ] Document provider event patterns and best practices
- [ ] Add integration tests for complex event scenarios
- [ ] Improve error messages when providers emit incorrect events

**Short Term (v0.9.0)**:
- [ ] Implement named clients/domains support
- [ ] Add per-provider status observability
- [ ] Update MultiProvider to better handle child statuses

**Long Term (v1.0.0+)**:
- [ ] Evaluate community feedback on status management
- [ ] Consider optional provider status property
- [ ] Maintain backward compatibility

---

---

## 8. Community Proposal Analysis

### 8.1 Proposal 1: BaseFeatureProvider / SimpleFeatureProvider

#### Overview
Provide a base class that handles status management and event emission, with synchronized lifecycle methods to avoid race conditions.

#### 8.1.1 Kotlin Coroutine Design

Unlike Java/C# which use recursive mutexes, Kotlin would use coroutines and structured concurrency:

```kotlin
/**
 * Base implementation of FeatureProvider that manages status state machine and event emission.
 *
 * Provider authors implement the simpler "do*" methods and this base class handles:
 * - Status state machine transitions
 * - Event emission from status changes
 * - Thread-safe serialization of lifecycle methods
 * - Status/event correlation
 *
 * Advanced providers can opt-out by implementing FeatureProvider directly.
 */
abstract class BaseFeatureProvider : FeatureProvider {
    // Status management
    private val _statusFlow = MutableStateFlow<ProviderStatus>(ProviderStatus.NotReady)
    val statusFlow: Flow<ProviderStatus> = _statusFlow.asStateFlow()

    // Event emission (derived from status changes)
    private val _eventFlow = MutableSharedFlow<OpenFeatureProviderEvents>(replay = 1, extraBufferCapacity = 5)

    // Lifecycle serialization - use Mutex instead of synchronized block
    private val lifecycleMutex = Mutex()

    // Coroutine scope for provider's internal work
    private val providerScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    // Status observation with automatic event emission
    init {
        providerScope.launch {
            _statusFlow.collect { status ->
                // Translate status changes to events
                when (status) {
                    is ProviderStatus.Ready -> _eventFlow.emit(OpenFeatureProviderEvents.ProviderReady())
                    is ProviderStatus.Error -> _eventFlow.emit(
                        OpenFeatureProviderEvents.ProviderError(
                            eventDetails = OpenFeatureProviderEvents.EventDetails(
                                errorCode = status.error.errorCode(),
                                message = status.error.message
                            )
                        )
                    )
                    is ProviderStatus.Stale -> _eventFlow.emit(OpenFeatureProviderEvents.ProviderStale())
                    // NotReady, Reconciling don't emit events
                    else -> {}
                }
            }
        }
    }

    // Public FeatureProvider interface implementation

    final override suspend fun initialize(initialContext: EvaluationContext?) {
        lifecycleMutex.withLock {
            if (_statusFlow.value != ProviderStatus.NotReady) {
                // Already initialized, ignore
                return
            }

            try {
                doInitialize(initialContext)
                _statusFlow.value = ProviderStatus.Ready
            } catch (e: CancellationException) {
                throw e
            } catch (e: OpenFeatureError) {
                _statusFlow.value = ProviderStatus.Error(e)
                throw e
            } catch (e: Throwable) {
                val error = OpenFeatureError.GeneralError(e.message ?: "Initialization failed")
                _statusFlow.value = ProviderStatus.Error(error)
                throw error
            }
        }
    }

    final override suspend fun onContextSet(oldContext: EvaluationContext?, newContext: EvaluationContext) {
        lifecycleMutex.withLock {
            // Emit Reconciling status
            _statusFlow.value = ProviderStatus.Reconciling

            try {
                doOnContextSet(oldContext, newContext)
                _statusFlow.value = ProviderStatus.Ready
            } catch (e: CancellationException) {
                throw e
            } catch (e: OpenFeatureError) {
                _statusFlow.value = ProviderStatus.Error(e)
                throw e
            } catch (e: Throwable) {
                val error = OpenFeatureError.GeneralError(e.message ?: "Context reconciliation failed")
                _statusFlow.value = ProviderStatus.Error(error)
                throw error
            }
        }
    }

    final override fun shutdown() {
        // Cancel internal coroutine scope
        providerScope.cancel()
        // Delegate to implementation
        doShutdown()
    }

    final override fun observe(): Flow<OpenFeatureProviderEvents> = _eventFlow.asSharedFlow()

    // Protected methods for subclasses to implement

    /**
     * Perform provider initialization. Called once at startup.
     * Throws OpenFeatureError or other exceptions on failure.
     */
    protected abstract suspend fun doInitialize(initialContext: EvaluationContext?)

    /**
     * Handle context changes. Called when evaluation context changes.
     * Throws OpenFeatureError or other exceptions on failure.
     */
    protected abstract suspend fun doOnContextSet(oldContext: EvaluationContext?, newContext: EvaluationContext)

    /**
     * Clean up resources. Called during shutdown.
     */
    protected open fun doShutdown() {
        // Default: no-op
    }

    // Helper for providers to emit stale status
    protected fun setStale() {
        _statusFlow.value = ProviderStatus.Stale
    }

    protected fun setError(error: OpenFeatureError) {
        _statusFlow.value = ProviderStatus.Error(error)
    }

    protected fun setReady() {
        _statusFlow.value = ProviderStatus.Ready
    }

    // Evaluation methods still abstract - providers must implement
    abstract override fun getBooleanEvaluation(
        key: String,
        defaultValue: Boolean,
        context: EvaluationContext?
    ): ProviderEvaluation<Boolean>

    // ... other evaluation methods
}
```

#### 8.1.2 Simplified Provider Implementation

With BaseFeatureProvider, a simple provider becomes:

```kotlin
class SimpleFileProvider(private val filePath: String) : BaseFeatureProvider() {
    private var flags: Map<String, Any> = emptyMap()

    override val metadata = object : ProviderMetadata {
        override val name = "file-provider"
    }

    override val hooks: List<Hook<*>> = emptyList()

    override suspend fun doInitialize(initialContext: EvaluationContext?) {
        // Just load flags, BaseFeatureProvider handles status/events
        flags = loadFlagsFromFile(filePath)
    }

    override suspend fun doOnContextSet(oldContext: EvaluationContext?, newContext: EvaluationContext) {
        // Reload flags if needed
        flags = loadFlagsFromFile(filePath)
    }

    override fun getBooleanEvaluation(
        key: String,
        defaultValue: Boolean,
        context: EvaluationContext?
    ): ProviderEvaluation<Boolean> {
        val value = flags[key] as? Boolean ?: throw OpenFeatureError.FlagNotFoundError(key)
        return ProviderEvaluation(value)
    }

    // ... other evaluation methods
}
```

#### 8.1.3 Complex Provider with Manual Status Control

Providers can still manually control status when needed:

```kotlin
class PollingProvider : BaseFeatureProvider() {
    private var pollingJob: Job? = null

    override suspend fun doInitialize(initialContext: EvaluationContext?) {
        fetchFlags()
        startPolling()
    }

    private fun startPolling() {
        pollingJob = providerScope.launch {
            while (isActive) {
                delay(30.seconds)
                try {
                    val hasChanges = fetchFlags()
                    if (hasChanges) {
                        setReady() // Trigger event
                    }
                } catch (e: RateLimitException) {
                    setStale() // Provider-controlled status change
                } catch (e: Exception) {
                    setError(OpenFeatureError.GeneralError(e.message ?: "Polling failed"))
                }
            }
        }
    }

    override fun doShutdown() {
        pollingJob?.cancel()
    }
}
```

#### 8.1.4 Benefits for Kotlin

1. **Coroutine-Native**: Uses `Mutex` and `Flow` instead of synchronized blocks
2. **Structured Concurrency**: Automatic cancellation via `providerScope`
3. **Type-Safe Status**: Sealed class hierarchy for status
4. **Suspending Functions**: Natural async support with `suspend`
5. **Reduced Boilerplate**: Providers don't implement status machine

#### 8.1.5 Drawbacks

1. **Inheritance Over Composition**: Forces inheritance, limits flexibility
2. **Hidden State**: Status management is somewhat opaque to provider authors
3. **Still Breaking Change**: Requires providers to migrate
4. **Not Compatible with Existing Providers**: NoOpProvider, MultiProvider need rewrites
5. **Kotlin-Only Solution**: Doesn't help other OpenFeature SDKs

#### 8.1.6 Alternative: Helper Class Instead of Base Class

Instead of inheritance, provide a composition-based helper:

```kotlin
class ProviderStatusManager {
    private val _statusFlow = MutableStateFlow<ProviderStatus>(ProviderStatus.NotReady)
    val statusFlow: Flow<ProviderStatus> = _statusFlow.asStateFlow()

    private val _eventFlow = MutableSharedFlow<OpenFeatureProviderEvents>(...)
    val eventFlow: Flow<OpenFeatureProviderEvents> = _eventFlow

    private val lifecycleMutex = Mutex()

    suspend fun <T> withLifecycleLock(block: suspend () -> T): T {
        return lifecycleMutex.withLock(block)
    }

    suspend fun transitionToReady() {
        _statusFlow.emit(ProviderStatus.Ready)
        _eventFlow.emit(OpenFeatureProviderEvents.ProviderReady())
    }

    suspend fun transitionToError(error: OpenFeatureError) {
        _statusFlow.emit(ProviderStatus.Error(error))
        _eventFlow.emit(OpenFeatureProviderEvents.ProviderError(...))
    }

    // ... other transitions
}

// Provider uses composition
class MyProvider : FeatureProvider {
    private val statusManager = ProviderStatusManager()

    override suspend fun initialize(initialContext: EvaluationContext?) {
        statusManager.withLifecycleLock {
            try {
                // do initialization
                statusManager.transitionToReady()
            } catch (e: OpenFeatureError) {
                statusManager.transitionToError(e)
                throw e
            }
        }
    }

    override fun observe() = statusManager.eventFlow
}
```

**Verdict**: Helper class is more flexible but still requires manual coordination.

---

### 8.2 Proposal 2: Split Provider Interface Package

#### 8.2.1 Current Package Structure

The Kotlin SDK is currently a single module:

```
kotlin-sdk/
├── kotlin-sdk/             (the SDK module)
│   ├── src/
│   │   ├── commonMain/
│   │   │   └── kotlin/dev/openfeature/kotlin/sdk/
│   │   │       ├── Client.kt
│   │   │       ├── FeatureProvider.kt
│   │   │       ├── OpenFeatureAPI.kt
│   │   │       ├── ProviderEvaluation.kt
│   │   │       ├── EvaluationContext.kt
│   │   │       ├── Value.kt
│   │   │       ├── exceptions/
│   │   │       │   ├── OpenFeatureError.kt
│   │   │       │   └── ErrorCode.kt
│   │   │       └── events/
│   │   │           └── OpenFeatureProviderEvents.kt
│   │   ├── androidMain/
│   │   ├── jvmMain/
│   │   └── jsMain/
│   └── build.gradle.kts
└── sampleapp/
```

Published artifact: `dev.openfeature:kotlin-sdk:0.x.x`

#### 8.2.2 Proposed Split Structure

```
kotlin-sdk/
├── provider-api/           (NEW: minimal provider interface)
│   ├── src/
│   │   └── commonMain/kotlin/dev/openfeature/kotlin/provider/
│   │       ├── FeatureProvider.kt
│   │       ├── ProviderEvaluation.kt
│   │       ├── ProviderMetadata.kt
│   │       ├── ProviderStatus.kt          (if moving status)
│   │       ├── EvaluationContext.kt
│   │       ├── Value.kt
│   │       ├── Hook.kt
│   │       ├── exceptions/
│   │       │   ├── ProviderError.kt
│   │       │   └── ErrorCode.kt
│   │       └── events/
│   │           └── ProviderEvents.kt      (if keeping events)
│   └── build.gradle.kts
│       dependencies: NONE (or just kotlinx-coroutines-core)
│
└── kotlin-sdk/             (existing SDK, depends on provider-api)
    ├── src/
    │   └── commonMain/kotlin/dev/openfeature/kotlin/sdk/
    │       ├── OpenFeatureAPI.kt
    │       ├── OpenFeatureClient.kt
    │       ├── Client.kt
    │       ├── BaseFeatureProvider.kt     (helper for providers)
    │       └── ... (SDK-specific code)
    └── build.gradle.kts
        dependencies:
          - api(project(":provider-api"))
          - implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core")
```

#### 8.2.3 What Goes in provider-api?

**Types Providers MUST Have:**

| Type | Current Package | New Package | Reason |
|------|-----------------|-------------|--------|
| `FeatureProvider` | sdk | provider-api | Core interface |
| `ProviderEvaluation<T>` | sdk | provider-api | Return type |
| `ProviderMetadata` | sdk | provider-api | Required property |
| `EvaluationContext` | sdk | provider-api | Parameter type |
| `Value` | sdk | provider-api | Flag value type |
| `Hook<T>` | sdk | provider-api | Provider hooks |
| `HookContext<T>` | sdk | provider-api | Hook parameter |
| `TrackingEventDetails` | sdk | provider-api | Track parameter |
| `ErrorCode` | sdk.exceptions | provider.exceptions | Error classification |
| `OpenFeatureError` | sdk.exceptions | provider.exceptions | Thrown by providers |
| `OpenFeatureProviderEvents` | sdk.events | provider.events | Event emission |

**Types That Stay in SDK:**

| Type | Package | Reason |
|------|---------|--------|
| `OpenFeatureAPI` | sdk | SDK orchestration |
| `Client` | sdk | SDK client interface |
| `OpenFeatureClient` | sdk | SDK implementation |
| `OpenFeatureStatus` | sdk | SDK-managed status |
| `FlagEvaluationDetails<T>` | sdk | Client return type |
| `HookSupport` | sdk | Internal SDK logic |

#### 8.2.4 Gradle Configuration

**provider-api/build.gradle.kts:**

```kotlin
plugins {
    id("org.jetbrains.kotlin.multiplatform")
    id("com.android.library")
    id("maven-publish")
}

group = "dev.openfeature"
version = "1.0.0"  // Independent versioning!

kotlin {
    androidTarget()
    jvm()
    linuxX64()
    js { nodejs(); browser() }

    sourceSets {
        commonMain.dependencies {
            // Minimal dependencies
            api("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.7.3")
        }
    }
}

publishing {
    publications {
        create<MavenPublication>("maven") {
            groupId = "dev.openfeature"
            artifactId = "kotlin-provider-api"
            version = "1.0.0"
        }
    }
}
```

**kotlin-sdk/build.gradle.kts:**

```kotlin
dependencies {
    commonMain.dependencies {
        // Depend on provider-api
        api(project(":provider-api"))
        // OR when published:
        // api("dev.openfeature:kotlin-provider-api:1.0.0")

        implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.7.3")
        implementation("org.jetbrains.kotlinx:kotlinx-collections-immutable:0.4.0")
    }
}
```

**Provider projects (kotlin-sdk-contrib):**

```kotlin
// Option 1: Depend only on provider-api (minimal)
dependencies {
    api("dev.openfeature:kotlin-provider-api:1.0.0")
    // Provider doesn't need full SDK!
}

// Option 2: Depend on full SDK (if using SDK utilities)
dependencies {
    api("dev.openfeature:kotlin-sdk:0.8.0")
    // Transitively gets provider-api
}
```

#### 8.2.5 Benefits for Kotlin Ecosystem

1. **Smaller Dependency for Native Providers**:
   - LaunchDarkly, Flagsmith, Split, etc. can depend only on provider-api
   - Reduces JAR size, compilation time, transitive dependencies

2. **Version Independence**:
   - Provider API can have stable 1.x version
   - SDK can evolve with breaking changes (0.x → 1.0 → 2.0)
   - Providers don't break when SDK changes (as long as API is stable)

3. **Better Dependency Resolution**:
   - Android Gradle handles version conflicts better
   - Reduces "dependency hell" in large projects
   - Explicit API vs implementation separation

4. **Encourages Provider Development**:
   - Lower barrier to entry for provider authors
   - Clear contract: "implement this minimal interface"
   - Less intimidating than depending on full SDK

#### 8.2.6 Drawbacks

1. **Repository Complexity**:
   - Need to publish two artifacts
   - More complex release process
   - Need to coordinate versions

2. **Migration Burden**:
   - Existing providers must update imports
   - Breaking change: `dev.openfeature.kotlin.sdk.FeatureProvider` → `dev.openfeature.kotlin.provider.FeatureProvider`
   - Need shims/deprecation path

3. **Circular Dependency Risk**:
   - SDK depends on provider-api
   - If provider-api needs SDK types, creates circle
   - Must be very careful about boundary

4. **Kotlin Multiplatform Publishing**:
   - More complex than JVM-only (e.g., Java SDK)
   - Need to publish all platform variants
   - Gradle metadata issues

#### 8.2.7 Versioning Strategy

**Provider API Versioning (Semantic Versioning 2.0.0):**

- **Major (1.0.0 → 2.0.0)**: Breaking changes to `FeatureProvider` interface
  - Adding required methods
  - Changing method signatures
  - Removing methods

- **Minor (1.0.0 → 1.1.0)**: Backwards-compatible additions
  - Adding optional methods with defaults
  - Adding new event types
  - Adding new error codes

- **Patch (1.0.0 → 1.0.1)**: Bug fixes only
  - Documentation improvements
  - Internal implementation fixes

**SDK Versioning (Independent):**

- **0.8.0**: Current SDK version (pre-1.0)
- **1.0.0**: SDK reaches stability
- **2.0.0**: SDK breaking changes (but provider API might stay 1.x)

**Example Compatibility Matrix:**

| SDK Version | provider-api Version | Compatible? |
|-------------|---------------------|-------------|
| 0.8.0 | 0.1.0 (pre-split) | ✅ (same artifact) |
| 0.9.0 | 1.0.0 | ✅ (first split version) |
| 1.0.0 | 1.0.0 | ✅ |
| 1.5.0 | 1.0.0 | ✅ (SDK evolved, API stable) |
| 2.0.0 | 1.0.0 | ✅ (SDK breaking change, API unchanged) |
| 2.0.0 | 2.0.0 | ✅ (both major bumps) |

#### 8.2.8 Migration Path

**Phase 1: Create provider-api module (v0.9.0)**

1. Create `provider-api` module with duplicated types
2. SDK re-exports types via `typealias`:
   ```kotlin
   // In kotlin-sdk module, for backward compatibility
   package dev.openfeature.kotlin.sdk

   @Deprecated("Use dev.openfeature.kotlin.provider.FeatureProvider",
               ReplaceWith("dev.openfeature.kotlin.provider.FeatureProvider"))
   typealias FeatureProvider = dev.openfeature.kotlin.provider.FeatureProvider
   ```
3. Publish both as part of 0.9.0 release
4. Update documentation to use new package

**Phase 2: Deprecation (v0.10.0)**

1. Mark old package paths as deprecated
2. Update kotlin-sdk-contrib providers to use new imports
3. Migration guide published

**Phase 3: Remove deprecated types (v1.0.0)**

1. Remove typealiases from SDK
2. provider-api becomes stable 1.0.0
3. SDK becomes stable 1.0.0

#### 8.2.9 Comparison to Other SDKs

**Java SDK**:
- Already split into `dev.openfeature:sdk` and `dev.openfeature:javasdk`
- Provider interface in separate artifact
- Proven pattern

**Go SDK**:
- Single module (Go modules work differently)
- Not applicable

**.NET SDK**:
- Split into `OpenFeature` (abstractions) and `OpenFeature.Sdk` (implementation)
- Providers depend only on abstractions
- Similar to this proposal

**Verdict**: This pattern is **proven** in other ecosystems.

---

### 8.3 Combined Proposal Analysis

Can we do BOTH BaseFeatureProvider AND package splitting?

#### 8.3.1 Combined Architecture

```
provider-api/
├── FeatureProvider.kt         (interface)
├── BaseFeatureProvider.kt     (helper implementation)
├── ProviderStatus.kt
├── ProviderStatusManager.kt   (composition helper)
└── ... (minimal types)

kotlin-sdk/
├── OpenFeatureAPI.kt
├── Client.kt
└── ... (depends on provider-api)
```

Provider authors can choose:

1. **Implement FeatureProvider directly** (full control, more work)
2. **Extend BaseFeatureProvider** (easier, less boilerplate)
3. **Use ProviderStatusManager** (composition, flexible)

#### 8.3.2 Benefits of Combining

- **Flexibility**: Provider authors choose their level of abstraction
- **Migration Path**: Existing providers keep working, new providers get easier path
- **Separation of Concerns**: Package split solves versioning, base class solves complexity

#### 8.3.3 Drawbacks of Combining

- **More Options = More Confusion**: Which approach should provider authors use?
- **Maintenance Burden**: SDK team maintains BaseFeatureProvider + ProviderStatusManager
- **Still Breaking Change**: Package split requires migration

---

### 8.4 Updated Recommendation

Based on analysis of both proposals for Kotlin SDK specifically:

#### 8.4.1 Recommendation: **Adopt Package Splitting, Defer Base Provider**

**Phase 1 (v0.9.0): Split Provider API**

✅ **DO THIS:**
1. Create `provider-api` module with minimal provider interface
2. SDK depends on provider-api
3. Use typealias for backward compatibility
4. Independent versioning: provider-api → 1.0.0, SDK stays 0.x

**Why:**
- Solves real problem: Vendor SDKs (LaunchDarkly, Split, etc.) get smaller dependency
- Proven pattern from Java/.NET SDKs
- Enables future breaking changes to SDK without breaking providers
- Low risk: Just moving files, no behavior changes

**Phase 2 (v1.0.0): Stabilize Provider API**

✅ **DO THIS:**
1. provider-api reaches 1.0.0 (stable, semantic versioning)
2. SDK reaches 1.0.0 (stable)
3. Remove deprecated typealiases

⚠️ **CONSIDER (but don't commit yet):**
- Add `BaseFeatureProvider` as **optional helper** in provider-api
- Keep it simple: just status management, not full lifecycle
- Alternative: `ProviderStatusManager` composition helper

**Phase 3 (Post-1.0): Based on Feedback**

❌ **DON'T DO YET:**
- Full migration to provider-owned status
- Required use of BaseFeatureProvider
- Breaking changes to core FeatureProvider interface

🔍 **EVALUATE:**
- Do provider authors request BaseFeatureProvider?
- Does status management remain a pain point?
- Are there better Kotlin-specific patterns (DSL, extension functions)?

#### 8.4.2 Rationale: Why Split First, Base Provider Later?

**Package Splitting Benefits are Clear:**
- Vendors want smaller dependencies (demonstrated in Java SDK)
- Version independence is valuable
- Low implementation risk
- Doesn't commit to status architecture

**Base Provider Benefits are Uncertain:**
- Kotlin providers can already use helper classes/extension functions
- Not clear how many providers actually need this
- Inheritance feels un-idiomatic in Kotlin (composition preferred)
- Can add later without breaking changes (opt-in)

**Decision Tree:**

```
Should we split provider-api?
├─ Yes → Do it in v0.9.0
│
Should we add BaseFeatureProvider?
├─ Wait for evidence
│  ├─ If 3+ providers duplicate status logic → Add helper
│  ├─ If providers request it → Add helper
│  └─ If not needed → Don't add complexity
│
Should we move status to providers?
└─ No → Keep current event-based architecture
   └─ Reason: Works well, proven, not broken
```

#### 8.4.3 Concrete Action Plan

**v0.9.0 (Next Release):**

1. Create `provider-api` module
   - Move: FeatureProvider, ProviderEvaluation, ProviderMetadata
   - Move: EvaluationContext, Value, Hook, TrackingEventDetails
   - Move: OpenFeatureError, ErrorCode
   - Move: OpenFeatureProviderEvents
   - Add: README explaining provider-api vs SDK

2. Update `kotlin-sdk` module
   - Depend on provider-api
   - Add typealiases for backward compat
   - Update internal imports

3. Update `kotlin-sdk-contrib` providers
   - Change imports to `dev.openfeature.kotlin.provider.*`
   - Verify no behavior changes
   - Update documentation

4. Documentation
   - Migration guide for provider authors
   - Versioning policy documented
   - Example provider using new package

**v1.0.0 (Stability Release):**

1. provider-api → 1.0.0
2. kotlin-sdk → 1.0.0
3. Remove typealiases
4. Commit to semantic versioning

**Post-1.0 (As Needed):**

1. Evaluate feedback from provider authors
2. If needed, add BaseFeatureProvider as **opt-in helper**
3. Keep FeatureProvider interface minimal and stable

#### 8.4.4 Does This Change the Original Recommendation?

**Original Recommendation:** Don't move status to providers

**New Recommendation:** Don't move status to providers, BUT split provider-api

**Why Both?**

- **Package splitting** solves dependency size/versioning (real problem)
- **Status architecture** works fine as-is (not broken, don't fix)
- These are **orthogonal concerns**:
  - Can split packages without changing status ownership
  - Can add BaseFeatureProvider later if needed
  - Can keep current event system in split architecture

**Updated Position:**

| Proposal | Decision | Timeline |
|----------|----------|----------|
| Move status to providers | ❌ No | Not planned |
| BaseFeatureProvider | ⚠️ Maybe | Post-1.0 if needed |
| Package splitting | ✅ Yes | v0.9.0 |

---

## Appendix: Key File Locations

### SDK Core
- **OpenFeatureAPI**: `/Users/tyler.potter/projects/OpenFeature/kotlin-sdk/kotlin-sdk/src/commonMain/kotlin/dev/openfeature/kotlin/sdk/OpenFeatureAPI.kt`
- **FeatureProvider Interface**: `/Users/tyler.potter/projects/OpenFeature/kotlin-sdk/kotlin-sdk/src/commonMain/kotlin/dev/openfeature/kotlin/sdk/FeatureProvider.kt`
- **OpenFeatureStatus**: `/Users/tyler.potter/projects/OpenFeature/kotlin-sdk/kotlin-sdk/src/commonMain/kotlin/dev/openfeature/kotlin/sdk/OpenFeatureStatus.kt`
- **OpenFeatureProviderEvents**: `/Users/tyler.potter/projects/OpenFeature/kotlin-sdk/kotlin-sdk/src/commonMain/kotlin/dev/openfeature/kotlin/sdk/events/OpenFeatureProviderEvents.kt`
- **Client Interface**: `/Users/tyler.potter/projects/OpenFeature/kotlin-sdk/kotlin-sdk/src/commonMain/kotlin/dev/openfeature/kotlin/sdk/Client.kt`
- **OpenFeatureClient**: `/Users/tyler.potter/projects/OpenFeature/kotlin-sdk/kotlin-sdk/src/commonMain/kotlin/dev/openfeature/kotlin/sdk/OpenFeatureClient.kt`

### Provider Implementations
- **NoOpProvider**: `/Users/tyler.potter/projects/OpenFeature/kotlin-sdk/kotlin-sdk/src/commonMain/kotlin/dev/openfeature/kotlin/sdk/NoOpProvider.kt`
- **MultiProvider**: `/Users/tyler.potter/projects/OpenFeature/kotlin-sdk/kotlin-sdk/src/commonMain/kotlin/dev/openfeature/kotlin/sdk/multiprovider/MultiProvider.kt`
- **OfrepProvider**: `/Users/tyler.potter/projects/OpenFeature/kotlin-sdk-contrib/providers/ofrep/src/commonMain/kotlin/dev/openfeature/kotlin/contrib/providers/ofrep/OfrepProvider.kt`

### Tests
- **ProviderEventingTests**: `/Users/tyler.potter/projects/OpenFeature/kotlin-sdk/kotlin-sdk/src/commonTest/kotlin/dev/openfeature/kotlin/sdk/ProviderEventingTests.kt`
- **DoSomethingProvider (test helper)**: `/Users/tyler.potter/projects/OpenFeature/kotlin-sdk/kotlin-sdk/src/commonTest/kotlin/dev/openfeature/kotlin/sdk/helpers/DoSomethingProvider.kt`

### Specification
- **Events Spec**: `/Users/tyler.potter/projects/OpenFeature/spec/specification/sections/05-events.md`
- **Providers Spec**: `/Users/tyler.potter/projects/OpenFeature/spec/specification/sections/02-providers.md`

### Build Configuration
- **Settings**: `/Users/tyler.potter/projects/OpenFeature/kotlin-sdk/settings.gradle.kts`
- **SDK Build**: `/Users/tyler.potter/projects/OpenFeature/kotlin-sdk/kotlin-sdk/build.gradle.kts`
- **Ofrep Provider Build**: `/Users/tyler.potter/projects/OpenFeature/kotlin-sdk-contrib/providers/ofrep/build.gradle.kts`

### Documentation
- **Architecture Doc**: `/Users/tyler.potter/projects/OpenFeature/kotlin-sdk/ARCHITECTURE.md`
- **README**: `/Users/tyler.potter/projects/OpenFeature/kotlin-sdk/README.md`
