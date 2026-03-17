# RUM Context for Feature Flags - Feature Specification

**Version:** 1.0.0
**Status:** Draft
**Target SDKs:** Datadog Client SDKs (Mobile - Kotlin/Swift, Browser - JavaScript)

---

## 1. Overview

This specification defines a utility for creating OpenFeature evaluation contexts that include RUM (Real User Monitoring) data. The utility provides a simple, explicit API for developers to gather RUM context and pass it to the OpenFeature Client API.

### 1.1 Goals

- Provide ergonomic API for creating RUM-enriched evaluation contexts
- Enable RUM-aware targeting and analysis
- Maintain explicit developer control over context creation
- Support configurable attribute filtering via allowlist
- Gracefully handle RUM SDK unavailability

### 1.2 Design Philosophy

**Explicit over Implicit**: Rather than automatically enriching context behind the scenes, this utility gives developers a clear, intentional way to include RUM data in their evaluation context. The developer explicitly:
1. Calls the utility to create a context
2. Passes that context to OpenFeature

This approach is more transparent, easier to debug, and gives developers full control.

---

## 2. API Design

### 2.1 Core Function Signature

The API style SHOULD follow the idioms of each target language (factory function, builder, constructor, etc.).

#### Kotlin (Factory Function)

```kotlin
/**
 * Creates an EvaluationContext enriched with current RUM data.
 *
 * @param targetingKey The targeting key for flag evaluation. MAY be null or empty.
 * @param allowList Set of RUM attribute keys to include. If null, all attributes are included.
 *                  If empty set, no RUM attributes are included.
 * @param additionalAttributes User-provided attributes to merge with RUM context.
 *                             These take precedence over RUM attributes with the same key.
 * @return EvaluationContext containing RUM data merged with additionalAttributes
 */
fun OpenFeatureRumContext(
    targetingKey: String? = null,
    allowList: Set<String>? = null,
    additionalAttributes: Map<String, Value> = emptyMap()
): EvaluationContext
```

#### Swift (Factory Function)

```swift
/// Creates an EvaluationContext enriched with current RUM data.
/// - Parameters:
///   - targetingKey: The targeting key for flag evaluation. May be nil or empty.
///   - allowList: Set of RUM attribute keys to include. If nil, all attributes are included.
///   - additionalAttributes: User-provided attributes to merge with RUM context.
/// - Returns: EvaluationContext containing RUM data merged with additionalAttributes
func openFeatureRumContext(
    targetingKey: String? = nil,
    allowList: Set<String>? = nil,
    additionalAttributes: [String: Value] = [:]
) -> EvaluationContext
```

#### JavaScript/TypeScript (Factory Function)

```typescript
/**
 * Creates an EvaluationContext enriched with current RUM data.
 */
function openFeatureRumContext(options?: {
  targetingKey?: string | null;
  allowList?: Set<string> | null;
  additionalAttributes?: Record<string, Value>;
}): EvaluationContext;
```

### 2.2 Parameters

| Parameter | Type | Default | Description |
|-----------|------|---------|-------------|
| `targetingKey` | `String?` | `null` | Targeting key for flag evaluation. Null and empty strings are allowed. |
| `allowList` | `Set<String>?` | `null` | RUM attribute keys to include. `null` = all, empty = none. |
| `additionalAttributes` | `Map<String, Value>` | `{}` | User attributes merged with RUM context (takes precedence). |

### 2.3 Return Value

Returns an `EvaluationContext` containing:
- The provided `targetingKey`
- Filtered RUM attributes (based on `allowList`)
- User-provided `additionalAttributes` (overriding any conflicting RUM keys)

---

## 3. Behavior Requirements

### 3.1 Snapshot Semantics

The utility MUST return a **snapshot** of the current RUM context at call time.

- The returned context is immutable
- Subsequent RUM changes (view navigation, session changes) are NOT reflected
- Developer calls the utility again when they need fresh RUM data

### 3.2 Attribute Precedence

When `additionalAttributes` contains keys that also exist in RUM context:

```
User-provided attributes MUST take precedence over RUM attributes.
```

