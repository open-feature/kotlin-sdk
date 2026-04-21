<!-- markdownlint-disable MD033 -->
<!-- x-hide-in-docs-start -->
<p align="center">
  <picture>
    <source media="(prefers-color-scheme: dark)" srcset="https://raw.githubusercontent.com/open-feature/community/0e23508c163a6a1ac8c0ced3e4bd78faafe627c7/assets/logo/horizontal/white/openfeature-horizontal-white.svg" />
    <img align="center" alt="OpenFeature Logo" src="https://raw.githubusercontent.com/open-feature/community/0e23508c163a6a1ac8c0ced3e4bd78faafe627c7/assets/logo/horizontal/black/openfeature-horizontal-black.svg" />
  </picture>
</p>

<h2 align="center">OpenFeature Kotlin SDK</h2>

<!-- x-hide-in-docs-end -->
<!-- The 'github-badges' class is used in the docs -->
<p align="center" class="github-badges">
  <a href="https://github.com/open-feature/spec/releases/tag/v0.8.0">
    <img alt="Specification" src="https://img.shields.io/static/v1?label=specification&message=v0.8.0&color=yellow&style=for-the-badge" />
  </a>
  <!-- x-release-please-start-version -->
  <a href="https://github.com/open-feature/kotlin-sdk/releases/tag/v0.7.2">
    <img alt="Release" src="https://img.shields.io/static/v1?label=release&message=v0.7.2&color=blue&style=for-the-badge" />
  </a>
  <!-- x-release-please-end -->
  <br/>
  <img alt="Status" src="https://img.shields.io/badge/lifecycle-alpha-a0c3d2.svg" />
  <a href="https://mvnrepository.com/artifact/dev.openfeature/android-sdk">
    <img alt="MavenCentral" src="https://img.shields.io/maven-central/v/dev.openfeature/android-sdk" />
  </a>
</p>
<!-- x-hide-in-docs-start -->

