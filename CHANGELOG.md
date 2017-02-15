# Fastbreak Changelog / Release Notes

All notable changes to `Fastbreak` will be documented in this file. `Fastbreak` adheres to [Semantic Versioning](http://semver.org/).

## Why pre-1.0 releases?

Fastbreak is used heavily and is stable internally at Nike, however the wider community may have needs or use cases that we haven't considered. Therefore Fastbreak will live at a sub-1.0 version for a short time after its initial open source release to give it time to respond quickly to the open source community without ballooning the version numbers. Once its public APIs have stabilized again as an open source project it will be switched to the normal post-1.0 semantic versioning system.

#### 0.x Releases

- `0.10.x` Releases - [0.10.0](#0100)
- `0.9.x` Releases - [0.9.0](#090)

## [0.10.0](https://github.com/Nike-Inc/fastbreak/releases/tag/fastbreak-v0.10.0)

Released on 2017-02-15.

### Added

- The `CircuitBreaker` interface no longer directly contains the manual/callback mode methods (`throwExceptionIfCircuitBreakerIsOpen()`, `handleEvent(...)`, and `handleException(...)`). Instead, a new `CircuitBreaker.newManualModeTask()` method will return a `ManualModeTask` interface that contains the manual/callback mode methods. This makes `CircuitBreaker` usage less confusing, and also allows for some `CircuitBreaker` implementations that otherwise wouldn't be possible.
	- Added by [Nic Munroe][contrib_nicmunroe] in pull request [#5](https://github.com/Nike-Inc/fastbreak/pull/5). For issue [#4](https://github.com/Nike-Inc/fastbreak/issues/4).
	
## [0.9.0](https://github.com/Nike-Inc/fastbreak/releases/tag/fastbreak-v0.9.0)

Released on 2016-09-26.

### Added

- Initial open source code drop for Fastbreak.
	- Added by [Nic Munroe][contrib_nicmunroe].
	

[contrib_nicmunroe]: https://github.com/nicmunroe