**Algorithm:**
```
FUNCTION createContext(targetingKey, allowList, additionalAttributes):
    result = new Map()

    // Step 1: Add filtered RUM attributes (lowest precedence)
    rumContext = getRumContext()
    FOR each (key, value) IN rumContext:
        IF allowList IS null OR key IN allowList:
            result[key] = value

    // Step 2: Override with user attributes (higher precedence)
    FOR each (key, value) IN additionalAttributes:
        result[key] = value  // Overwrites RUM values if key exists

    RETURN EvaluationContext(targetingKey, result)
```

### 3.3 Targeting Key Handling

- `targetingKey` MAY be `null` or empty string (`""`)
- If `null`, the resulting context SHOULD have an empty or null targeting key (language-dependent)
- The targeting key is NOT derived from RUM data unless explicitly provided in `additionalAttributes`

### 3.4 Allowlist Behavior

| `allowList` Value | Behavior |
|-------------------|----------|
| `null` | Include ALL available RUM attributes |
| `emptySet()` | Include NO RUM attributes (only `additionalAttributes`) |
| `setOf("a", "b")` | Include only attributes with keys "a" and "b" |

---

## 4. RUM Context Provider

### 4.1 Internal Interface

Implementations MUST have access to a RUM context provider (internal, not exposed to developers):

```kotlin
internal interface RumContextProvider {
    /**
     * Returns current RUM context as flattened map.
     * Keys MUST use dot notation (e.g., "rum.sessionId").
     * @return Current RUM context, or null if unavailable
     */
    fun getCurrentContext(): Map<String, Any?>?

    /**
     * Checks if RUM SDK is initialized and available.
     */
    fun isAvailable(): Boolean
}
```

### 4.2 Platform Implementations

Each platform SDK MUST provide an internal implementation that bridges to the Datadog RUM SDK:

- **Android**: `DatadogRumContextProvider` using `GlobalRumMonitor`
- **iOS**: `DatadogRumContextProvider` using `RUMMonitor.shared`
- **Browser**: `DatadogRumContextProvider` using `DD_RUM` global

---

## 5. Key Flattening Strategy

### 5.1 Requirement

All RUM attributes MUST be flattened using dot notation to produce a `Map<String, Value>` (not nested structures).

### 5.2 Namespace Convention

| Namespace | Examples |
|-----------|----------|
| `rum.*` | `rum.sessionId`, `rum.viewId`, `rum.viewName`, `rum.viewUrl`, `rum.applicationId` |
| `device.*` | `device.type`, `device.model`, `device.brand`, `device.architecture` |
| `os.*` | `os.name`, `os.version`, `os.versionCode` |
| `app.*` | `app.id`, `app.version`, `app.versionCode`, `app.buildType` |
| `network.*` | `network.type`, `network.carrier` |
| `user.*` | `user.id`, `user.name`, `user.email`, `user.<custom>` |
| `geo.*` | `geo.country`, `geo.region`, `geo.city` |

---

## 6. Available RUM Attributes

### 6.1 Full Attribute List

#### RUM Session
| Key | Type | Description |
|-----|------|-------------|
| `rum.sessionId` | String | Unique session identifier |
| `rum.viewId` | String | Current view identifier |
| `rum.viewName` | String | Human-readable view name |
| `rum.viewUrl` | String | View URL or route path |
| `rum.applicationId` | String | RUM application identifier |

#### Device
| Key | Type | Description |
|-----|------|-------------|
| `device.type` | String | "mobile", "tablet", "desktop", "tv", "other" |
| `device.model` | String | Device model name |
| `device.brand` | String | Device manufacturer |
| `device.architecture` | String | CPU architecture |

#### OS
| Key | Type | Description |
|-----|------|-------------|
| `os.name` | String | Operating system name |
| `os.version` | String | OS version string |
| `os.versionCode` | Integer | Numeric OS version (e.g., Android API level) |

#### App
| Key | Type | Description |
|-----|------|-------------|
| `app.id` | String | Application bundle/package identifier |
| `app.version` | String | Application version name |
| `app.versionCode` | Integer | Application version code |
| `app.buildType` | String | "debug", "release", etc. |

