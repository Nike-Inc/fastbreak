# Fastbreak Changelog / Release Notes

All notable changes to `Fastbreak` will be documented in this file. `Fastbreak` adheres to [Semantic Versioning](http://semver.org/).

## Why pre-1.0 releases?

Fastbreak is used heavily and is stable internally at Nike, however the wider community may have needs or use cases that we haven't considered. Therefore Fastbreak will live at a sub-1.0 version for a short time after its initial open source release to give it time to respond quickly to the open source community without ballooning the version numbers. Once its public APIs have stabilized again as an open source project it will be switched to the normal post-1.0 semantic versioning system.

#### 0.x Releases

- `0.10.x` Releases - [0.10.2](#0102), [0.10.1](#0101), [0.10.0](#0100)
- `0.9.x` Releases - [0.9.0](#090)

## [0.10.2](https://github.com/Nike-Inc/fastbreak/releases/tag/fastbreak-v0.10.2)

Released on 2022-02-24.

### Project Build

- Cleaned up bit-rot and generally de-rusted the project. The main changes were bringing the project up to gradle 
  `7.4`, migrating to Github Actions for CI build, and setting things up to publish directly to Maven Central.  
    - Cleaned up by [Nic Munroe][contrib_nicmunroe] in pull requests 
      [#9](https://github.com/Nike-Inc/fastbreak/pull/9), [#10](https://github.com/Nike-Inc/fastbreak/pull/10), and
	  [#11](https://github.com/Nike-Inc/fastbreak/pull/11).

## [0.10.1](https://github.com/Nike-Inc/fastbreak/releases/tag/fastbreak-v0.10.1)

_**NOTE: This version was only published to JCenter. It does not exist on Maven Central. For a Maven Central 
version you'll need to update to Fastbreak version `0.10.2` or later.**_

Released on 2017-11-08.

### Added

- Added default `getId` method to `CircuitBreaker` interface to identify each `CircuitBreaker` instance. This is implementation specific and there is no guarantee of uniqueness. If not overridden by the implementation then a default ID will be used. 
	- Added by [Robert_Abeyta][contrib_rabeyta] in pull request [#7](https://github.com/Nike-Inc/fastbreak/pull/7).

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
[contrib_rabeyta]: https://github.com/rabeyta
