## MultiProvider (OpenFeature Kotlin SDK)

Combine multiple `FeatureProvider`s into a single provider with deterministic ordering, pluggable evaluation strategies, and unified status/event handling.

### Why use MultiProvider?
- **Layer providers**: fall back from an in-memory or experiment provider to a remote provider.
- **Migrate safely**: put the new provider first, retain the old as fallback.
- **Handle errors predictably**: choose whether errors should short-circuit or be skipped.

This implementation is adapted for Kotlin coroutines, flows, and OpenFeature error types.

### Quick start
```kotlin
import dev.openfeature.kotlin.sdk.OpenFeatureAPI
import dev.openfeature.kotlin.sdk.multiprovider.MultiProvider
import dev.openfeature.kotlin.sdk.multiprovider.FirstMatchStrategy
// import dev.openfeature.kotlin.sdk.multiprovider.FirstSuccessfulStrategy

// 1) Construct your providers (examples)
val experiments = MyExperimentProvider()      // e.g., local overrides/experiments
val remote = MyRemoteProvider()               // e.g., network-backed

// 2) Wrap them with MultiProvider in the desired order
val multi = MultiProvider(
    providers = listOf(experiments, remote),
    strategy = FirstMatchStrategy() // default; FirstSuccessfulStrategy() also available
)

// 3) Set the SDK provider and wait until ready
OpenFeatureAPI.setProviderAndWait()

// 4) Use the client as usual
val client = OpenFeatureAPI.getClient("my-app")
val enabled = client.getBooleanValue("new-ui", defaultValue = false)
```

### How it works (at a glance)
- The `MultiProvider` delegates each evaluation to its child providers in the order you supply.
- A pluggable `Strategy` decides which child result to return.
- Provider events are observed and converted into a single aggregate SDK status.
- Context changes are forwarded to all children concurrently.

### Strategies

- **FirstMatchStrategy (default)**
  - Returns the first child result that is not "flag not found".
  - If a child returns an error other than `FLAG_NOT_FOUND`, that error is returned immediately.
  - If all children report `FLAG_NOT_FOUND`, the default value is returned with reason `DEFAULT`.

- **FirstSuccessfulStrategy**
  - Skips over errors from children and continues to the next provider.
  - Returns the first successful evaluation (no error code).
  - If no provider succeeds, the default value is returned with `FLAG_NOT_FOUND`.

Pick the strategy that best matches your failure-policy:
- Prefer early, explicit error surfacing: use `FirstMatchStrategy`.
- Prefer resilience and best-effort success: use `FirstSuccessfulStrategy`.

### Evaluation order matters
Children are evaluated in the order provided. Put the most authoritative or fastest provider first. For example, place a small in-memory override provider before a remote provider to reduce latency.

### Events and status aggregation
`MultiProvider` listens to child provider events and emits a single, aggregate status via `OpenFeatureAPI.statusFlow`. The highest-precedence status among children wins:

1. Fatal
2. NotReady
3. Error
4. Reconciling / Stale
5. Ready

`ProviderConfigurationChanged` is re-emitted as-is. When the aggregate status changes due to a child event, the original triggering event is also emitted.

### Context propagation
When the evaluation context changes, `MultiProvider` calls `onContextSet` on all child providers concurrently. Aggregate status transitions to Reconciling and then back to Ready (or Error) in line with SDK behavior.

### Provider metadata
`MultiProvider.metadata` exposes:
- `name = "multiprovider"`
- `originalMetadata`: a map of child-name → child `ProviderMetadata`

Child names are derived from each provider’s `metadata.name`. If duplicates occur, stable suffixes are applied (e.g., `myProvider_1`, `myProvider_2`).

Example: inspect provider metadata
```kotlin
val meta = OpenFeatureAPI.getProviderMetadata()
println(meta?.name) // "multiprovider"
println(meta?.originalMetadata) // map of child names to their metadata
```

### Shutdown behavior
`shutdown()` is invoked on all children. If any child fails to shut down, an aggregated error is thrown that includes all individual failures. Resources should be released in child providers even if peers fail.

### Custom strategies
You can provide your own composition policy by implementing `MultiProvider.Strategy`:
```kotlin
import dev.openfeature.kotlin.sdk.*
import dev.openfeature.kotlin.sdk.multiprovider.MultiProvider

class MyStrategy : MultiProvider.Strategy {
    override fun <T> evaluate(
        providers: List<FeatureProvider>,
        key: String,
        defaultValue: T,
        evaluationContext: EvaluationContext?,
        flagEval: FeatureProvider.(String, T, EvaluationContext?) -> ProviderEvaluation<T>
    ): ProviderEvaluation<T> {
        // Example: try all, prefer the highest integer value (demo only)
        var best: ProviderEvaluation<T>? = null
        for (p in providers) {
            val e = p.flagEval(key, defaultValue, evaluationContext)
            // ... decide whether to keep e as best ...
            best = best ?: e
        }
        return best ?: ProviderEvaluation(defaultValue)
    }
}

val multi = MultiProvider(listOf(experiments, remote), strategy = MyStrategy())
```

### Notes and limitations
- Hooks on `MultiProvider` are currently not applied.
- Ensure each child’s `metadata.name` is set for clearer diagnostics in `originalMetadata`.