#### Network
| Key | Type | Description |
|-----|------|-------------|
| `network.type` | String | "wifi", "cellular", "ethernet", "offline", "unknown" |
| `network.carrier` | String | Mobile carrier name (if applicable) |

#### User
| Key | Type | Description |
|-----|------|-------------|
| `user.id` | String | User identifier (from RUM user info) |
| `user.name` | String | User display name |
| `user.email` | String | User email address |
| `user.<custom>` | Any | Custom user attributes from RUM |

#### Geo
| Key | Type | Description |
|-----|------|-------------|
| `geo.country` | String | ISO country code |
| `geo.region` | String | Region/state/province |
| `geo.city` | String | City name |

### 6.2 Recommended Default Allowlist

For common use cases, the following attributes are RECOMMENDED:

```kotlin
val RECOMMENDED_ALLOWLIST = setOf(
    // Core RUM correlation
    "rum.sessionId",
    "rum.viewId",
    "rum.viewName",
    "rum.applicationId",

    // Device targeting
    "device.type",
    "device.model",

    // OS targeting
    "os.name",
    "os.version",

    // App version targeting
    "app.version",
    "app.versionCode",
    "app.buildType",

    // Network-aware features
    "network.type",

    // User identification
    "user.id"
)
```

---

## 7. Error Handling

### 7.1 RUM SDK Unavailable

When the RUM SDK is not initialized or unavailable:

1. Log a warning message ONCE (not on every call)
2. Return an `EvaluationContext` containing only `targetingKey` and `additionalAttributes`
3. Do NOT throw an exception

**Warning Message Format:**
```
"RUM context unavailable: RUM SDK is not initialized. Context will contain only user-provided attributes."
```

### 7.2 Error Scenarios

| Scenario | Behavior | Log Level |
|----------|----------|-----------|
| RUM SDK not initialized | Log warning ONCE, return context without RUM | WARN |
| RUM SDK returns null context | Return context without RUM | DEBUG |
| Exception accessing RUM SDK | Log warning, return context without RUM | WARN |
| Invalid attribute value | Skip that attribute | DEBUG |

### 7.3 Principles

1. **Never throw** - The utility MUST NOT throw exceptions
2. **Warn once** - Unavailability warning logged only once per application lifecycle
3. **Graceful degradation** - Always return a valid `EvaluationContext`

---

## 8. Usage Examples

### 8.1 Basic Usage

```kotlin
// Create context with all RUM attributes
val context = OpenFeatureRumContext(
    targetingKey = "user-123"
)

// Pass to OpenFeature
OpenFeatureAPI.setEvaluationContext(context)

// Evaluate flags
val client = OpenFeatureAPI.getClient()
val showNewFeature = client.getBooleanValue("new-checkout", false)
```

### 8.2 With Allowlist

```kotlin
// Include only specific RUM attributes
val context = OpenFeatureRumContext(
    targetingKey = "user-123",
    allowList = setOf("rum.sessionId", "device.type", "app.version")
)

OpenFeatureAPI.setEvaluationContext(context)
```

### 8.3 With Additional Attributes

```kotlin
// Add custom attributes alongside RUM data
val context = OpenFeatureRumContext(
    targetingKey = "user-123",
    additionalAttributes = mapOf(
        "subscription" to Value.String("premium"),
        "betaUser" to Value.Boolean(true)
    )
)

OpenFeatureAPI.setEvaluationContext(context)
```

### 8.4 Override RUM Attributes

```kotlin
// RUM provides device.type = "mobile"
// User wants to override for testing
val context = OpenFeatureRumContext(
    targetingKey = "user-123",
    additionalAttributes = mapOf(
        "device.type" to Value.String("tablet")  // Overrides RUM value
    )
)

// Result: device.type = "tablet" (user-provided wins)
```

### 8.5 Refresh on View Change

```kotlin
// Developer refreshes context when view changes
override fun onViewCreated(view: View) {
    // Create fresh snapshot with current RUM state
    val context = OpenFeatureRumContext(
        targetingKey = currentUserId,
        additionalAttributes = mapOf("currentScreen" to Value.String("checkout"))
    )
    OpenFeatureAPI.setEvaluationContext(context)
}
```

