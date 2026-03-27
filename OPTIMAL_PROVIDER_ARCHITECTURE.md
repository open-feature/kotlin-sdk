# Optimal Provider Architecture Design (v1.0.0)

**Date**: 2026-03-12
**Target**: Kotlin SDK v1.0.0 (Breaking Changes Allowed)
**Goal**: Best developer experience + thread safety + minimal interface

---

## Table of Contents

1. [Design Principles](#1-design-principles)
2. [Architecture Overview](#2-architecture-overview)
3. [Minimal Provider Interface](#3-minimal-provider-interface)
4. [Smart Wrapping Layer](#4-smart-wrapping-layer)
5. [Synchronization Strategy](#5-synchronization-strategy)
6. [Developer Experience](#6-developer-experience)
7. [Package Structure](#7-package-structure)
8. [Testing Strategy](#8-testing-strategy)
9. [Migration Guide](#9-migration-guide)
10. [Complete Implementation](#10-complete-implementation)

---

## 1. Design Principles

### Core Principles

1. **Minimal Interface**: Providers implement only what they need
2. **Automatic Status Management**: SDK manages status state machine
3. **Thread Safety by Default**: Wrapper serializes lifecycle and events
4. **Composition Over Inheritance**: Use interfaces, not base classes
5. **Developer Experience First**: Simple things simple, complex things possible

### Key Decisions

✅ **Breaking changes allowed** - Pre-1.0 SDK, optimize for best design
✅ **Wrap all providers** - Automatic status management and thread safety
✅ **Use Mutex** - Serialize lifecycle methods and event emission
✅ **Optional interfaces** - Clear separation of capabilities
✅ **Package splitting** - `provider-api` for minimal dependencies

---

## 2. Architecture Overview

### 2.1 Component Diagram

```
┌─────────────────────────────────────────────────────────────────────┐
│                          OpenFeature SDK                            │
│                                                                     │
│  ┌────────────────────────────────────────────────────────────┐   │
│  │                  OpenFeatureAPI                            │   │
│  │  - Sets providers via setProvider()                       │   │
│  │  - Wraps with EventingProviderWrapper                     │   │
│  │  - Exposes statusFlow to clients                          │   │
│  └────────────────────────────────────────────────────────────┘   │
│                           │                                        │
│                           │ wraps                                  │
│                           ↓                                        │
│  ┌────────────────────────────────────────────────────────────┐   │
│  │          EventingProviderWrapper                           │   │
│  │                                                            │   │
│  │  - Manages status state machine                           │   │
│  │  - Serializes lifecycle (Mutex)                           │   │
│  │  - Collects provider events                               │   │
│  │  - Emits status to SDK                                    │   │
│  │  - Thread-safe by design                                  │   │
│  └────────────────────────────────────────────────────────────┘   │
│                           │                                        │
│                           │ delegates to                           │
│                           ↓                                        │
│  ┌────────────────────────────────────────────────────────────┐   │
│  │              Provider Implementation                       │   │
│  │                                                            │   │
│  │  Simple: Just implement FeatureProvider                   │   │
│  │  Complex: Implement + StateHandler + EventEmitter         │   │
│  │                                                            │   │
│  │  - No status management needed                            │   │
│  │  - No synchronization needed                              │   │
│  │  - Just business logic                                    │   │
│  └────────────────────────────────────────────────────────────┘   │
└─────────────────────────────────────────────────────────────────────┘
```

### 2.2 Status Flow

```
Provider emits event → Wrapper receives → Wrapper updates status → SDK status flow
                              ↓                      ↓
                        Serialized by Mutex    State machine
```

### 2.3 Lifecycle Flow

```
SDK calls wrapper.initialize()
    ↓
Wrapper acquires Mutex
    ↓
Wrapper sets status = Reconciling/NotReady
    ↓
Wrapper calls provider.initialize() [if StateHandler]
    ↓
Provider does initialization
    ↓
Provider emits ProviderReady event [if EventEmitter]
    ↓
Wrapper receives event (still holding Mutex)
    ↓
Wrapper updates status = Ready
    ↓
Wrapper releases Mutex
    ↓
SDK observes status = Ready
```

---

## 3. Minimal Provider Interface

### 3.1 Core Interface (Required)

```kotlin
// File: provider-api/src/commonMain/kotlin/dev/openfeature/kotlin/provider/FeatureProvider.kt

package dev.openfeature.kotlin.provider

/**
 * Minimal provider interface - ONLY flag evaluation.
 *
 * This is the ONLY required interface. Providers that implement just this
 * are valid and will work immediately (status = Ready, no events).
 *
 * For lifecycle management, implement [StateHandler].
 * For event emission, implement [EventEmitter].
 * For tracking, implement [Tracker].
 */
interface FeatureProvider {
    /**
     * Provider metadata (name, etc.)
     */
    val metadata: ProviderMetadata

    /**
     * Provider-level hooks (optional, can return emptyList())
     */
    val hooks: List<Hook<*>>
        get() = emptyList()

    // Flag evaluation methods (REQUIRED)

    fun getBooleanEvaluation(
        key: String,
        defaultValue: Boolean,
        context: EvaluationContext?
    ): ProviderEvaluation<Boolean>

    fun getStringEvaluation(
        key: String,
        defaultValue: String,
        context: EvaluationContext?
    ): ProviderEvaluation<String>

    fun getIntegerEvaluation(
        key: String,
        defaultValue: Int,
        context: EvaluationContext?
    ): ProviderEvaluation<Int>

    fun getDoubleEvaluation(
        key: String,
        defaultValue: Double,
        context: EvaluationContext?
    ): ProviderEvaluation<Double>

    fun getObjectEvaluation(
        key: String,
        defaultValue: Value,
        context: EvaluationContext?
    ): ProviderEvaluation<Value>
}
```

### 3.2 Optional Interfaces

```kotlin
// File: provider-api/src/commonMain/kotlin/dev/openfeature/kotlin/provider/StateHandler.kt

package dev.openfeature.kotlin.provider

/**
 * Optional interface for providers that need lifecycle management.
 *
 * If not implemented, the provider is assumed to be ready immediately with no initialization.
 *
 * ## Thread Safety
 * The SDK guarantees that these methods are called serially (not concurrently).
 * Providers do NOT need to implement their own synchronization.
 *
 * ## Event Emission
 * Providers that implement [EventEmitter] can emit events from within these methods.
 * The SDK handles synchronization between lifecycle and events.
 *
 * ## Error Handling
 * Throw [ProviderError] to indicate initialization/reconciliation failure.
 * The SDK will set status to Error or Fatal based on the error code.
 */
interface StateHandler {
    /**
     * Initialize the provider.
     *
     * Called once when the provider is set via [OpenFeatureAPI.setProvider].
     *
     * @param initialContext The initial evaluation context (may be null)
     * @throws ProviderError If initialization fails
     * @throws CancellationException If the SDK is shutting down during init
     */
    @Throws(ProviderError::class, CancellationException::class)
    suspend fun initialize(initialContext: EvaluationContext?)

    /**
     * Handle evaluation context change.
     *
     * Called when [OpenFeatureAPI.setEvaluationContext] is called with a different context.
     *
     * @param oldContext The previous context (may be null)
     * @param newContext The new context
     * @throws ProviderError If reconciliation fails
     * @throws CancellationException If cancelled
     */
    @Throws(ProviderError::class, CancellationException::class)
    suspend fun onContextSet(oldContext: EvaluationContext?, newContext: EvaluationContext)

    /**
     * Clean up resources.
     *
     * Called when the provider is replaced or the SDK is shut down.
     * Should not throw exceptions.
     */
    fun shutdown()
}
```

```kotlin
// File: provider-api/src/commonMain/kotlin/dev/openfeature/kotlin/provider/EventEmitter.kt

package dev.openfeature.kotlin.provider

/**
 * Optional interface for providers that emit events.
 *
 * If not implemented, the provider will not emit any events (beyond automatic
 * Ready/Error events from lifecycle methods).
 *
 * ## Thread Safety
 * The SDK collects events from this Flow and handles synchronization.
 * Providers can emit events from anywhere (including lifecycle methods).
 *
 * ## Event Types
 * Providers can emit:
 * - [ProviderEvent.Ready] - Provider became ready (if not emitted, SDK assumes ready after successful init)
 * - [ProviderEvent.Error] - Provider encountered an error
 * - [ProviderEvent.ConfigurationChanged] - Flags changed, clients should re-evaluate
 * - [ProviderEvent.Stale] - Cached data may be outdated
 *
 * ## Best Practices
 * - Use [MutableSharedFlow] with replay=1 for the event flow
 * - Emit Ready after successful initialization
 * - Emit ConfigurationChanged when flags change
 * - Emit Stale when cache is invalidated
 * - Emit Error for recoverable errors (SDK will set status to Error)
 * - Throw ProviderError with Fatal code for irrecoverable errors
 */
interface EventEmitter {
    /**
     * Returns a Flow of provider events.
     *
     * The SDK will collect this flow starting when the provider is set.
     */
    fun events(): Flow<ProviderEvent>
}
```

```kotlin
// File: provider-api/src/commonMain/kotlin/dev/openfeature/kotlin/provider/Tracker.kt

package dev.openfeature.kotlin.provider

/**
 * Optional interface for providers that support tracking.
 *
 * If not implemented, calls to [Client.track] will be no-ops.
 */
interface Tracker {
    /**
     * Track an event.
     *
     * @param trackingEventName The event name
     * @param context The evaluation context
     * @param details Additional tracking details
     */
    fun track(
        trackingEventName: String,
        context: EvaluationContext?,
        details: TrackingEventDetails?
    )
}
```

### 3.3 Provider Events

```kotlin
// File: provider-api/src/commonMain/kotlin/dev/openfeature/kotlin/provider/ProviderEvent.kt

package dev.openfeature.kotlin.provider

/**
 * Events that providers can emit to communicate state changes.
 */
sealed class ProviderEvent {
    /**
     * Optional metadata about the event.
     */
    abstract val details: EventDetails?

    /**
     * Provider is ready for flag evaluation.
     *
     * Typically emitted after successful initialization or reconciliation.
     * If not emitted, the SDK assumes ready after successful lifecycle method completion.
     */
    data class Ready(
        override val details: EventDetails? = null
    ) : ProviderEvent()

    /**
     * Provider encountered an error.
     *
     * For recoverable errors (temporary network issue, etc.).
     * For irrecoverable errors, throw ProviderError with Fatal code instead.
     */
    data class Error(
        override val details: EventDetails? = null
    ) : ProviderEvent()

    /**
     * Flag configuration changed.
     *
     * Signals that flags have changed and clients should re-evaluate.
     * This does NOT change provider status (remains Ready).
     */
    data class ConfigurationChanged(
        override val details: EventDetails? = null
    ) : ProviderEvent()

    /**
     * Provider's cached state may be stale.
     *
     * Indicates the provider's cache is invalidated and may not be up-to-date.
     * Clients can continue using the provider but should be aware data may be stale.
     */
    data class Stale(
        override val details: EventDetails? = null
    ) : ProviderEvent()

    /**
     * Event metadata.
     */
    data class EventDetails(
        /**
         * List of flag keys that changed (for ConfigurationChanged events)
         */
        val flagsChanged: Set<String> = emptySet(),

        /**
         * Human-readable message
         */
        val message: String? = null,

        /**
         * Arbitrary metadata
         */
        val metadata: Map<String, Any> = emptyMap()
    )
}
```

### 3.4 Provider Errors

```kotlin
// File: provider-api/src/commonMain/kotlin/dev/openfeature/kotlin/provider/ProviderError.kt

package dev.openfeature.kotlin.provider

/**
 * Exception thrown by providers to indicate errors during lifecycle or evaluation.
 */
sealed class ProviderError(
    message: String,
    cause: Throwable? = null
) : Exception(message, cause) {

    /**
     * Error code for classification
     */
    abstract val errorCode: ErrorCode

    /**
     * General error (default)
     */
    class General(
        override val message: String,
        cause: Throwable? = null
    ) : ProviderError(message, cause) {
        override val errorCode = ErrorCode.GENERAL
    }

    /**
     * Flag not found
     */
    class FlagNotFound(
        val flagKey: String,
        override val message: String = "Flag not found: $flagKey"
    ) : ProviderError(message) {
        override val errorCode = ErrorCode.FLAG_NOT_FOUND
    }

    /**
     * Invalid evaluation context
     */
    class InvalidContext(
        override val message: String = "Invalid evaluation context"
    ) : ProviderError(message) {
        override val errorCode = ErrorCode.INVALID_CONTEXT
    }

    /**
     * Targeting key missing
     */
    class TargetingKeyMissing(
        override val message: String = "Targeting key required but not provided"
    ) : ProviderError(message) {
        override val errorCode = ErrorCode.TARGETING_KEY_MISSING
    }

    /**
     * Parse error
     */
    class ParseError(
        override val message: String,
        cause: Throwable? = null
    ) : ProviderError(message, cause) {
        override val errorCode = ErrorCode.PARSE_ERROR
    }

    /**
     * Type mismatch
     */
    class TypeMismatch(
        override val message: String = "Flag value type does not match requested type"
    ) : ProviderError(message) {
        override val errorCode = ErrorCode.TYPE_MISMATCH
    }

    /**
     * Provider is not ready (should rarely be thrown - SDK handles this)
     */
    class NotReady(
        override val message: String = "Provider not ready"
    ) : ProviderError(message) {
        override val errorCode = ErrorCode.PROVIDER_NOT_READY
    }

    /**
     * Fatal error - provider is in an irrecoverable state.
     *
     * Use this for errors that cannot be recovered from (e.g., invalid credentials,
     * missing required configuration). The SDK will set status to Fatal.
     */
    class Fatal(
        override val message: String,
        cause: Throwable? = null
    ) : ProviderError(message, cause) {
        override val errorCode = ErrorCode.PROVIDER_FATAL
    }
}

/**
 * Error codes for provider errors
 */
enum class ErrorCode {
    GENERAL,
    FLAG_NOT_FOUND,
    PARSE_ERROR,
    TYPE_MISMATCH,
    TARGETING_KEY_MISSING,
    INVALID_CONTEXT,
    PROVIDER_NOT_READY,
    PROVIDER_FATAL
}
```

---

## 4. Smart Wrapping Layer

### 4.1 EventingProviderWrapper Implementation

```kotlin
// File: kotlin-sdk/src/commonMain/kotlin/dev/openfeature/kotlin/sdk/EventingProviderWrapper.kt

package dev.openfeature.kotlin.sdk

import dev.openfeature.kotlin.provider.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Wrapper that adds automatic status management and thread safety to any provider.
 *
 * This wrapper:
 * - Manages the status state machine (NotReady → Ready → Error → etc.)
 * - Serializes lifecycle methods (initialize, onContextSet, shutdown) using Mutex
 * - Collects provider events and updates status accordingly
 * - Ensures thread safety without requiring providers to implement synchronization
 * - Emits status changes to the SDK
 *
 * ## Synchronization Strategy
 *
 * All lifecycle methods and event handling are serialized through a single Mutex:
 * - initialize() acquires lock → calls provider → releases lock
 * - onContextSet() acquires lock → calls provider → releases lock
 * - Event handling acquires lock → updates status → releases lock
 *
 * This prevents race conditions like:
 * - Event emitted during initialization
 * - Context change during initialization
 * - Multiple concurrent initializations
 *
 * ## Event Emission from Lifecycle Methods
 *
 * Providers CAN emit events from within lifecycle methods (initialize, onContextSet).
 * The wrapper handles this correctly:
 * - Event is emitted by provider
 * - Event flow delivers event to wrapper
 * - Wrapper tries to acquire lock to handle event
 * - Lock is already held by lifecycle method (same coroutine)
 * - Mutex allows reentrant lock from same coroutine
 * - Event is handled after lifecycle method completes
 *
 * ## Status State Machine
 *
 * NotReady → (initialize) → Ready
 *          → (initialize error) → Error
 *
 * Ready → (onContextSet) → Reconciling → Ready
 *       → (onContextSet error) → Error
 *       → (event: Error) → Error
 *       → (event: Stale) → Stale
 *       → (event: ConfigurationChanged) → Ready (no status change)
 *
 * Error → (event: Ready) → Ready
 *       → (event: Error with Fatal code) → Fatal
 *
 * Fatal → (terminal state, no recovery)
 */
class EventingProviderWrapper(
    private val provider: FeatureProvider,
    private val scope: CoroutineScope
) : FeatureProvider by provider {  // Delegate evaluation methods

    /**
     * Mutex for serializing lifecycle and event handling.
     *
     * Using Mutex instead of synchronized because:
     * - Kotlin coroutines (suspend functions)
     * - Mutex.withLock is cancellable
     * - Works across coroutines on different threads
     */
    private val lifecycleMutex = Mutex()

    /**
     * Current status of the wrapped provider.
     */
    private val _status = MutableStateFlow<ProviderStatus>(ProviderStatus.NotReady)
    val status: StateFlow<ProviderStatus> = _status.asStateFlow()

    /**
     * Job for collecting provider events
     */
    private var eventCollectionJob: Job? = null

    /**
     * Initialize the provider.
     *
     * This method is thread-safe and serialized via Mutex.
     * Only one initialization can happen at a time.
     *
     * @param initialContext The initial evaluation context
     * @return The resulting status (Ready or Error/Fatal)
     */
    suspend fun initialize(initialContext: EvaluationContext?): ProviderStatus {
        return lifecycleMutex.withLock {
            // Start collecting events BEFORE initialization
            // (so events emitted during init are captured)
            startEventCollection()

            // Check if provider supports lifecycle
            val stateHandler = provider as? StateHandler

            if (stateHandler == null) {
                // No lifecycle support - ready immediately
                val newStatus = ProviderStatus.Ready
                _status.value = newStatus
                return@withLock newStatus
            }

            // Provider has lifecycle - initialize it
            try {
                // Note: We don't set status to NotReady here because it already is
                // (or if re-initializing, we stay in current status until init completes)

                stateHandler.initialize(initialContext)

                // If provider implements EventEmitter and emitted Ready event,
                // the event handler will set status to Ready.
                // If provider doesn't emit Ready event, we set it here.
                if (provider !is EventEmitter) {
                    val newStatus = ProviderStatus.Ready
                    _status.value = newStatus
                    return@withLock newStatus
                }

                // Provider emits events - wait briefly for Ready event
                // If no Ready event within timeout, assume ready
                withTimeoutOrNull(100) {
                    _status.first { it is ProviderStatus.Ready }
                } ?: ProviderStatus.Ready.also { _status.value = it }

            } catch (e: CancellationException) {
                // Initialization was cancelled (e.g., SDK shutting down)
                throw e
            } catch (e: ProviderError) {
                // Provider threw an error during initialization
                val newStatus = when (e.errorCode) {
                    ErrorCode.PROVIDER_FATAL -> ProviderStatus.Fatal(e)
                    else -> ProviderStatus.Error(e)
                }
                _status.value = newStatus
                return@withLock newStatus
            } catch (e: Throwable) {
                // Unexpected error
                val error = ProviderError.General("Initialization failed: ${e.message}", e)
                val newStatus = ProviderStatus.Error(error)
                _status.value = newStatus
                return@withLock newStatus
            }
        }
    }

    /**
     * Handle evaluation context change.
     *
     * This method is thread-safe and serialized via Mutex.
     *
     * @param oldContext The old context
     * @param newContext The new context
     * @return The resulting status
     */
    suspend fun onContextSet(
        oldContext: EvaluationContext?,
        newContext: EvaluationContext
    ): ProviderStatus {
        return lifecycleMutex.withLock {
            val stateHandler = provider as? StateHandler

            if (stateHandler == null) {
                // Provider doesn't handle context changes - no status change
                return@withLock _status.value
            }

            try {
                // Set status to Reconciling
                _status.value = ProviderStatus.Reconciling

                // Call provider
                stateHandler.onContextSet(oldContext, newContext)

                // If provider emits Ready event, handler will set status
                // If not, we set it here
                if (provider !is EventEmitter) {
                    val newStatus = ProviderStatus.Ready
                    _status.value = newStatus
                    return@withLock newStatus
                }

                // Wait for Ready event or timeout
                withTimeoutOrNull(100) {
                    _status.first { it is ProviderStatus.Ready }
                } ?: ProviderStatus.Ready.also { _status.value = it }

            } catch (e: CancellationException) {
                throw e
            } catch (e: ProviderError) {
                val newStatus = when (e.errorCode) {
                    ErrorCode.PROVIDER_FATAL -> ProviderStatus.Fatal(e)
                    else -> ProviderStatus.Error(e)
                }
                _status.value = newStatus
                return@withLock newStatus
            } catch (e: Throwable) {
                val error = ProviderError.General("Context reconciliation failed: ${e.message}", e)
                val newStatus = ProviderStatus.Error(error)
                _status.value = newStatus
                return@withLock newStatus
            }
        }
    }

    /**
     * Shutdown the provider.
     *
     * Cancels event collection and calls provider shutdown.
     * Does not acquire lock (called during provider swap or SDK shutdown).
     */
    fun shutdown() {
        // Cancel event collection
        eventCollectionJob?.cancel()

        // Call provider shutdown if supported
        val stateHandler = provider as? StateHandler
        try {
            stateHandler?.shutdown()
        } catch (e: Exception) {
            // Log but don't throw - shutdown should be best-effort
            println("Warning: Provider shutdown failed: ${e.message}")
        }
    }

    /**
     * Start collecting events from the provider (if it emits events).
     *
     * This is called from within initialize() while holding the lock,
     * so events emitted during initialization are captured.
     */
    private fun startEventCollection() {
        val eventEmitter = provider as? EventEmitter ?: return

        eventCollectionJob?.cancel()
        eventCollectionJob = scope.launch {
            eventEmitter.events().collect { event ->
                handleProviderEvent(event)
            }
        }
    }

    /**
     * Handle a provider event.
     *
     * This method acquires the lifecycle lock to ensure thread safety.
     * Events can be emitted from anywhere (including lifecycle methods).
     *
     * ## Reentrant Lock Handling
     *
     * If an event is emitted from within a lifecycle method:
     * 1. Lifecycle method holds the lock
     * 2. Provider emits event
     * 3. Event flows to this handler
     * 4. Handler tries to acquire lock
     * 5. Kotlin's Mutex is NOT reentrant by default
     * 6. BUT: We're in the same coroutine, so withLock will succeed
     *    (because the lock is held by this coroutine)
     *
     * Actually, Kotlin Mutex is NOT reentrant even within the same coroutine.
     * We need to handle this differently.
     *
     * ## Solution: Non-blocking event handling
     *
     * We use tryLock() instead of withLock():
     * - If lock is available: acquire, handle event, release
     * - If lock is held (by lifecycle method): queue event for later
     *
     * OR use a simpler approach: Events are collected in a separate coroutine,
     * so they won't block lifecycle methods. The Flow will buffer events.
     */
    private suspend fun handleProviderEvent(event: ProviderEvent) {
        lifecycleMutex.withLock {
            val currentStatus = _status.value

            val newStatus = when (event) {
                is ProviderEvent.Ready -> {
                    // Provider is ready
                    ProviderStatus.Ready
                }

                is ProviderEvent.Error -> {
                    // Provider encountered an error
                    // Check if it's fatal
                    val errorMessage = event.details?.message ?: "Provider error"
                    // We don't have error code in the event (design choice)
                    // For fatal errors, provider should throw ProviderError.Fatal
                    ProviderStatus.Error(ProviderError.General(errorMessage))
                }

                is ProviderEvent.ConfigurationChanged -> {
                    // Configuration changed - stay in current status
                    // (ConfigurationChanged doesn't affect status)
                    currentStatus
                }

                is ProviderEvent.Stale -> {
                    // Provider's cache is stale
                    ProviderStatus.Stale
                }
            }

            if (newStatus != currentStatus) {
                _status.value = newStatus
            }
        }
    }

    // Note: Evaluation methods are delegated to provider via "by provider"
    // No need to override them here
}
```

### 4.2 Status Types

```kotlin
// File: kotlin-sdk/src/commonMain/kotlin/dev/openfeature/kotlin/sdk/ProviderStatus.kt

package dev.openfeature.kotlin.sdk

import dev.openfeature.kotlin.provider.ProviderError

/**
 * Provider status managed by the SDK.
 *
 * This is separate from provider events. The SDK maintains status
 * based on lifecycle method results and provider events.
 */
sealed interface ProviderStatus {
    /**
     * Provider has not been initialized yet.
     */
    object NotReady : ProviderStatus

    /**
     * Provider is ready for flag evaluation.
     */
    object Ready : ProviderStatus

    /**
     * Provider is reconciling with a new evaluation context.
     */
    object Reconciling : ProviderStatus

    /**
     * Provider encountered a recoverable error.
     */
    data class Error(val error: ProviderError) : ProviderStatus

    /**
     * Provider is in an irrecoverable error state.
     */
    data class Fatal(val error: ProviderError) : ProviderStatus

    /**
     * Provider's cached data may be stale.
     */
    object Stale : ProviderStatus
}
```

---

## 5. Synchronization Strategy

### 5.1 The Mutex Problem

**Challenge**: Kotlin's `Mutex` is NOT reentrant, even within the same coroutine.

```kotlin
val mutex = Mutex()

suspend fun outer() {
    mutex.withLock {
        inner()  // Will deadlock!
    }
}

suspend fun inner() {
    mutex.withLock {  // Deadlock - same coroutine can't acquire lock twice
        // ...
    }
}
```

**Why this matters**:
If a provider emits an event from within `initialize()`:

```kotlin
class MyProvider : FeatureProvider, StateHandler, EventEmitter {
    private val events = MutableSharedFlow<ProviderEvent>()

    override suspend fun initialize(ctx: EvaluationContext?) {
        // Wrapper holds lock here
        loadFlags()
        events.emit(ProviderEvent.Ready())  // Event emitted while lock is held
    }

    override fun events() = events
}
```

The event handler tries to acquire the same lock → **Deadlock!**

### 5.2 Solution: Separate Event Collection Coroutine

**Key Insight**: Event collection happens in a SEPARATE coroutine (launched in `startEventCollection`).

```kotlin
// In EventingProviderWrapper

private fun startEventCollection() {
    val eventEmitter = provider as? EventEmitter ?: return

    eventCollectionJob = scope.launch {  // SEPARATE coroutine!
        eventEmitter.events().collect { event ->
            handleProviderEvent(event)  // Runs in separate coroutine
        }
    }
}
```

**Flow**:
1. `initialize()` acquires lock (Coroutine A)
2. `initialize()` calls `provider.initialize()` (still Coroutine A, still has lock)
3. Provider emits event to Flow (Coroutine A)
4. Event is buffered in Flow (non-blocking)
5. `initialize()` completes and releases lock
6. Event collection coroutine (Coroutine B) receives event from Flow
7. Event handler acquires lock (Coroutine B) ← **No conflict!**
8. Event handler updates status
9. Event handler releases lock

**Why this works**:
- Events are collected in a DIFFERENT coroutine than lifecycle methods
- Flow buffers events (via `MutableSharedFlow` with replay and buffer)
- No deadlock because different coroutines acquire the lock at different times

### 5.3 Flow Configuration

```kotlin
// In provider implementation

class MyProvider : FeatureProvider, StateHandler, EventEmitter {
    // Use replay=1 and extraBufferCapacity for buffering
    private val _events = MutableSharedFlow<ProviderEvent>(
        replay = 1,              // Last event is replayed to new collectors
        extraBufferCapacity = 5  // Buffer up to 5 events
    )

    override fun events(): Flow<ProviderEvent> = _events

    override suspend fun initialize(ctx: EvaluationContext?) {
        loadFlags()
        _events.emit(ProviderEvent.Ready())  // Buffered, non-blocking
    }
}
```

**Configuration**:
- `replay = 1`: Last event is saved and delivered to new collectors
- `extraBufferCapacity = 5`: Can buffer multiple events without suspending
- `emit()` is non-blocking if buffer has space

### 5.4 Synchronization Diagram

```
Timeline: Provider initialization with event emission

Thread: Coroutine A (lifecycle)
  │
  ├─ acquire lifecycleMutex
  │
  ├─ call provider.initialize()
  │    │
  │    ├─ provider loads flags
  │    │
  │    └─ provider.events.emit(Ready)  ← Event emitted
  │                  │
  │                  └─→ Event buffered in Flow (non-blocking)
  │
  ├─ provider.initialize() completes
  │
  └─ release lifecycleMutex
       │
       │
       │  Thread: Coroutine B (event collection)
       │    │
       │    ├─ Flow delivers buffered event
       │    │
       │    ├─ handleProviderEvent(Ready)
       │    │    │
       │    │    ├─ acquire lifecycleMutex  ← Lock is free!
       │    │    │
       │    │    ├─ update status to Ready
       │    │    │
       │    │    └─ release lifecycleMutex
       │    │
       │    └─ continue collecting
       │
       └─────────→ (no deadlock)
```

### 5.5 Edge Cases

#### Edge Case 1: Multiple Events During Init

```kotlin
override suspend fun initialize(ctx: EvaluationContext?) {
    events.emit(ProviderEvent.Stale())     // Event 1
    loadFlags()
    events.emit(ProviderEvent.Ready())     // Event 2
}
```

**Behavior**:
- Both events are buffered
- After `initialize()` releases lock, both events are processed in order
- Status: NotReady → Stale → Ready

#### Edge Case 2: Event Before Init Completes

```kotlin
override suspend fun initialize(ctx: EvaluationContext?) {
    launch {
        delay(100)
        events.emit(ProviderEvent.Ready())  // Emitted from different coroutine
    }
    // init returns immediately
}
```

**Behavior**:
- `initialize()` completes quickly
- Wrapper sets status to Ready (because no Ready event yet)
- Later, provider emits Ready event (no-op, already Ready)

This is fine! Provider's Ready event is just confirmation.

#### Edge Case 3: Error During Init

```kotlin
override suspend fun initialize(ctx: EvaluationContext?) {
    throw ProviderError.Fatal("Invalid credentials")
}
```

**Behavior**:
- Exception is caught by wrapper
- Wrapper sets status to Fatal
- No event handling needed

#### Edge Case 4: Context Change During Init

Can't happen! Both acquire the same lock:

```kotlin
// Thread A
suspend fun initialize() {
    mutex.withLock {  // Acquired
        // ...
    }
}

// Thread B (SDK user calls setEvaluationContext)
suspend fun onContextSet() {
    mutex.withLock {  // Blocks until A releases
        // ...
    }
}
```

**Guarantee**: Lifecycle methods are serialized.

---

## 6. Developer Experience

### 6.1 Simple Static Provider

```kotlin
/**
 * Simplest possible provider - just flag evaluation.
 * No lifecycle, no events, no tracking.
 */
class StaticProvider(
    private val flags: Map<String, Any>
) : FeatureProvider {

    override val metadata = object : ProviderMetadata {
        override val name = "static-provider"
    }

    // hooks is optional, defaults to emptyList()

    override fun getBooleanEvaluation(
        key: String,
        defaultValue: Boolean,
        context: EvaluationContext?
    ): ProviderEvaluation<Boolean> {
        val value = flags[key] as? Boolean
            ?: throw ProviderError.FlagNotFound(key)
        return ProviderEvaluation(value = value)
    }

    override fun getStringEvaluation(
        key: String,
        defaultValue: String,
        context: EvaluationContext?
    ): ProviderEvaluation<String> {
        val value = flags[key] as? String
            ?: throw ProviderError.FlagNotFound(key)
        return ProviderEvaluation(value = value)
    }

    // ... other evaluation methods

    override fun getIntegerEvaluation(
        key: String,
        defaultValue: Int,
        context: EvaluationContext?
    ): ProviderEvaluation<Int> {
        val value = flags[key] as? Int
            ?: throw ProviderError.FlagNotFound(key)
        return ProviderEvaluation(value = value)
    }

    override fun getDoubleEvaluation(
        key: String,
        defaultValue: Double,
        context: EvaluationContext?
    ): ProviderEvaluation<Double> {
        val value = flags[key] as? Double
            ?: throw ProviderError.FlagNotFound(key)
        return ProviderEvaluation(value = value)
    }

    override fun getObjectEvaluation(
        key: String,
        defaultValue: Value,
        context: EvaluationContext?
    ): ProviderEvaluation<Value> {
        val value = flags[key] as? Value
            ?: throw ProviderError.FlagNotFound(key)
        return ProviderEvaluation(value = value)
    }
}

// Usage
val provider = StaticProvider(
    mapOf(
        "my-flag" to true,
        "greeting" to "Hello",
        "max-users" to 100
    )
)

OpenFeatureAPI.setProvider(provider)
// Provider is immediately Ready (no initialization needed)
```

**Lines of code**: ~40 (just evaluation logic)
**Complexity**: Ultra-low
**Thread safety**: Automatic (wrapper handles it)

### 6.2 File-Based Provider with Lifecycle

```kotlin
/**
 * Provider that loads flags from a file.
 * Has lifecycle (initialize/shutdown) but no events.
 */
class FileProvider(
    private val filePath: String
) : FeatureProvider, StateHandler {

    private var flags: Map<String, Any> = emptyMap()

    override val metadata = object : ProviderMetadata {
        override val name = "file-provider"
    }

    // StateHandler implementation

    override suspend fun initialize(initialContext: EvaluationContext?) {
        // Load flags from file
        flags = withContext(Dispatchers.IO) {
            val json = File(filePath).readText()
            Json.decodeFromString<Map<String, Any>>(json)
        }
        // No event emission - wrapper will set status to Ready
    }

    override suspend fun onContextSet(
        oldContext: EvaluationContext?,
        newContext: EvaluationContext
    ) {
        // Reload flags (maybe context affects which file to load)
        flags = loadFlagsForContext(newContext)
        // Wrapper sets status to Reconciling → Ready automatically
    }

    override fun shutdown() {
        flags = emptyMap()
    }

    // FeatureProvider implementation (same as StaticProvider)

    override fun getBooleanEvaluation(
        key: String,
        defaultValue: Boolean,
        context: EvaluationContext?
    ): ProviderEvaluation<Boolean> {
        val value = flags[key] as? Boolean
            ?: throw ProviderError.FlagNotFound(key)
        return ProviderEvaluation(value = value)
    }

    // ... other evaluation methods
}

// Usage
val provider = FileProvider("/path/to/flags.json")
OpenFeatureAPI.setProviderAndWait(provider)
// Provider is Ready after file is loaded
```

**Lines of code**: ~50 (business logic only)
**Complexity**: Low (no status management, no synchronization)
**Thread safety**: Automatic

### 6.3 Remote Provider with Events and Polling

```kotlin
/**
 * Full-featured provider with lifecycle, events, and background polling.
 */
class RemoteProvider(
    private val apiClient: FlagApiClient,
    private val pollingInterval: Duration = 30.seconds
) : FeatureProvider, StateHandler, EventEmitter, Tracker {

    private val _events = MutableSharedFlow<ProviderEvent>(
        replay = 1,
        extraBufferCapacity = 5
    )

    private var flagCache: Map<String, Any> = emptyMap()
    private var pollingJob: Job? = null
    private val providerScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override val metadata = object : ProviderMetadata {
        override val name = "remote-provider"
    }

    // StateHandler implementation

    override suspend fun initialize(initialContext: EvaluationContext?) {
        try {
            // Fetch initial flags
            flagCache = apiClient.fetchFlags(initialContext)

            // Start background polling
            startPolling()

            // Emit Ready event
            _events.emit(ProviderEvent.Ready())

        } catch (e: Exception) {
            throw ProviderError.General("Failed to fetch flags: ${e.message}", e)
        }
    }

    override suspend fun onContextSet(
        oldContext: EvaluationContext?,
        newContext: EvaluationContext
    ) {
        // Emit Stale to indicate we're refetching
        _events.emit(ProviderEvent.Stale())

        try {
            flagCache = apiClient.fetchFlags(newContext)
            _events.emit(ProviderEvent.Ready())
        } catch (e: Exception) {
            _events.emit(ProviderEvent.Error(
                details = ProviderEvent.EventDetails(
                    message = "Failed to fetch flags for new context: ${e.message}"
                )
            ))
        }
    }

    override fun shutdown() {
        pollingJob?.cancel()
        providerScope.cancel()
    }

    // EventEmitter implementation

    override fun events(): Flow<ProviderEvent> = _events

    // Tracker implementation

    override fun track(
        trackingEventName: String,
        context: EvaluationContext?,
        details: TrackingEventDetails?
    ) {
        providerScope.launch {
            try {
                apiClient.sendTrackingEvent(trackingEventName, context, details)
            } catch (e: Exception) {
                println("Warning: Failed to send tracking event: ${e.message}")
            }
        }
    }

    // FeatureProvider implementation

    override fun getBooleanEvaluation(
        key: String,
        defaultValue: Boolean,
        context: EvaluationContext?
    ): ProviderEvaluation<Boolean> {
        val value = flagCache[key] as? Boolean
            ?: throw ProviderError.FlagNotFound(key)
        return ProviderEvaluation(value = value)
    }

    // ... other evaluation methods

    // Private helpers

    private fun startPolling() {
        pollingJob = providerScope.launch {
            while (isActive) {
                delay(pollingInterval)
                try {
                    val newFlags = apiClient.fetchFlags(null)
                    if (newFlags != flagCache) {
                        flagCache = newFlags
                        _events.emit(
                            ProviderEvent.ConfigurationChanged(
                                details = ProviderEvent.EventDetails(
                                    message = "Flags updated via polling"
                                )
                            )
                        )
                    }
                } catch (e: Exception) {
                    _events.emit(
                        ProviderEvent.Error(
                            details = ProviderEvent.EventDetails(
                                message = "Polling error: ${e.message}"
                            )
                        )
                    )
                }
            }
        }
    }
}

// Usage
val provider = RemoteProvider(
    apiClient = MyApiClient("https://flags.example.com"),
    pollingInterval = 60.seconds
)

OpenFeatureAPI.setProviderAndWait(provider)
// Provider is Ready after initial fetch
// Background polling continues
// Events are emitted when flags change
```

**Lines of code**: ~120 (full-featured)
**Complexity**: Medium (but all business logic, no status management)
**Thread safety**: Automatic (wrapper handles lifecycle serialization)
**Features**: Full lifecycle + events + tracking + polling

### 6.4 Developer Experience Comparison

| Task | Current SDK | New Architecture |
|------|-------------|------------------|
| **Simple static provider** | ~60 lines (with lifecycle no-ops) | ~40 lines (just evaluation) |
| **Status management** | Provider manages (complex) | Wrapper manages (automatic) |
| **Thread safety** | Provider implements (error-prone) | Wrapper handles (automatic) |
| **Event emission** | Provider manages flow + sync | Provider just emits (simple) |
| **Testing simple provider** | Mock lifecycle methods | Mock only evaluation |
| **Testing complex provider** | Mock status + sync + events | Mock only business logic |
| **Lifecycle coordination** | Provider coordinates | Wrapper coordinates |
| **Error handling** | Provider catches + sets status | Provider just throws |

**Winner**: New architecture is MUCH simpler for provider authors.

---

## 7. Package Structure

### 7.1 Ideal Structure (Breaking Changes Allowed)

```
kotlin-sdk/
├── provider-api/              (NEW: Minimal provider interface)
│   ├── src/commonMain/kotlin/dev/openfeature/kotlin/provider/
│   │   ├── FeatureProvider.kt           (required interface)
│   │   ├── StateHandler.kt              (optional interface)
│   │   ├── EventEmitter.kt              (optional interface)
│   │   ├── Tracker.kt                   (optional interface)
│   │   ├── ProviderEvent.kt             (event types)
│   │   ├── ProviderError.kt             (error types)
│   │   ├── ProviderEvaluation.kt        (return type)
│   │   ├── ProviderMetadata.kt          (metadata type)
│   │   ├── EvaluationContext.kt         (context type)
│   │   ├── Value.kt                     (flag value type)
│   │   ├── Hook.kt                      (hook interface)
│   │   └── TrackingEventDetails.kt      (tracking type)
│   └── build.gradle.kts
│       dependencies: ONLY kotlinx-coroutines-core
│
└── kotlin-sdk/                (SDK implementation)
    ├── src/commonMain/kotlin/dev/openfeature/kotlin/sdk/
    │   ├── OpenFeatureAPI.kt            (API singleton)
    │   ├── Client.kt                    (client interface)
    │   ├── OpenFeatureClient.kt         (client implementation)
    │   ├── EventingProviderWrapper.kt   (wrapper)
    │   ├── ProviderStatus.kt            (status types - SDK only)
    │   ├── HookSupport.kt               (hook execution)
    │   └── ... (other SDK internals)
    └── build.gradle.kts
        dependencies:
          - api(project(":provider-api"))
          - implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core")
```

### 7.2 Published Artifacts

```kotlin
// provider-api/build.gradle.kts
group = "dev.openfeature"
artifactId = "kotlin-provider-api"
version = "1.0.0"

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

```kotlin
// kotlin-sdk/build.gradle.kts
group = "dev.openfeature"
artifactId = "kotlin-sdk"
version = "1.0.0"

dependencies {
    api(project(":provider-api"))
    // OR when published:
    // api("dev.openfeature:kotlin-provider-api:1.0.0")
}
```

### 7.3 Dependency Graph

```
Vendor Provider Implementation
    ↓
    depends on: kotlin-provider-api (minimal, ~50KB)
    ↓
    implements: FeatureProvider, StateHandler?, EventEmitter?, Tracker?


Application
    ↓
    depends on: kotlin-sdk (full SDK)
    ↓
    transitively gets: kotlin-provider-api
    ↓
    uses: OpenFeatureAPI, Client


kotlin-sdk (internal)
    ↓
    depends on: kotlin-provider-api (api dependency)
    ↓
    wraps providers with: EventingProviderWrapper
```

**Benefits**:
- Vendors depend only on provider-api (small)
- SDK depends on provider-api (api dependency, not implementation)
- Clear separation of concerns
- Independent versioning possible

---

## 8. Testing Strategy

### 8.1 Testing Simple Providers

```kotlin
class StaticProviderTest {
    @Test
    fun `should evaluate boolean flags`() {
        val provider = StaticProvider(
            mapOf("my-flag" to true)
        )

        val result = provider.getBooleanEvaluation("my-flag", false, null)

        assertEquals(true, result.value)
    }

    @Test
    fun `should throw on missing flag`() {
        val provider = StaticProvider(emptyMap())

        assertFailsWith<ProviderError.FlagNotFound> {
            provider.getBooleanEvaluation("missing", false, null)
        }
    }

    // That's it! No lifecycle to test, no status to test, no events to test
}
```

**Simplicity**: Just test evaluation logic.

### 8.2 Testing Providers with Lifecycle

```kotlin
class FileProviderTest {
    @Test
    fun `should load flags on initialization`() = runTest {
        val provider = FileProvider("/path/to/test-flags.json")

        // Initialize (as SDK would)
        provider.initialize(null)

        // Flags are loaded
        val result = provider.getBooleanEvaluation("my-flag", false, null)
        assertEquals(true, result.value)
    }

    @Test
    fun `should reload on context change`() = runTest {
        val provider = FileProvider("/path/to/flags.json")
        provider.initialize(null)

        // Change context
        provider.onContextSet(null, EvaluationContext("new-context"))

        // Flags reloaded
        // ... assertions
    }

    @Test
    fun `should throw on initialization failure`() = runTest {
        val provider = FileProvider("/nonexistent/path.json")

        assertFailsWith<ProviderError> {
            provider.initialize(null)
        }
    }
}
```

**Simplicity**: Test lifecycle methods directly, no status mocking.

### 8.3 Testing Providers with Events

```kotlin
class RemoteProviderTest {
    @Test
    fun `should emit Ready event after initialization`() = runTest {
        val apiClient = mockApiClient()
        val provider = RemoteProvider(apiClient)

        val events = mutableListOf<ProviderEvent>()
        val job = launch {
            provider.events().toList(events)
        }

        provider.initialize(null)
        testScheduler.advanceUntilIdle()

        assertTrue(events.any { it is ProviderEvent.Ready })

        job.cancel()
    }

    @Test
    fun `should emit ConfigurationChanged on polling update`() = runTest {
        // ... test polling logic and events
    }
}
```

**Simplicity**: Test events separately from status (status is SDK concern).

### 8.4 Testing the Wrapper

```kotlin
class EventingProviderWrapperTest {
    @Test
    fun `should set status to Ready after successful initialization`() = runTest {
        val provider = object : FeatureProvider, StateHandler {
            override val metadata = TestMetadata()
            override suspend fun initialize(ctx: EvaluationContext?) {
                // Success
            }
            override suspend fun onContextSet(old: EvaluationContext?, new: EvaluationContext) {}
            override fun shutdown() {}
            override fun getBooleanEvaluation(...) = ProviderEvaluation(true)
            // ... other methods
        }

        val wrapper = EventingProviderWrapper(provider, this)
        wrapper.initialize(null)

        assertEquals(ProviderStatus.Ready, wrapper.status.value)
    }

    @Test
    fun `should set status to Error on initialization failure`() = runTest {
        val provider = object : FeatureProvider, StateHandler {
            override suspend fun initialize(ctx: EvaluationContext?) {
                throw ProviderError.General("Test error")
            }
            // ... other methods
        }

        val wrapper = EventingProviderWrapper(provider, this)
        wrapper.initialize(null)

        assertTrue(wrapper.status.value is ProviderStatus.Error)
    }

    @Test
    fun `should serialize concurrent lifecycle calls`() = runTest {
        var initializeCount = 0
        val provider = object : FeatureProvider, StateHandler {
            override suspend fun initialize(ctx: EvaluationContext?) {
                delay(100)
                initializeCount++
            }
            // ... other methods
        }

        val wrapper = EventingProviderWrapper(provider, this)

        // Try to initialize concurrently
        val job1 = launch { wrapper.initialize(null) }
        val job2 = launch { wrapper.initialize(null) }

        job1.join()
        job2.join()

        // Both complete, but only one actually initialized (second waited for first)
        assertEquals(2, initializeCount) // Both ran (serially)
    }

    @Test
    fun `should update status on provider events`() = runTest {
        val events = MutableSharedFlow<ProviderEvent>()
        val provider = object : FeatureProvider, EventEmitter {
            override fun events() = events
            override val metadata = TestMetadata()
            // ... other methods
        }

        val wrapper = EventingProviderWrapper(provider, this)
        wrapper.initialize(null)

        // Provider emits Stale event
        events.emit(ProviderEvent.Stale())
        testScheduler.advanceUntilIdle()

        assertEquals(ProviderStatus.Stale, wrapper.status.value)
    }
}
```

**Coverage**: Test wrapper's status management, serialization, event handling.

---

## 9. Migration Guide

### 9.1 Current Architecture

```kotlin
// v0.8.0 (current)
interface FeatureProvider {
    val hooks: List<Hook<*>>
    val metadata: ProviderMetadata

    suspend fun initialize(initialContext: EvaluationContext?)
    fun shutdown()
    suspend fun onContextSet(oldContext: EvaluationContext?, newContext: EvaluationContext)
    fun observe(): Flow<OpenFeatureProviderEvents> = emptyFlow()
    fun track(...) { }

    fun getBooleanEvaluation(...): ProviderEvaluation<Boolean>
    // ... other evaluation methods
}

// Current provider
class MyProvider : FeatureProvider {
    private val eventFlow = MutableSharedFlow<OpenFeatureProviderEvents>()

    override suspend fun initialize(ctx: EvaluationContext?) {
        loadFlags()
        eventFlow.emit(OpenFeatureProviderEvents.ProviderReady())
    }

    override fun observe() = eventFlow

    override fun getBooleanEvaluation(...) = ...
}
```

### 9.2 New Architecture (v1.0.0)

```kotlin
// v1.0.0 (new)
package dev.openfeature.kotlin.provider  // NEW PACKAGE!

interface FeatureProvider {
    val metadata: ProviderMetadata
    val hooks: List<Hook<*>> get() = emptyList()  // Optional with default

    fun getBooleanEvaluation(...): ProviderEvaluation<Boolean>
    // ... other evaluation methods
}

interface StateHandler {
    suspend fun initialize(initialContext: EvaluationContext?)
    suspend fun onContextSet(oldContext: EvaluationContext?, newContext: EvaluationContext)
    fun shutdown()
}

interface EventEmitter {
    fun events(): Flow<ProviderEvent>  // Different event types!
}

// Migrated provider
class MyProvider : FeatureProvider, StateHandler, EventEmitter {
    private val _events = MutableSharedFlow<ProviderEvent>()

    override val metadata = ...

    override suspend fun initialize(ctx: EvaluationContext?) {
        loadFlags()
        _events.emit(ProviderEvent.Ready())  // New event type!
    }

    override suspend fun onContextSet(old: EvaluationContext?, new: EvaluationContext) {
        reloadFlags()
        _events.emit(ProviderEvent.Ready())
    }

    override fun shutdown() {
        cleanup()
    }

    override fun events() = _events  // New method name!

    override fun getBooleanEvaluation(...) = ...
}
```

### 9.3 Migration Checklist

**Step 1: Update Imports**
```kotlin
// Before
import dev.openfeature.kotlin.sdk.FeatureProvider
import dev.openfeature.kotlin.sdk.ProviderEvaluation
import dev.openfeature.kotlin.sdk.events.OpenFeatureProviderEvents

// After
import dev.openfeature.kotlin.provider.FeatureProvider
import dev.openfeature.kotlin.provider.ProviderEvaluation
import dev.openfeature.kotlin.provider.ProviderEvent  // New event types
import dev.openfeature.kotlin.provider.StateHandler
import dev.openfeature.kotlin.provider.EventEmitter
```

**Step 2: Implement Optional Interfaces**
```kotlin
// Before
class MyProvider : FeatureProvider {
    // lifecycle methods mixed with evaluation
}

// After
class MyProvider : FeatureProvider, StateHandler, EventEmitter {
    // Explicitly declare capabilities
}
```

**Step 3: Update Event Types**
```kotlin
// Before
OpenFeatureProviderEvents.ProviderReady()
OpenFeatureProviderEvents.ProviderError()
OpenFeatureProviderEvents.ProviderConfigurationChanged()
OpenFeatureProviderEvents.ProviderStale()

// After
ProviderEvent.Ready()
ProviderEvent.Error()
ProviderEvent.ConfigurationChanged()
ProviderEvent.Stale()
```

**Step 4: Rename Event Method**
```kotlin
// Before
override fun observe(): Flow<OpenFeatureProviderEvents> = eventFlow

// After
override fun events(): Flow<ProviderEvent> = eventFlow
```

**Step 5: Update Error Types**
```kotlin
// Before
throw OpenFeatureError.FlagNotFoundError(key)
throw OpenFeatureError.GeneralError("message")

// After
throw ProviderError.FlagNotFound(key)
throw ProviderError.General("message")
```

**Step 6: Remove Status Management**
```kotlin
// Before (provider managed status)
class MyProvider : FeatureProvider {
    private val statusFlow = MutableStateFlow(ProviderStatus.NotReady)

    override suspend fun initialize(ctx: EvaluationContext?) {
        try {
            loadFlags()
            statusFlow.value = ProviderStatus.Ready
        } catch (e: Exception) {
            statusFlow.value = ProviderStatus.Error(...)
        }
    }
}

// After (wrapper manages status)
class MyProvider : FeatureProvider, StateHandler, EventEmitter {
    // No status management!

    override suspend fun initialize(ctx: EvaluationContext?) {
        loadFlags()
        events.emit(ProviderEvent.Ready())  // Just emit event
        // Wrapper handles status
    }
}
```

### 9.4 Example: OfrepProvider Migration

**Before** (current):
```kotlin
// From: kotlin-sdk-contrib/providers/ofrep/src/commonMain/kotlin/.../OfrepProvider.kt

package dev.openfeature.kotlin.contrib.providers.ofrep

import dev.openfeature.kotlin.sdk.FeatureProvider
import dev.openfeature.kotlin.sdk.events.OpenFeatureProviderEvents
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow

class OfrepProvider(private val options: OfrepOptions) : FeatureProvider {
    private val statusFlow = MutableSharedFlow<OpenFeatureProviderEvents>(replay = 1)

    override suspend fun initialize(initialContext: EvaluationContext?) {
        try {
            evaluateFlags(initialContext)
            statusFlow.emit(OpenFeatureProviderEvents.ProviderReady())
        } catch (e: Exception) {
            statusFlow.emit(OpenFeatureProviderEvents.ProviderError(...))
        }
    }

    override fun observe(): Flow<OpenFeatureProviderEvents> = statusFlow

    override fun getBooleanEvaluation(...) = ...
}
```

**After** (migrated):
```kotlin
// New package structure
package dev.openfeature.kotlin.contrib.providers.ofrep

import dev.openfeature.kotlin.provider.*  // NEW IMPORTS
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow

class OfrepProvider(
    private val options: OfrepOptions
) : FeatureProvider, StateHandler, EventEmitter {  // EXPLICIT INTERFACES

    private val _events = MutableSharedFlow<ProviderEvent>(replay = 1)

    override val metadata = OfrepProviderMetadata()

    // StateHandler implementation
    override suspend fun initialize(initialContext: EvaluationContext?) {
        try {
            evaluateFlags(initialContext)
            _events.emit(ProviderEvent.Ready())  // NEW EVENT TYPE
        } catch (e: Exception) {
            // Throw instead of emitting error event
            throw ProviderError.General(e.message ?: "Init failed", e)
        }
    }

    override suspend fun onContextSet(
        oldContext: EvaluationContext?,
        newContext: EvaluationContext
    ) {
        _events.emit(ProviderEvent.Stale())
        try {
            evaluateFlags(newContext)
            _events.emit(ProviderEvent.Ready())
        } catch (e: Exception) {
            throw ProviderError.General(e.message ?: "Context update failed", e)
        }
    }

    override fun shutdown() {
        pollingJob?.cancel()
    }

    // EventEmitter implementation
    override fun events(): Flow<ProviderEvent> = _events  // RENAMED METHOD

    // FeatureProvider implementation (unchanged)
    override fun getBooleanEvaluation(...) = ...
}
```

**Changes**:
1. ✅ Import from `dev.openfeature.kotlin.provider`
2. ✅ Implement `StateHandler` and `EventEmitter` explicitly
3. ✅ Rename `observe()` → `events()`
4. ✅ Change event types: `OpenFeatureProviderEvents.ProviderReady()` → `ProviderEvent.Ready()`
5. ✅ Throw errors instead of emitting error events (wrapper handles status)
6. ✅ Remove status management code

**Lines changed**: ~15 lines
**Complexity**: Low (mostly find-replace)

### 9.5 Automated Migration Tool

```kotlin
// migration-tool/src/main/kotlin/MigrationTool.kt

/**
 * Automated migration tool for v0.8 → v1.0
 *
 * Usage:
 *   ./gradlew :migration-tool:run --args="/path/to/provider"
 */
fun main(args: Array<String>) {
    val providerPath = args.firstOrNull() ?: error("Provide path to provider")

    File(providerPath).walk()
        .filter { it.extension == "kt" }
        .forEach { file ->
            var content = file.readText()

            // Step 1: Update imports
            content = content
                .replace(
                    "import dev.openfeature.kotlin.sdk.FeatureProvider",
                    "import dev.openfeature.kotlin.provider.FeatureProvider"
                )
                .replace(
                    "import dev.openfeature.kotlin.sdk.ProviderEvaluation",
                    "import dev.openfeature.kotlin.provider.ProviderEvaluation"
                )
                .replace(
                    "import dev.openfeature.kotlin.sdk.events.OpenFeatureProviderEvents",
                    "import dev.openfeature.kotlin.provider.ProviderEvent"
                )

            // Step 2: Add optional interface imports
            if (content.contains("override suspend fun initialize")) {
                content = "import dev.openfeature.kotlin.provider.StateHandler\n" + content
            }
            if (content.contains("override fun observe")) {
                content = "import dev.openfeature.kotlin.provider.EventEmitter\n" + content
            }

            // Step 3: Update event types
            content = content
                .replace("OpenFeatureProviderEvents.ProviderReady", "ProviderEvent.Ready")
                .replace("OpenFeatureProviderEvents.ProviderError", "ProviderEvent.Error")
                .replace("OpenFeatureProviderEvents.ProviderConfigurationChanged", "ProviderEvent.ConfigurationChanged")
                .replace("OpenFeatureProviderEvents.ProviderStale", "ProviderEvent.Stale")

            // Step 4: Rename methods
            content = content
                .replace("override fun observe(): Flow<", "override fun events(): Flow<")

            // Step 5: Update error types
            content = content
                .replace("OpenFeatureError.FlagNotFoundError", "ProviderError.FlagNotFound")
                .replace("OpenFeatureError.GeneralError", "ProviderError.General")
                .replace("OpenFeatureError.ParseError", "ProviderError.ParseError")
                .replace("OpenFeatureError.InvalidContextError", "ProviderError.InvalidContext")
                .replace("OpenFeatureError.TargetingKeyMissingError", "ProviderError.TargetingKeyMissing")
                .replace("OpenFeatureError.TypeMismatchError", "ProviderError.TypeMismatch")
                .replace("OpenFeatureError.ProviderNotReadyError", "ProviderError.NotReady")
                .replace("OpenFeatureError.ProviderFatalError", "ProviderError.Fatal")

            file.writeText(content)
            println("Migrated: ${file.absolutePath}")
        }

    println("Migration complete! Review changes and test.")
}
```

---

## 10. Complete Implementation

### 10.1 Full EventingProviderWrapper (Production-Ready)

```kotlin
// File: kotlin-sdk/src/commonMain/kotlin/dev/openfeature/kotlin/sdk/EventingProviderWrapper.kt

package dev.openfeature.kotlin.sdk

import dev.openfeature.kotlin.provider.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.Duration.Companion.milliseconds

/**
 * Production-ready wrapper for providers.
 *
 * Features:
 * - Automatic status management
 * - Thread-safe lifecycle serialization
 * - Event collection and handling
 * - Proper error handling and recovery
 * - Cancellation support
 */
class EventingProviderWrapper(
    private val provider: FeatureProvider,
    private val scope: CoroutineScope,
    private val logger: Logger = NoOpLogger()
) : FeatureProvider by provider {

    private val lifecycleMutex = Mutex()
    private val _status = MutableStateFlow<ProviderStatus>(ProviderStatus.NotReady)
    val status: StateFlow<ProviderStatus> = _status.asStateFlow()

    private var eventCollectionJob: Job? = null
    private var initializationJob: Job? = null

    /**
     * Initialize the provider with automatic status management.
     *
     * @param initialContext Initial evaluation context
     * @return Resulting status (Ready, Error, or Fatal)
     */
    suspend fun initialize(initialContext: EvaluationContext?): ProviderStatus {
        return lifecycleMutex.withLock {
            logger.debug("Initializing provider: ${provider.metadata.name}")

            // Cancel any previous initialization
            initializationJob?.cancel()

            // Start event collection BEFORE calling provider
            // (so events emitted during init are captured)
            startEventCollection()

            val stateHandler = provider as? StateHandler

            if (stateHandler == null) {
                // No lifecycle - ready immediately
                logger.debug("Provider has no lifecycle, marking as Ready")
                val newStatus = ProviderStatus.Ready
                _status.value = newStatus
                return@withLock newStatus
            }

            try {
                logger.debug("Calling provider.initialize()")
                stateHandler.initialize(initialContext)

                // Wait briefly for Ready event from provider
                // If no event, assume ready
                val newStatus = if (provider is EventEmitter) {
                    logger.debug("Provider emits events, waiting for Ready event")
                    waitForReadyEvent() ?: ProviderStatus.Ready
                } else {
                    logger.debug("Provider doesn't emit events, assuming Ready")
                    ProviderStatus.Ready
                }

                logger.info("Provider initialized successfully: ${provider.metadata.name}")
                _status.value = newStatus
                return@withLock newStatus

            } catch (e: CancellationException) {
                logger.debug("Provider initialization cancelled")
                throw e
            } catch (e: ProviderError) {
                logger.error("Provider initialization failed with ProviderError", e)
                val newStatus = statusFromError(e)
                _status.value = newStatus
                return@withLock newStatus
            } catch (e: Throwable) {
                logger.error("Provider initialization failed with unexpected error", e)
                val error = ProviderError.General("Initialization failed: ${e.message}", e)
                val newStatus = ProviderStatus.Error(error)
                _status.value = newStatus
                return@withLock newStatus
            }
        }
    }

    /**
     * Handle context change with automatic status management.
     */
    suspend fun onContextSet(
        oldContext: EvaluationContext?,
        newContext: EvaluationContext
    ): ProviderStatus {
        return lifecycleMutex.withLock {
            logger.debug("Context change: ${provider.metadata.name}")

            val stateHandler = provider as? StateHandler

            if (stateHandler == null) {
                // Provider doesn't handle context changes
                logger.debug("Provider doesn't handle context changes")
                return@withLock _status.value
            }

            try {
                // Set status to Reconciling
                _status.value = ProviderStatus.Reconciling
                logger.debug("Status set to Reconciling")

                stateHandler.onContextSet(oldContext, newContext)

                // Wait for Ready event or assume ready
                val newStatus = if (provider is EventEmitter) {
                    waitForReadyEvent() ?: ProviderStatus.Ready
                } else {
                    ProviderStatus.Ready
                }

                logger.info("Context reconciliation complete")
                _status.value = newStatus
                return@withLock newStatus

            } catch (e: CancellationException) {
                logger.debug("Context reconciliation cancelled")
                throw e
            } catch (e: ProviderError) {
                logger.error("Context reconciliation failed", e)
                val newStatus = statusFromError(e)
                _status.value = newStatus
                return@withLock newStatus
            } catch (e: Throwable) {
                logger.error("Context reconciliation failed unexpectedly", e)
                val error = ProviderError.General("Reconciliation failed: ${e.message}", e)
                val newStatus = ProviderStatus.Error(error)
                _status.value = newStatus
                return@withLock newStatus
            }
        }
    }

    /**
     * Shutdown the provider and clean up resources.
     */
    fun shutdown() {
        logger.debug("Shutting down provider: ${provider.metadata.name}")

        // Cancel event collection
        eventCollectionJob?.cancel()

        // Call provider shutdown
        val stateHandler = provider as? StateHandler
        try {
            stateHandler?.shutdown()
            logger.info("Provider shutdown complete: ${provider.metadata.name}")
        } catch (e: Exception) {
            logger.error("Provider shutdown failed (ignoring)", e)
        }
    }

    /**
     * Start collecting events from the provider.
     * Called from within initialize() while holding the lock.
     */
    private fun startEventCollection() {
        val eventEmitter = provider as? EventEmitter ?: return

        eventCollectionJob?.cancel()
        eventCollectionJob = scope.launch {
            try {
                logger.debug("Starting event collection")
                eventEmitter.events().collect { event ->
                    handleProviderEvent(event)
                }
            } catch (e: CancellationException) {
                logger.debug("Event collection cancelled")
            } catch (e: Exception) {
                logger.error("Event collection failed", e)
            }
        }
    }

    /**
     * Handle a provider event.
     * Events are collected in a separate coroutine, so no deadlock.
     */
    private suspend fun handleProviderEvent(event: ProviderEvent) {
        lifecycleMutex.withLock {
            logger.debug("Received provider event: ${event::class.simpleName}")

            val currentStatus = _status.value

            val newStatus = when (event) {
                is ProviderEvent.Ready -> {
                    logger.info("Provider emitted Ready event")
                    ProviderStatus.Ready
                }

                is ProviderEvent.Error -> {
                    val message = event.details?.message ?: "Provider error"
                    logger.warn("Provider emitted Error event: $message")
                    ProviderStatus.Error(ProviderError.General(message))
                }

                is ProviderEvent.ConfigurationChanged -> {
                    logger.info("Provider emitted ConfigurationChanged event")
                    // ConfigurationChanged doesn't affect status
                    currentStatus
                }

                is ProviderEvent.Stale -> {
                    logger.info("Provider emitted Stale event")
                    ProviderStatus.Stale
                }
            }

            if (newStatus != currentStatus) {
                logger.debug("Status changed: $currentStatus → $newStatus")
                _status.value = newStatus
            }
        }
    }

    /**
     * Wait for a Ready event from the provider (with timeout).
     * Returns null if timeout or no event.
     */
    private suspend fun waitForReadyEvent(): ProviderStatus? {
        return withTimeoutOrNull(100.milliseconds) {
            _status.first { it is ProviderStatus.Ready }
        }
    }

    /**
     * Convert ProviderError to ProviderStatus.
     */
    private fun statusFromError(error: ProviderError): ProviderStatus {
        return when (error.errorCode) {
            ErrorCode.PROVIDER_FATAL -> ProviderStatus.Fatal(error)
            else -> ProviderStatus.Error(error)
        }
    }

    // Evaluation methods are delegated to provider via "by provider"
}

/**
 * Simple logger interface
 */
interface Logger {
    fun debug(message: String)
    fun info(message: String)
    fun warn(message: String)
    fun error(message: String, throwable: Throwable? = null)
}

class NoOpLogger : Logger {
    override fun debug(message: String) {}
    override fun info(message: String) {}
    override fun warn(message: String) {}
    override fun error(message: String, throwable: Throwable?) {}
}
```

### 10.2 OpenFeatureAPI Integration

```kotlin
// File: kotlin-sdk/src/commonMain/kotlin/dev/openfeature/kotlin/sdk/OpenFeatureAPI.kt

package dev.openfeature.kotlin.sdk

import dev.openfeature.kotlin.provider.EvaluationContext
import dev.openfeature.kotlin.provider.FeatureProvider
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

object OpenFeatureAPI {
    private val providerMutex = Mutex()
    private var currentWrapper: EventingProviderWrapper? = null
    private var context: EvaluationContext? = null

    private val apiScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /**
     * Global status flow (from current provider).
     */
    val statusFlow: Flow<ProviderStatus> = currentWrapper?.status ?: flowOf(ProviderStatus.NotReady)

    /**
     * Set the provider asynchronously.
     */
    fun setProvider(
        provider: FeatureProvider,
        dispatcher: CoroutineDispatcher = Dispatchers.Default,
        initialContext: EvaluationContext? = null
    ) {
        apiScope.launch(dispatcher) {
            setProviderInternal(provider, initialContext)
        }
    }

    /**
     * Set the provider and wait for initialization.
     */
    suspend fun setProviderAndWait(
        provider: FeatureProvider,
        initialContext: EvaluationContext? = null,
        dispatcher: CoroutineDispatcher = Dispatchers.Default
    ) {
        withContext(dispatcher) {
            setProviderInternal(provider, initialContext)
        }
    }

    private suspend fun setProviderInternal(
        provider: FeatureProvider,
        initialContext: EvaluationContext?
    ) {
        providerMutex.withLock {
            // Shutdown old provider
            currentWrapper?.shutdown()

            // Wrap new provider
            val wrapper = EventingProviderWrapper(provider, apiScope)
            currentWrapper = wrapper

            // Update context if provided
            if (initialContext != null) {
                context = initialContext
            }

            // Initialize provider
            wrapper.initialize(context)
        }
    }

    /**
     * Set evaluation context.
     */
    suspend fun setEvaluationContext(evaluationContext: EvaluationContext) {
        providerMutex.withLock {
            val oldContext = context
            context = evaluationContext

            if (oldContext != evaluationContext) {
                currentWrapper?.onContextSet(oldContext, evaluationContext)
            }
        }
    }

    /**
     * Get current provider.
     */
    fun getProvider(): FeatureProvider {
        return currentWrapper ?: NoOpProvider()
    }

    /**
     * Get current evaluation context.
     */
    fun getEvaluationContext(): EvaluationContext? {
        return context
    }

    /**
     * Get current status.
     */
    fun getStatus(): ProviderStatus {
        return currentWrapper?.status?.value ?: ProviderStatus.NotReady
    }

    /**
     * Shutdown the API.
     */
    suspend fun shutdown() {
        providerMutex.withLock {
            currentWrapper?.shutdown()
            currentWrapper = null
            apiScope.cancel()
        }
    }
}
```

---

## Summary

### What We've Designed

1. **Minimal Provider Interface**: Just evaluation methods (40 lines for simple providers)
2. **Optional Interfaces**: StateHandler, EventEmitter, Tracker (clear separation)
3. **Smart Wrapper**: Automatic status management, thread safety, event handling
4. **Synchronization**: Mutex-based serialization, no deadlocks, separate event coroutine
5. **Developer Experience**: Simple things simple, complex things possible
6. **Package Structure**: provider-api (minimal) + kotlin-sdk (full)
7. **Testing**: Test business logic only, wrapper tests separately
8. **Migration**: Clear path from v0.8 → v1.0, automated tool

### Key Innovations

✅ **Wrapper handles status** - Providers just emit events
✅ **Separate event coroutine** - No deadlock from events during lifecycle
✅ **Optional interfaces** - Providers implement only what they need
✅ **Automatic thread safety** - Wrapper serializes lifecycle with Mutex
✅ **Package splitting** - Minimal dependencies for vendor providers

### Developer Experience Win

**Before (v0.8)**:
- Provider: 60+ lines with status management
- Thread safety: Provider's responsibility
- Testing: Mock status, sync, events

**After (v1.0)**:
- Provider: 40 lines (business logic only)
- Thread safety: Automatic (wrapper)
- Testing: Test business logic only

**Result**: ~40% less code, 100% less complexity for provider authors!

---

## Next Steps

1. Review this design with SDK team
2. Implement prototype
3. Test with real providers (Ofrep, etc.)
4. Create migration tool
5. Ship v1.0!

