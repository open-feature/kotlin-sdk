# OpenFeature Kotlin SDK

![Status](https://img.shields.io/badge/lifecycle-alpha-a0c3d2.svg)

What is OpenFeature?
[OpenFeature][openfeature-website] is an open standard that provides a vendor-agnostic, community-driven API for feature flagging that works with your favorite feature flag management tool.

Why standardize feature flags?
Standardizing feature flags unifies tools and vendors behind a common interface which avoids vendor lock-in at the code level. Additionally, it offers a framework for building extensions and integrations and allows providers to focus on their unique value proposition.

This Kotlin implementation of an OpenFeature SDK has been developed at Spotify, and currently made available and maintained within the Spotify Open Source Software organization. Part of our roadmap is for the OpenFeature community to evaluate this implementation and potentially include it in the existing ecosystem of [OpenFeature SDKs][openfeature-sdks].

## Requirements

- The Android minSdk version supported is: `21`.

Note that this library is intended to be used in a mobile context, and has not been evaluated for use in other type of applications (e.g. server applications).


## Usage

### Adding the library dependency (WORK IN PROGRESS ⚠️)

This library is not published to central repositories yet.
Clone this repository and run the following to install the library locally:
```
./gradlew publishToMavenLocal
```
The Android project must include `mavenLocal()` in `settings.gradle`.

You can now add the OpenFeature SDK dependency:
```
implementation(""dev.openfeature:kotlin-sdk:0.0.1-SNAPSHOT")
```

### Resolving a flag
```kotlin
import dev.openfeature.sdk.*

// Change NoOpProvider with your actual provider
OpenFeatureAPI.setProvider(NoOpProvider(), MutableContext())
val flagValue = OpenFeatureAPI.getClient().getBooleanValue("boolFlag", false)
```
Setting a new provider or setting a new evaluation context are asynchronous operations. The provider might execute I/O operations as part of these method calls (e.g. fetching flag evaluations from the backend and store them in a local cache). It's advised to not interact with the OpenFeature client until the `setProvider()` or `setEvaluationContext()` functions have returned successfully.

Please refer to our [documentation on static-context APIs](https://github.com/open-feature/spec/pull/171) for further information on how these APIs are structured for the use-case of mobile clients.


### Providers

To develop a provider, you need to create a new project and include the OpenFeature SDK as a dependency. You’ll then need to write the provider itself. This can be accomplished by implementing the `FeatureProvider` interface exported by the OpenFeature SDK.

[openfeature-website]: https://openfeature.dev
[openfeature-sdks]: https://openfeature.dev/docs/reference/technologies/