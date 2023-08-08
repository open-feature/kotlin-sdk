<!-- markdownlint-disable MD033 -->
<p align="center">
  <picture>
    <source media="(prefers-color-scheme: dark)" srcset="https://raw.githubusercontent.com/open-feature/community/0e23508c163a6a1ac8c0ced3e4bd78faafe627c7/assets/logo/horizontal/white/openfeature-horizontal-white.svg">
    <source media="(prefers-color-scheme: light)" srcset="https://raw.githubusercontent.com/open-feature/community/0e23508c163a6a1ac8c0ced3e4bd78faafe627c7/assets/logo/horizontal/black/openfeature-horizontal-black.svg">
    <img align="center" alt="OpenFeature Logo">
  </picture>
</p>

<h2 align="center">OpenFeature Kotlin SDKs</h2>

![Status](https://img.shields.io/badge/lifecycle-alpha-a0c3d2.svg) [![](https://jitpack.io/v/spotify/openfeature-kotlin-sdk.svg)](https://jitpack.io/#spotify/openfeature-kotlin-sdk)

## 👋 Hey there! Thanks for checking out the OpenFeature Kotlin SDK

### What is OpenFeature?

[OpenFeature][openfeature-website] is an open standard that provides a vendor-agnostic, community-driven API for feature flagging that works with your favorite feature flag management tool.

### Why standardize feature flags?

Standardizing feature flags unifies tools and vendors behind a common interface which avoids vendor lock-in at the code level. Additionally, it offers a framework for building extensions and integrations and allows providers to focus on their unique value proposition.

## 🔍 Requirements

- The Android minSdk version supported is: `21`.

Note that this library is intended to be used in a mobile context, and has not been evaluated for use in other type of applications (e.g. server applications).

## 📦 Installation

### Jitpack

The Android project must include `maven("https://jitpack.io")` in `settings.gradle`.

You can now add the OpenFeature SDK dependency:
```kotlin
dependencies {
    api("com.github.spotify:openfeature-kotlin-sdk:<Latest>")
}
```
Please note that the `<Latest>` can be any `Commit SHA` or a version based off a branch as following:
```
api("com.github.spotify:openfeature-kotlin-sdk:[ANY_BRANCH]-SNAPSHOT")
```

This will get a build from the head of the mentioned branch. 

### Maven

Installation via Maven Central is currently WIP

## 🌟 Features

- support for various backend [providers](https://openfeature.dev/docs/reference/concepts/provider)
- easy integration and extension via [hooks](https://openfeature.dev/docs/reference/concepts/hooks)
- bool, string, numeric, and object flag types
- [context-aware](https://openfeature.dev/docs/reference/concepts/evaluation-context) evaluation

## 🚀 Usage

```kotlin
    // configure a provider and get client
    OpenFeatureAPI.setProvider(customProvider)
    val client = OpenFeatureAPI.getClient()

    // get a bool flag value
    client.getBooleanValue("boolFlag", default = false)
    
    // get a bool flag value async
    coroutineScope.launch {
        WithContext(Dispatchers.IO) {
            client.awaitProviderReady()
        }
        client.getBooleanValue("boolFlag", default = false)
    }
```

### Events

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

### Providers

To develop a provider, you need to create a new project and include the OpenFeature SDK as a dependency.
This can be a new repository or included in the existing contrib repository available under the OpenFeature organization.
Finally, you’ll then need to write the provider itself.
This can be accomplished by implementing the `Provider` interface exported by the OpenFeature SDK.

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
        // add context-aware provider initialisation
    }

    override fun onContextSet(oldContext: EvaluationContext?, newContext: EvaluationContext) {
        // add necessary changes on context change
    }

}
```


## ⭐️ Support the project

- Give this repo a ⭐️!
- Follow us on social media:
    - Twitter: [@openfeature](https://twitter.com/openfeature)
    - LinkedIn: [OpenFeature](https://www.linkedin.com/company/openfeature/)
- Join us on [Slack](https://cloud-native.slack.com/archives/C0344AANLA1)
- For more check out our [community page](https://openfeature.dev/community/)

## 🤝 Contributing

Interested in contributing? Great, we'd love your help! To get started, take a look at the [CONTRIBUTING](CONTRIBUTING.md) guide.

### Thanks to everyone that has already contributed

<a href="https://github.com/open-feature/kotlin-sdk/graphs/contributors">
  <img src="https://contrib.rocks/image?repo=open-feature/kotlin-sdk" alt="Pictures of the folks who have contributed to the project" />
</a>

Made with [contrib.rocks](https://contrib.rocks).

## 📜 License

[Apache License 2.0](LICENSE)

[openfeature-website]: https://openfeature.dev
