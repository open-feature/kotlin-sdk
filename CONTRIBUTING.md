## Getting Started

OpenFeature is not keen on vendor-specific stuff in this library, but if there are changes that need to happen in the spec to enable vendor-specific stuff in user code or other extension points, check out [the spec](https://github.com/open-feature/spec).

## Formatting

This repo uses [ktlint](https://github.com/JLLeitschuh/ktlint-gradle) for formatting. 

Please consider adding a pre-commit hook for formatting using 

```
./gradlew addKtlintCheckGitPreCommitHook
```
Manual formatting is done by invoking
```
./gradlew ktlintFormat
```