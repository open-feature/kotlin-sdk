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
  <a href="https://github.com/open-feature/spec/tree/v0.6.0">
    <img alt="Specification" src="https://img.shields.io/static/v1?label=specification&message=v0.6.0&color=yellow&style=for-the-badge" />
  </a>

  <a href="https://github.com/open-feature/kotlin-sdk/releases/tag/v0.0.2">
    <img alt="Release" src="https://img.shields.io/static/v1?label=release&message=v0.0.2&color=blue&style=for-the-badge" />
  </a>

  <br/>
  <img alt="Status" src="https://img.shields.io/badge/lifecycle-alpha-a0c3d2.svg" />
  <a href="https://jitpack.io/#open-feature/kotlin-sdk">
    <img alt="JitPack" src="https://jitpack.io/v/open-feature/kotlin-sdk.svg" />
  </a>
</p>
<!-- x-hide-in-docs-start -->

[OpenFeature](https://openfeature.dev) is an open specification that provides a vendor-agnostic, community-driven API for feature flagging that works with your favorite feature flag management tool.

<!-- x-hide-in-docs-end -->
## 🚀 Quick start

### Requirements

- The Android minSdk version supported is: `21`.

Note that this library is intended to be used in a mobile context, and has not been evaluated for use in other types of applications (e.g. server applications).

### Install

#### Jitpack

The Android project must include `maven("https://jitpack.io")` in `settings.gradle`.

You can now add the OpenFeature SDK dependency:
```kotlin
dependencies {
    api("com.github.open-feature:kotlin-sdk:<Latest>")
}
```
Please note that the `<Latest>` can be any `Commit SHA` or a version based on a branch as follows:
```
api("com.github.open-feature:kotlin-sdk:[ANY_BRANCH]-SNAPSHOT")
```

This will get a build from the head of the mentioned branch. 

#### Maven

Installation via Maven Central is currently [WIP](https://github.com/open-feature/kotlin-sdk/issues/37)

### Usage

```kotlin
// configure a provider and get client
OpenFeatureAPI.setProvider(customProvider)
val client = OpenFeatureAPI.getClient()

// get a bool flag value
client.getBooleanValue("boolFlag", default = false)

// get a bool flag after "ready" signal from provider
coroutineScope.launch {
    WithContext(Dispatchers.IO) {
        client.awaitProviderReady()
    }
    client.getBooleanValue("boolFlag", default = false)
}
```

## 🌟 Features

| Status | Features                        | Description                                                                                                                        |
| ------ | ------------------------------- | ---------------------------------------------------------------------------------------------------------------------------------- |
| ✅      | [Providers](#providers)         | Integrate with a commercial, open source, or in-house feature management tool.                                                     |
| ✅      | [Targeting](#targeting)         | Contextually-aware flag evaluation using [evaluation context](https://openfeature.dev/docs/reference/concepts/evaluation-context). |
| ✅      | [Hooks](#hooks)                 | Add functionality to various stages of the flag evaluation life-cycle.                                                             |
| ❌      | Logging                         | Integrate with popular logging packages.                                                                                           |
| ❌      | Named clients                   | Utilize multiple providers in a single application.                                                                                |
| ⚠️      | [Eventing](#eventing)           | React to state changes in the provider or flag management system.                                                                  |
| ✅      | [Shutdown](#shutdown)           | Gracefully clean up a provider during application shutdown.                                                                        |
| ⚠️      | [Extending](#extending)         | Extend OpenFeature with custom providers and hooks.                                                                                |

<sub>Implemented: ✅ | In-progress: ⚠️ | Not implemented yet: ❌</sub>

### Providers

[Providers](https://openfeature.dev/docs/reference/concepts/provider) are an abstraction between a flag management system and the OpenFeature SDK.
Look [here](https://openfeature.dev/ecosystem?instant_search%5BrefinementList%5D%5Btype%5D%5B0%5D=Provider&instant_search%5BrefinementList%5D%5Btechnology%5D%5B0%5D=kotlin) for a complete list of available providers.
If the provider you're looking for hasn't been created yet, see the [develop a provider](#develop-a-provider) section to learn how to build it yourself.

Once you've added a provider as a dependency, it can be registered with OpenFeature like this:

```kotlin
OpenFeatureAPI.setProvider(MyProvider())
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

### Eventing

Events allow you to react to state changes in the provider or underlying flag management system, such as flag definition changes, provider readiness, or error conditions.
Initialization events (`PROVIDER_READY` on success, `PROVIDER_ERROR` on failure) are dispatched for every provider.
Some providers support additional events, such as `PROVIDER_CONFIGURATION_CHANGED`.

Please refer to the documentation of the provider you're using to see what events are supported.

```kotlin
OpenFeatureAPI.eventsObserver()
    .observe<OpenFeatureEvents.ProviderReady>()
    .collect {
        // do something once the provider is ready
    }
```

### Shutdown

The OpenFeature API provides a close function to perform a cleanup of the registered provider.
This should only be called when your application is in the process of shutting down.

```kotlin
OpenFeatureAPI.shutdown()
```

## Extending

### Develop a provider

To develop a provider, you need to create a new project and include the OpenFeature SDK as a dependency.
You’ll then need to write the provider by implementing the `FeatureProvider` interface exported by the OpenFeature SDK.

```kotlin
class NewProvider(override val hooks: List<Hook<*>>, override val metadata: Metadata) : FeatureProvider {
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

    override fun initialize(initialContext: EvaluationContext?) {
        // add context-aware provider initialization
    }

    override fun onContextSet(oldContext: EvaluationContext?, newContext: EvaluationContext) {
        // add necessary changes on context change
    }

}
```

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

## 🤝 Contributing

Interested in contributing? Great, we'd love your help! To get started, take a look at the [CONTRIBUTING](CONTRIBUTING.md) guide.

### Thanks to everyone who has already contributed

<a href="https://github.com/open-feature/kotlin-sdk/graphs/contributors">
  <img src="https://contrib.rocks/image?repo=open-feature/kotlin-sdk" alt="Pictures of the folks who have contributed to the project" />
</a>

Made with [contrib.rocks](https://contrib.rocks).
<!-- x-hide-in-docs-end -->
