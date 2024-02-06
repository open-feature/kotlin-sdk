# Changelog

## [0.2.3](https://github.com/open-feature/kotlin-sdk/compare/v0.2.2...v0.2.3) (2024-02-02)


### 🧹 Chore

* Remove AsyncClient and refactor extensions ([#96](https://github.com/open-feature/kotlin-sdk/issues/96)) ([53a0908](https://github.com/open-feature/kotlin-sdk/commit/53a0908ee5d2e2fbacbfca9ce22e92e2e5de2308))
* update readme ([#95](https://github.com/open-feature/kotlin-sdk/issues/95)) ([2f83367](https://github.com/open-feature/kotlin-sdk/commit/2f833679c0983d33ac0aecfdf172fff8175e4c82))


### 📚 Documentation

* changed emphasis of provider register disclaimer ([cce8368](https://github.com/open-feature/kotlin-sdk/commit/cce8368f090b335b7b29c66479aaebb44c58ca5b))


### 🔄 Refactoring

* Rename shutdown event to notready event ([#97](https://github.com/open-feature/kotlin-sdk/issues/97)) ([5a2bc63](https://github.com/open-feature/kotlin-sdk/commit/5a2bc6353d862597bdae7ca803ebd1f444601e12))

## [0.2.2](https://github.com/open-feature/kotlin-sdk/compare/v0.2.1...v0.2.2) (2024-01-15)


### 🐛 Bug Fixes

* setProviderAndWait does not hang on ProviderError ([#88](https://github.com/open-feature/kotlin-sdk/issues/88)) ([1d4c24f](https://github.com/open-feature/kotlin-sdk/commit/1d4c24f193589fa7d0c754308547a775a3b55856))


### ✨ New Features

* add getProviderStatus() function ([#84](https://github.com/open-feature/kotlin-sdk/issues/84)) ([b3ba673](https://github.com/open-feature/kotlin-sdk/commit/b3ba673807dbba6919a08ff47fdebd6ab0d2cceb))
* Update observeEvents() API ([#89](https://github.com/open-feature/kotlin-sdk/issues/89)) ([466115b](https://github.com/open-feature/kotlin-sdk/commit/466115bce5b0de978b47fe15ff29e8875083fb2d))


### 🧹 Chore

* fix MavenCentral badge ([e8eea26](https://github.com/open-feature/kotlin-sdk/commit/e8eea26b6e11fddd092a72b618a7e7eaaba567da))


### 📚 Documentation

* Update Events docs and signature ([#90](https://github.com/open-feature/kotlin-sdk/issues/90)) ([9ab5006](https://github.com/open-feature/kotlin-sdk/commit/9ab5006eb8c9945c68959efcd1722da2a2b522db))

## [0.2.1](https://github.com/open-feature/kotlin-sdk/compare/v0.2.0...v0.2.1) (2023-12-19)


### ✨ New Features

* downgrade the kotlin version so it's compatible with AGP 7.4 ([#83](https://github.com/open-feature/kotlin-sdk/issues/83)) ([8c3207a](https://github.com/open-feature/kotlin-sdk/commit/8c3207a1eba4be793fdcc87043f53ead05e314d5))
* set evaluation-context in setProviderAndWait() ([#80](https://github.com/open-feature/kotlin-sdk/issues/80)) ([e19dd88](https://github.com/open-feature/kotlin-sdk/commit/e19dd8893c8df98c49168af27ec44be43d542dcb))

## [0.2.0](https://github.com/open-feature/kotlin-sdk/compare/v0.1.0...v0.2.0) (2023-12-19)


### ⚠ BREAKING CHANGES

* Artifact name change ([#79](https://github.com/open-feature/kotlin-sdk/issues/79))

### 🐛 Bug Fixes

* **deps:** update dependency org.jetbrains.kotlinx:kotlinx-serialization-json to v1.6.2 ([#68](https://github.com/open-feature/kotlin-sdk/issues/68)) ([f7c7ab0](https://github.com/open-feature/kotlin-sdk/commit/f7c7ab0e7235533fc5f665ff6d383ea4522b25ad))


### ✨ New Features

* Make the eventing provider specific instead of being singleton ([#75](https://github.com/open-feature/kotlin-sdk/issues/75)) ([321f8be](https://github.com/open-feature/kotlin-sdk/commit/321f8be3201c4c7bf33ace1edb1b70e8efd352f8))


### 🧹 Chore

* Artifact name change ([#79](https://github.com/open-feature/kotlin-sdk/issues/79)) ([8c540e6](https://github.com/open-feature/kotlin-sdk/commit/8c540e6064bb79c1bf425bb006ad7e2b3b3a616e))
* Remove mentions of jitpack. Adjusts maven central instructions ([#78](https://github.com/open-feature/kotlin-sdk/issues/78)) ([1246d5d](https://github.com/open-feature/kotlin-sdk/commit/1246d5d1b53fc00898d3ac99cd750f0206ef11d6))
* Remove minify false  ([#77](https://github.com/open-feature/kotlin-sdk/issues/77)) ([f4ba35d](https://github.com/open-feature/kotlin-sdk/commit/f4ba35d34c19ddc3a7c4dd81ced01689766af4c3))

## [0.1.0](https://github.com/open-feature/kotlin-sdk/compare/v0.0.4...v0.1.0) (2023-11-08)


### ✨ New Features

* Add new error for type mismatch ([#64](https://github.com/open-feature/kotlin-sdk/issues/64)) ([95e910a](https://github.com/open-feature/kotlin-sdk/commit/95e910a23be8a55eda1218144e540a3221cd877f))


### 🧹 Chore

* update spec release link ([7888447](https://github.com/open-feature/kotlin-sdk/commit/7888447c968d911c4495a9e3bf027d1fcd739f70))
* update the release version badge on the readme ([#59](https://github.com/open-feature/kotlin-sdk/issues/59)) ([aa44c7f](https://github.com/open-feature/kotlin-sdk/commit/aa44c7f1bf1cf0922ad22d94b02693288be499d7))


### 🔄 Refactoring

* changing vars to vals ([#65](https://github.com/open-feature/kotlin-sdk/issues/65)) ([cdcb2df](https://github.com/open-feature/kotlin-sdk/commit/cdcb2df45fc956bc4f80d3273ea1cb5ba37446b9))
* make targeting key immutable ([#66](https://github.com/open-feature/kotlin-sdk/issues/66)) ([3ec73f2](https://github.com/open-feature/kotlin-sdk/commit/3ec73f243a650021464e872b6593fef7677d2153))

## [0.0.4](https://github.com/open-feature/kotlin-sdk/compare/v0.0.3...v0.0.4) (2023-10-16)


### 📚 Documentation

* add release instructions ([#57](https://github.com/open-feature/kotlin-sdk/issues/57)) ([8d87712](https://github.com/open-feature/kotlin-sdk/commit/8d877122449b3f298a71f336b84033fc43c879bd))

## [0.0.3](https://github.com/open-feature/kotlin-sdk/compare/v0.0.2...v0.0.3) (2023-10-12)


### 🐛 Bug Fixes

* **deps:** update dependency org.jetbrains.kotlinx:kotlinx-serialization-json to v1.6.0 ([#39](https://github.com/open-feature/kotlin-sdk/issues/39)) ([714dd05](https://github.com/open-feature/kotlin-sdk/commit/714dd058b6781f1eb7addb67de2905cab47b76df))
* **deps:** update dependency org.mockito.kotlin:mockito-kotlin to v5.1.0 ([#10](https://github.com/open-feature/kotlin-sdk/issues/10)) ([695c380](https://github.com/open-feature/kotlin-sdk/commit/695c3809c136f29e8682edc0a50b18f5938e252c))
* Release please handle versioning ([#47](https://github.com/open-feature/kotlin-sdk/issues/47)) ([bc249c5](https://github.com/open-feature/kotlin-sdk/commit/bc249c50225955fbda3319d490b188ddc399f0b7))


### 🧹 Chore

* add missing language to a code block ([028d880](https://github.com/open-feature/kotlin-sdk/commit/028d880a691151b784e7404dfab7e8611a62f599))
* **deps:** update actions/cache action to v3 ([#18](https://github.com/open-feature/kotlin-sdk/issues/18)) ([9db4359](https://github.com/open-feature/kotlin-sdk/commit/9db4359b31d5cd06ab01957c5a965e357742cef4))
* **deps:** update actions/checkout action to v3 ([#19](https://github.com/open-feature/kotlin-sdk/issues/19)) ([8299d5f](https://github.com/open-feature/kotlin-sdk/commit/8299d5fc92f2458cb720f0ab71212e70cac528d1))
* **deps:** update actions/checkout action to v4 ([#43](https://github.com/open-feature/kotlin-sdk/issues/43)) ([17d31e4](https://github.com/open-feature/kotlin-sdk/commit/17d31e40d773bea66d087b95e8339020e7dd7c3b))
* **deps:** update actions/setup-java action to v3 ([#22](https://github.com/open-feature/kotlin-sdk/issues/22)) ([8969edf](https://github.com/open-feature/kotlin-sdk/commit/8969edfca4bf7e58560bdec987709115bb703a72))
* **deps:** update dependency gradle to v8.3 ([#14](https://github.com/open-feature/kotlin-sdk/issues/14)) ([e59e2c1](https://github.com/open-feature/kotlin-sdk/commit/e59e2c1b13a359d22cf365d530e5fc9f68a3ca3c))
* **deps:** update kotlin monorepo to v1.9.10 ([#11](https://github.com/open-feature/kotlin-sdk/issues/11)) ([04717a1](https://github.com/open-feature/kotlin-sdk/commit/04717a192d4d54f2af9e289a5aaaca26da83798c))
* **deps:** update plugin org.jlleitschuh.gradle.ktlint to v11.5.1 ([#12](https://github.com/open-feature/kotlin-sdk/issues/12)) ([bdf5abb](https://github.com/open-feature/kotlin-sdk/commit/bdf5abb6192ee6d9062694fc03099ddb848de00e))
* **deps:** update plugin org.jlleitschuh.gradle.ktlint to v11.6.0 ([#45](https://github.com/open-feature/kotlin-sdk/issues/45)) ([6f0ad55](https://github.com/open-feature/kotlin-sdk/commit/6f0ad559a8a13a53f1948008b109506fd72c3db2))
* **deps:** update plugin org.jlleitschuh.gradle.ktlint to v11.6.1 ([#51](https://github.com/open-feature/kotlin-sdk/issues/51)) ([c66558a](https://github.com/open-feature/kotlin-sdk/commit/c66558a88710e146c6dc9d0cde047afd94820bdf))
* move release please managed artifacts ([#53](https://github.com/open-feature/kotlin-sdk/issues/53)) ([d19f2c5](https://github.com/open-feature/kotlin-sdk/commit/d19f2c505d69292f338ee80bec7acbf772aeb389))
* release please should use package "." ([#48](https://github.com/open-feature/kotlin-sdk/issues/48)) ([150883b](https://github.com/open-feature/kotlin-sdk/commit/150883bff037e64ea0383488c728e1627b46edde))


### 📚 Documentation

* add develop hook section ([4cfef58](https://github.com/open-feature/kotlin-sdk/commit/4cfef58b612ffdea12bff8e4d4060dfaad93f8ae))
* update install instructions ([#38](https://github.com/open-feature/kotlin-sdk/issues/38)) ([d7f504e](https://github.com/open-feature/kotlin-sdk/commit/d7f504e44bf1a896286c68b0b0df687b012e73d6))
* update jitpack link ([#42](https://github.com/open-feature/kotlin-sdk/issues/42)) ([1ed69d2](https://github.com/open-feature/kotlin-sdk/commit/1ed69d2b03c127e53e57252a47af1241337ed848))
* Update README according to latest template ([#41](https://github.com/open-feature/kotlin-sdk/issues/41)) ([ca30073](https://github.com/open-feature/kotlin-sdk/commit/ca3007333a4fe09a4e7ec439150beaf9814c6c5a))
* Update README.md ([#44](https://github.com/open-feature/kotlin-sdk/issues/44)) ([2b91c75](https://github.com/open-feature/kotlin-sdk/commit/2b91c7524fbb97d2c520455243cc5f2567690f5a))

## [0.0.2](https://github.com/open-feature/kotlin-sdk/compare/v0.0.1...v0.0.2) (2023-08-21)


### 🐛 Bug Fixes

* Test release please with feat. ([#21](https://github.com/open-feature/kotlin-sdk/issues/21)) ([fad1fc4](https://github.com/open-feature/kotlin-sdk/commit/fad1fc468aa298268e51ed3a2e6a2e65b2f9fcef))


### 🧹 Chore

* **main:** release 1.0.0 ([#31](https://github.com/open-feature/kotlin-sdk/issues/31)) ([8194fe0](https://github.com/open-feature/kotlin-sdk/commit/8194fe02feb0888cbfd69a24af77c3513cdf0759))