[OpenFeature](https://openfeature.dev) is an open specification that provides a vendor-agnostic, community-driven API for feature flagging that works with your favorite feature flag management tool.

<!-- x-hide-in-docs-end -->
## 🚀 Quick start

### Requirements

The following [Kotlin Multiplatform Targets](https://www.jetbrains.com/help/kotlin-multiplatform-dev/multiplatform-dsl-reference.html#targets) are supported:

| Supported | Platform             | Supported versions                                                             |
| --------- | -------------------- |--------------------------------------------------------------------------------|
| ✅         | Android              | SDK 21+                                                                        |
| ✅         | JVM                  | JDK 11+                                                                        |
| ✅         | Native               | Linux x64, iosSimulatorArm64, iosArm64                                         |
| ❌         | Native               | [Other native targets](https://kotlinlang.org/docs/native-target-support.html) |
| ✅         | Javascript (Node.js) |                                                                                |
| ✅         | Javascript (Browser) |                                                                                |
| ❌         | Wasm                 |                                                                                |


Note that this library adheres to the
[Static Context Paradigm](https://openfeature.dev/docs/reference/concepts/sdk-paradigms), so it is
intended to be used on the **client side** (i.e. mobile apps, web apps and desktop apps), and has
not been evaluated for use in other types of applications (e.g. server applications).

### Installation

Installation is preferred via Maven Central.

#### In Android projects

> [!IMPORTANT]
> Before version 0.6.0 the Maven artifact's id was `android-sdk`. When upgrading to 0.6.0 or higher
> please do make sure to use the new artifact id, which is `kotlin-sdk`.

<!-- x-release-please-start-version -->

```kotlin
dependencies {
    api("dev.openfeature:kotlin-sdk:0.7.2")
}
```
<!-- x-release-please-end -->

#### In multiplatform projects

<!-- x-release-please-start-version -->
```kotlin
kotlin {
    sourceSets {
        commonMain.dependencies {
            api("dev.openfeature:kotlin-sdk:0.7.2")
        }
    }
}
```
<!-- x-release-please-end -->


### Usage

> [!NOTE]
> In version 0.6.0 the base package name has changed from `dev.openfeature.sdk` to
> `dev.openfeature.kotlin.sdk`. When upgrading to 0.6.0 or higher please update your imports
> accordingly.
> 
> **Example:** `import dev.openfeature.sdk.EvaluationContext` ->
> `import dev.openfeature.kotlin.sdk.EvaluationContext`.

```kotlin
coroutineScope.launch(Dispatchers.Default) {
  // configure a provider, wait for it to complete its initialization tasks
  OpenFeatureAPI.setProviderAndWait(customProvider)
  val client = OpenFeatureAPI.getClient()

  // get a bool flag value
  client.getBooleanValue("boolFlag", defaultValue = false)
}
```

## 🌟 Features

| Status | Features                          | Description                                                                                                                        |
| ------ | --------------------------------- | ---------------------------------------------------------------------------------------------------------------------------------- |
| ✅      | [Providers](#providers)           | Integrate with a commercial, open source, or in-house feature management tool.                                                     |
| ✅      | [Targeting](#targeting)           | Contextually-aware flag evaluation using [evaluation context](https://openfeature.dev/docs/reference/concepts/evaluation-context). |
| ✅      | [Hooks](#hooks)                   | Add functionality to various stages of the flag evaluation life-cycle.                                                             |
| ✅      | [Tracking](#tracking)             | Associate user actions with feature flag evaluations.                                                                              |
| ✅      | [Logging](#logging)               | Integrate with popular logging packages.                                                                                           |
| ❌      | [Domains](#domains)               | Logically bind clients with providers.                                                                                             |
| ✅      | [Eventing](#eventing)             | React to state changes in the provider or flag management system.                                                                  |
| ✅      | [Shutdown](#shutdown)             | Gracefully clean up a provider during application shutdown.                                                                        |
| ✅      | [Extending](#extending)           | Extend OpenFeature with custom providers and hooks.                                                                                |
| ✅      | [Multi-Provider](#multi-provider) | Combine multiple providers with configurable evaluation strategies.                                                                |

<sub>Implemented: ✅ | In-progress: ⚠️ | Not implemented yet: ❌</sub>

### Providers

[Providers](https://openfeature.dev/docs/reference/concepts/provider) are an abstraction between a flag management system and the OpenFeature SDK.
Look [here](https://openfeature.dev/ecosystem?instant_search%5BrefinementList%5D%5Btype%5D%5B0%5D=Provider&instant_search%5BrefinementList%5D%5Btechnology%5D%5B0%5D=kotlin) for a complete list of available providers.
If the provider you're looking for hasn't been created yet, see the [develop a provider](#develop-a-provider) section to learn how to build it yourself.

Once you've added a provider as a dependency, it can be registered with OpenFeature like this:

```kotlin
coroutineScope.launch(Dispatchers.Default) {
    OpenFeatureAPI.setProviderAndWait(MyProvider())
}
```

After `initialize()` returns, `setProviderAndWait` waits for the provider’s `status` to move off `NotReady` and `Reconciling`. That is the provider’s contract to fulfill; the SDK does not time out. If a provider never updates `status` correctly, the call never completes. If you need a maximum wait time, wrap the call in your own `withTimeout` from `kotlinx.coroutines` (or similar) at the application level.

Asynchronous API that doesn't wait is also available. It's useful when you want to set a provider and continue with other tasks.

However, flag evaluations are only possible after the provider is `OpenFeatureStatus.Ready`. The built-in `NoOpProvider` reports `Ready` after initialization and returns default values for flags (a lightweight placeholder until you register a real provider).

```kotlin
OpenFeatureAPI.setProvider(MyProvider()) // can pass a dispatcher here
// The provider initialization happens on a coroutine launched on the IO dispatcher. 
val status = OpenFeatureAPI.getStatus()
// When status is Ready, flag evaluations can be made
```


### Targeting

Sometimes, the value of a flag must consider some dynamic criteria about the application or user, such as the user's location, IP, email address, or the server's location.
In OpenFeature, we refer to this as [targeting](https://openfeature.dev/specification/glossary#targeting).
If the flag management system you're using supports targeting, you can provide the input data using the [evaluation context](https://openfeature.dev/docs/reference/concepts/evaluation-context).

```kotlin
// set a value to the global context
val evaluationContext = ImmutableContext(
    targetingKey = session.getId,
    attributes = mutableMapOf("region" to Value.String("us-east-1")))
OpenFeatureAPI.setEvaluationContext(evaluationContext)
```

### Hooks

[Hooks](https://openfeature.dev/docs/reference/concepts/hooks) allow for custom logic to be added at well-defined points of the flag evaluation life-cycle.
Look [here](https://openfeature.dev/ecosystem/?instant_search%5BrefinementList%5D%5Btype%5D%5B0%5D=Hook&instant_search%5BrefinementList%5D%5Btechnology%5D%5B0%5D=kotlin) for a complete list of available hooks.
If the hook you're looking for hasn't been created yet, see the [develop a hook](#develop-a-hook) section to learn how to build it yourself.

Once you've added a hook as a dependency, it can be registered at the global, client, or flag invocation level.

```kotlin
// add a hook globally, to run on all evaluations
OpenFeatureAPI.addHooks(listOf(ExampleHook()))

// add a hook on this client, to run on all evaluations made by this client
val client = OpenFeatureAPI.getClient()
client.addHooks(listOf(ExampleHook()))

// add a hook for this evaluation only
val retval = client.getBooleanValue(flagKey, false,
    FlagEvaluationOptions(listOf(ExampleHook())))
```

### Tracking

The [tracking API](https://openfeature.dev/specification/sections/tracking/) allows you to use 
OpenFeature abstractions to associate user actions with feature flag evaluations.
This is essential for robust experimentation powered by feature flags. Note that, unlike methods 
that handle feature flag evaluations, calling `track(...)` may throw an `IllegalArgumentException` 
if an empty string is passed as the `trackingEventName`.

Below is an example of how we can track a "Checkout" event with some `TrackingDetails`.

```kotlin
OpenFeatureAPI.getClient().track(
  "Checkout",
  TrackingEventDetails(
    499.99,
    ImmutableStructure(
      "numberOfItems" to Value.Integer(4),
      "timeInCheckout" to Value.String("PT3M20S")
    )
  )
)
```

Tracking is optionally implemented by Providers.

### Logging

The SDK ships a `Logger` interface and a built-in `LoggingHook` that emits structured log records at each stage of the flag evaluation life-cycle.

#### Logger interface

```kotlin
interface Logger {
    fun debug(message: () -> String, attributes: () -> Map<String, Any?> = { emptyMap() }, throwable: Throwable? = null)
    fun info(message: () -> String, attributes: () -> Map<String, Any?> = { emptyMap() }, throwable: Throwable? = null)
    fun warn(message: () -> String, attributes: () -> Map<String, Any?> = { emptyMap() }, throwable: Throwable? = null)
    fun error(message: () -> String, attributes: () -> Map<String, Any?> = { emptyMap() }, throwable: Throwable? = null)
}
```

Both `message` and `attributes` are lambdas — they are evaluated lazily and only invoked if the logger decides to emit the record. This means inactive log levels incur no allocation overhead.

Platform-specific loggers are created via `LoggerFactory.getLogger(tag)`:

| Platform | Backend | Format |
|----------|---------|--------|
| Android  | `android.util.Log` | Logcat with tag; attributes appended as `key=value` pairs |
| JVM      | `System.out` (DEBUG/INFO) / `System.err` (WARN/ERROR) | `<timestamp> [LEVEL] <tag> - <message> key=value …` |
| iOS      | `NSLog` | `[LEVEL] <tag> - <message> key=value …` (NSLog adds its own timestamp) |
| JavaScript | `console` API | `[<tag>] <message>` with attributes as an expandable JS object (browser devtools / Node.js); note: `debug` uses `console.log`, not `console.debug` — browser "Verbose" filter will not capture it |
| Linux/Native | `stdout` (DEBUG/INFO) / `stderr` (WARN/ERROR) | `[LEVEL] <tag> - <message> key=value …` (no timestamp; systemd/journald provides its own) |

#### Custom Logger

To route SDK logs into your own logging framework, implement `Logger` and pass it wherever a `Logger` is accepted:

```kotlin
// SLF4J's {} placeholder calls toString() on the map, producing {key=value, ...}.
// Use entries.joinToString(" ") { "${it.key}=${it.value}" } to get key=value output instead.
class MyLogger(private val underlying: org.slf4j.Logger) : Logger {
    override fun debug(message: () -> String, attributes: () -> Map<String, Any?>, throwable: Throwable?) {
        if (!underlying.isDebugEnabled) return
        underlying.debug("{} {}", message(), attributes(), throwable)
    }

    override fun info(message: () -> String, attributes: () -> Map<String, Any?>, throwable: Throwable?) {
        if (!underlying.isInfoEnabled) return
        underlying.info("{} {}", message(), attributes(), throwable)
    }

    override fun warn(message: () -> String, attributes: () -> Map<String, Any?>, throwable: Throwable?) {
        if (!underlying.isWarnEnabled) return
        underlying.warn("{} {}", message(), attributes(), throwable)
    }

    override fun error(message: () -> String, attributes: () -> Map<String, Any?>, throwable: Throwable?) {
        if (!underlying.isErrorEnabled) return
        underlying.error("{} {}", message(), attributes(), throwable)
    }
}
```

#### LoggingHook

`LoggingHook` logs at each stage of flag evaluation. Register it like any other hook:

```kotlin
// dev.openfeature.kotlin.sdk.logging.LoggerFactory — the SDK's platform-specific factory
val logger = LoggerFactory.getLogger("FeatureFlags")

val hook = LoggingHook(
    logger = logger,
    logEvaluationContext = false,   // set true to include context attributes in logs
    beforeLogLevel = LogLevel.DEBUG,
    afterLogLevel = LogLevel.DEBUG,
    errorLogLevel = LogLevel.ERROR,
    finallyLogLevel = LogLevel.DEBUG,
)

// register globally
OpenFeatureAPI.addHooks(listOf(hook))
```

#### PII filtering

When `logEvaluationContext = true`, context attributes are included in log records. By default, attributes matching common PII field names (see `LoggingHook.DEFAULT_SENSITIVE_KEYS`) are excluded. Two optional parameters let you customize this behaviour:

| Parameter | Type | Default | Description |
|-----------|------|---------|-------------|
| `logTargetingKey` | `Boolean` | `true` | Include the targeting key in context logs. Set to `false` if targeting keys contain PII such as user IDs or email addresses. |
| `includeAttributes` | `Set<String>?` | `null` | Allowlist: only attributes with these names are logged. An empty set logs no attributes. Takes precedence over `excludeAttributes`. Attribute name matching is case-sensitive. |
| `excludeAttributes` | `Set<String>` | `DEFAULT_SENSITIVE_KEYS` | Denylist: attributes with these names are omitted. Attribute name matching is case-sensitive. |

`LoggingHook.DEFAULT_SENSITIVE_KEYS` contains: `email`, `phone`, `phoneNumber`, `ssn`, `socialSecurityNumber`, `creditCard`, `creditCardNumber`, `password`, `address`, `streetAddress`, `zipCode`, `postalCode`, `ipAddress`, `firstName`, `lastName`, `fullName`, `dateOfBirth`.

```kotlin
// Log only a specific set of safe attributes
val hook = LoggingHook(
    logger = logger,
    logEvaluationContext = true,
    logTargetingKey = false,                              // targeting key contains a user ID
    includeAttributes = setOf("region", "plan", "tier"), // allowlist — only these are logged
)

// Or extend the default denylist
val hook = LoggingHook(
    logger = logger,
    logEvaluationContext = true,
    excludeAttributes = LoggingHook.DEFAULT_SENSITIVE_KEYS + setOf("internalId", "accountNumber"),
)
```

The hook logs the following at each lifecycle stage:

| Stage | Message | Attributes |
|-------|---------|------------|
| `before` | `Flag evaluation starting` | `flag`, `type`, `defaultValue`, `provider`, `client` (if set), `context.*` (if enabled) |
| `after` | `Flag evaluation completed` | `flag`, `value`, `variant` (if set), `reason` (if set), `provider`, `context.*` (if enabled) |
| `error` | `Flag evaluation error` | `flag`, `type`, `defaultValue`, `provider`, `error` (if set), `context.*` (if enabled) |
| `finallyAfter` | `Flag evaluation finalized` | `flag`, `errorCode` (if set), `errorMessage` (if set) |

Example JVM output for a successful evaluation:

```
2026-04-15T10:00:00.123Z [DEBUG] FeatureFlags - Flag evaluation starting flag=my-flag type=BOOLEAN defaultValue=false provider=MyProvider
2026-04-15T10:00:00.124Z [DEBUG] FeatureFlags - Flag evaluation completed flag=my-flag value=true variant=on reason=TARGETING_MATCH provider=MyProvider
2026-04-15T10:00:00.124Z [DEBUG] FeatureFlags - Flag evaluation finalized flag=my-flag
```

To include evaluation context in logs for a single call, pass a hook hint:

```kotlin
client.getBooleanValue(
    "my-flag",
    false,
    FlagEvaluationOptions(
        hooks = listOf(hook),
        hookHints = mapOf(LoggingHook.HINT_LOG_EVALUATION_CONTEXT to true)
    )
)
```

### Domains

Domains allow you to logically bind clients with providers.
Support for domains is not yet available in the Kotlin SDK.

### Eventing

Events from the Provider allow the SDK to react to state changes in the provider or underlying flag management system, such as flag definition changes, provider readiness, or error conditions.
Events are optional which mean that not all Providers will emit them and it is not a must have. Some providers support additional events, such as `PROVIDER_CONFIGURATION_CHANGED`.

Please refer to the documentation of the provider you're using to see what events are supported.

Example usage:
```kotlin
viewModelScope.launch {
  OpenFeatureAPI.observe<OpenFeatureProviderEvents>().collect {
    println(">> Provider event received")
  }
}

viewModelScope.launch {
  OpenFeatureAPI.setProviderAndWait(
    MyFeatureProvider(),
    myEvaluationContext,
    Dispatchers.Default
  )
}
```

<!-- (It's only possible to observe events from the global `OpenFeatureAPI`, until multiple providers are supported) -->

### Multi-Provider

The Multi-Provider allows you to use multiple underlying providers as sources of flag data for the OpenFeature Kotlin SDK.
When a flag is being evaluated, the Multi-Provider will use each underlying provider in order to determine the final result.
Different evaluation strategies can be defined to control which providers get evaluated and which result is used.

The Multi-Provider is a useful tool for performing migrations between flag providers, or combining multiple providers into a single feature flagging interface.

See the [Multi-Provider documentation](docs/multiprovider/README.md) for more details.

### Shutdown

The OpenFeature API provides a close function to perform a cleanup of the registered provider.
This should only be called when your application is in the process of shutting down.

```kotlin
coroutineScope.launch {
    OpenFeatureAPI.shutdown()
}
```
## Sample app

In the repo there is also a sample app currently under development. 
The sample app can be used to try out development of a [Provider](#develop-a-provider), a [Hook](#develop-a-hook) 
or to validate changes to the SDK itself. 

The sample app should not be used as a reference implementation of how to use the OpenFeature SDK 
in an Android app.

## Extending

### Develop a provider

Providers are developed in dedicated projects that declare the OpenFeature SDK as a dependency. Each provider must implement the `StateManagingProvider` interface exported by the OpenFeature SDK.

The provider must keep `status` and `observe()` consistent: each time the provider transitions between `OpenFeatureStatus.NotReady`, `OpenFeatureStatus.Reconciling`, and `OpenFeatureStatus.Ready`, it must update `_status` and emit the corresponding `OpenFeatureProviderEvents` (for example, `OpenFeatureStatus.Reconciling` paired with `ProviderReconciling()`). The SDK derives `statusFlow` from `status`, and application-level handlers registered via `OpenFeatureAPI.observe()` receive the emitted events — inconsistency between the two will produce contradictory state to callers.

```kotlin
class NewProvider(override val hooks: List<Hook<*>>, override val metadata: ProviderMetadata) : StateManagingProvider {
    private val _status = MutableStateFlow(OpenFeatureStatus.NotReady)
    override val status: StateFlow<OpenFeatureStatus> = _status.asStateFlow()

    private val events = MutableSharedFlow<OpenFeatureProviderEvents>(replay = 1, extraBufferCapacity = 5)

    override fun getBooleanEvaluation(
        key: String,
        defaultValue: Boolean,
        context: EvaluationContext?
    ): ProviderEvaluation<Boolean> {
        // resolve a boolean flag value
    }

    override fun getDoubleEvaluation(
        key: String,
        defaultValue: Double,
        context: EvaluationContext?
    ): ProviderEvaluation<Double> {
        // resolve a double flag value
    }

    override fun getIntegerEvaluation(
        key: String,
        defaultValue: Int,
        context: EvaluationContext?
    ): ProviderEvaluation<Int> {
        // resolve an integer flag value
    }

    override fun getObjectEvaluation(
        key: String,
        defaultValue: Value,
        context: EvaluationContext?
    ): ProviderEvaluation<Value> {
        // resolve an object flag value
    }

    override fun getStringEvaluation(
        key: String,
        defaultValue: String,
        context: EvaluationContext?
    ): ProviderEvaluation<String> {
        // resolve a string flag value
    }

    override suspend fun initialize(initialContext: EvaluationContext?) {
        // add context-aware provider initialization

        _status.value = OpenFeatureStatus.Ready
        events.emit(OpenFeatureProviderEvents.ProviderReady())
    }

    override suspend fun onContextSet(oldContext: EvaluationContext?, newContext: EvaluationContext) {
        _status.value = OpenFeatureStatus.Reconciling
        events.emit(OpenFeatureProviderEvents.ProviderReconciling())

        // add necessary changes on context change

        _status.value = OpenFeatureStatus.Ready
        events.emit(OpenFeatureProviderEvents.ProviderReady())
    }

    override fun shutdown() {
        _status.value = OpenFeatureStatus.NotReady
        events.tryEmit(OpenFeatureProviderEvents.ProviderNotReady)
        // add necessary closure on shutdown
    }
  
    override fun track(
        trackingEventName: String,
        context: EvaluationContext?,
        details: TrackingEventDetails?
    ) {
        // Optionally track an event
    }

    override fun observe(): Flow<OpenFeatureProviderEvents> = events
}
```

#### `FeatureProvider` DEPRECATION

`FeatureProvider` is still supported although `StateManagingProvider` is preferred for new providers. It should be noted that `FeatureProvider` is a legacy behavior and will be removed in the next major version, due to its possible race condition in the presence of multi-threading.

> Built a new provider? [Let us know](https://github.com/open-feature/openfeature.dev/issues/new?assignees=&labels=provider&projects=&template=document-provider.yaml&title=%5BProvider%5D%3A+) so we can add it to the docs!

### Develop a hook

To develop a hook, you need to create a new project and include the OpenFeature SDK as a dependency.
Implement your own hook by conforming to the `Hook` interface exported by the OpenFeature SDK.

<!-- TODO: code example of hook implementation -->

> Built a new hook? [Let us know](https://github.com/open-feature/openfeature.dev/issues/new?assignees=&labels=hook&projects=&template=document-hook.yaml&title=%5BHook%5D%3A+) so we can add it to the docs!

<!-- x-hide-in-docs-start -->
## ⭐️ Support the project

- Give this repo a ⭐️!
- Follow us on social media:
  - Twitter: [@openfeature](https://twitter.com/openfeature)
  - LinkedIn: [OpenFeature](https://www.linkedin.com/company/openfeature/)
- Join us on [Slack](https://cloud-native.slack.com/archives/C0344AANLA1)
- For more, check out our [community page](https://openfeature.dev/community/)

## 📦 SNAPSHOT versions

SNAPSHOT versions are published to Maven Central's snapshot repository from the upcoming release branch.
If you need a SNAPSHOT version published, please reach out to the [maintainers](https://github.com/open-feature/kotlin-sdk/graphs/contributors) to trigger the workflow.

## 🤝 Contributing

Interested in contributing? Great, we'd love your help! To get started, take a look at the [CONTRIBUTING](CONTRIBUTING.md) guide.

### Thanks to everyone who has already contributed

<a href="https://github.com/open-feature/kotlin-sdk/graphs/contributors">
  <img src="https://contrib.rocks/image?repo=open-feature/kotlin-sdk" alt="Pictures of the folks who have contributed to the project" />
</a>

Made with [contrib.rocks](https://contrib.rocks).
<!-- x-hide-in-docs-end -->