### 8.6 Without RUM (Fallback)

```kotlin
// If RUM SDK not initialized, still works
val context = OpenFeatureRumContext(
    targetingKey = "user-123",
    additionalAttributes = mapOf("tier" to Value.String("free"))
)
// Context contains only targetingKey + additionalAttributes
// Warning logged once: "RUM context unavailable..."
```

---

## 9. Logging Integration

### 9.1 Automatic Flow

When the returned context is passed to `OpenFeatureAPI.setEvaluationContext()`:
1. The context (containing RUM attributes) is stored globally
2. `LoggingHook` receives this context in `HookContext.ctx` during evaluations
3. All RUM attributes appear in evaluation logs automatically

### 9.2 No Special Handling Required

Since the utility returns a standard `EvaluationContext`, all existing logging mechanisms work without modification.

---

## 10. Testing

### 10.1 Unit Tests

```
- returns context with all RUM attributes when allowList is null
- returns context with filtered attributes when allowList is specified
- returns empty RUM attributes when allowList is empty set
- additionalAttributes override RUM attributes with same key
- handles null targetingKey
- handles empty string targetingKey
- handles RUM SDK not initialized (logs warning once)
- handles RUM SDK returning null context
- handles exception in RUM SDK (does not throw)
- flattens nested RUM data correctly
```

### 10.2 Integration Tests

- Verify with actual Datadog RUM SDK on each platform
- Verify context appears correctly in flag evaluations
- Verify logging captures RUM attributes

---

## 11. Implementation Notes

### 11.1 Thread Safety

- The utility MUST be thread-safe
- RUM context retrieval MUST be callable from any thread
- The "warned once" flag SHOULD use atomic/volatile semantics

### 11.2 Performance

- `getCurrentContext()` SHOULD complete in <1ms
- Consider caching RUM context if frequently called
- Attribute filtering SHOULD use efficient set lookups

### 11.3 Value Conversion

Convert RUM SDK values to OpenFeature `Value` types:

```kotlin
private fun convertToValue(value: Any): Value = when (value) {
    is String -> Value.String(value)
    is Boolean -> Value.Boolean(value)
    is Int -> Value.Integer(value)
    is Long -> Value.Integer(value.toInt())
    is Double -> Value.Double(value)
    is Float -> Value.Double(value.toDouble())
    is List<*> -> Value.List(value.filterNotNull().map { convertToValue(it) })
    is Map<*, *> -> Value.Structure(
        value.entries
            .filter { it.key is String && it.value != null }
            .associate { (k, v) -> k as String to convertToValue(v!!) }
    )
    else -> Value.String(value.toString())
}
```

---

## 12. Cross-Platform Considerations

| Aspect | Kotlin/Android | Swift/iOS | JavaScript/Browser |
|--------|----------------|-----------|-------------------|
| Function style | Factory function | Factory function | Factory function |
| Naming | `OpenFeatureRumContext()` | `openFeatureRumContext()` | `openFeatureRumContext()` |
| Null handling | Kotlin nullability | Swift optionals | `undefined`/`null` |
| Set type | `Set<String>` | `Set<String>` | `Set<string>` |
| Map type | `Map<String, Value>` | `[String: Value]` | `Record<string, Value>` |

---

## 13. Glossary

| Term | Definition |
|------|------------|
| RUM | Real User Monitoring - observability for end-user experience |
| Evaluation Context | Data provided to feature flag evaluation for targeting |
| Targeting Key | Primary identifier for flag targeting (usually user ID) |
| Allowlist | Set of explicitly permitted attribute keys |
| Snapshot | Point-in-time capture of context (not reactive) |

---

## 14. References

- [OpenFeature Specification](https://openfeature.dev/specification/)
- [OpenFeature Evaluation Context](https://openfeature.dev/docs/reference/concepts/evaluation-context)
- [Datadog RUM SDK](https://docs.datadoghq.com/real_user_monitoring/)
- [RFC 2119 - Key Words](https://www.rfc-editor.org/rfc/rfc2119)
