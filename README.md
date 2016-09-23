# Fastbreak - A Simple Java-8-Native Circuit Breaker

[ ![Download](https://api.bintray.com/packages/nike/maven/fastbreak/images/download.svg) ](https://bintray.com/nike/maven/fastbreak/_latestVersion)
[![][travis img]][travis]
[![Code Coverage](https://img.shields.io/codecov/c/github/Nike-Inc/fastbreak/master.svg)](https://codecov.io/github/Nike-Inc/fastbreak?branch=master)
[![][license img]][license]

<a name="overview"></a>
A circuit breaker is typically used to help stabilize distributed systems by detecting when a downstream dependency is unhealthy and preventing calls to that service for a while to give it a chance to become healthy again. 

Fastbreak is a simple but powerful circuit breaker with default behavior inspired by the [Akka Circuit Breaker][akka cb], but supports the Java 8 `CompletableFuture` natively and only has a single dependency (SLF4J for logging). Fastbreak supports three workflows:

* **Asynchronous Future Mode** - If you have an asynchronous task represented by a `CompletableFuture` that should be protected with a circuit breaker you can do so by passing the future into `CircuitBreaker`'s `executeAsyncCall(Supplier<CompletableFuture<ET>>)` method. This is the recommended way to use Fastbreak when possible. 
* **Synchronous Mode** - You can protect a synchronous blocking task with a circuit breaker by calling `CircuitBreaker`'s `executeBlockingCall(Callable<ET> eventSupplier)` method. This has some drawbacks when compared with the asynchronous future mode but can be trivially converted to asynchronous future mode by wrapping the task in a `CompletableFuture` and calling `executeAsyncCall(Supplier<CompletableFuture<ET>>)` instead.
* **Asynchronous Callback Mode, a.k.a. Manual Mode** - There are some situations where you cannot wrap a task in a `CompletableFuture` or `Callable`, such as some asynchronous workflows that rely on callbacks. Fastbreak still allows you to protect these tasks with a circuit breaker by exposing the important methods and letting you call them manually at the appropriate time: `throwExceptionIfCircuitBreakerIsOpen()`, `handleEvent(ET)`, and `handleException(Throwable)`. 

<a name="quickstart"></a>
## Quickstart

<a name="quickstart_cb_definition"></a>
### Circuit Breaker Definition

``` java
// Everything in the builder is optional - you can create a CircuitBreaker from a blank builder 
//      and it will still be a fully functioning circuit breaker. Defaults explained inline below.
CircuitBreaker<TaskResult> exampleCircuitBreaker = CircuitBreakerImpl
    .<TaskResult>newBuilder()
    // In this example only 42 is a successful event, all other results are considered breaking failures.
    //      Default is to assume all events are successful/healthy events.
    .withBreakingEventStrategy(taskResult -> taskResult.resultCode() != 42)
    // In this example BadCallDataExceptions are not considered breaking failures but everything else is.
    //      Default is to assume all exceptions are breaking failures.
    .withBreakingExceptionStrategy(ex -> !(ex instanceof BadCallDataException))
    // Set the number of max consecutive failures allowed before tripping the circuit breaker OPEN to 30.
    //      Application-wide default can be set via the fastbreak.defaultMaxConsecutiveFailuresAllowed
    //      System Property, or 20 will be used as a fallback default.
    .withMaxConsecutiveFailuresAllowed(30)
    // Fail calls with a CircuitBreakerTimeoutException after 3 seconds.
    //      Default is no call timeout - calls can run indefinitely.
    .withCallTimeout(Duration.ofSeconds(3))
    // Set the amount of time after the CB trips OPEN before it will allow a single call through to check 
    //      if the downstream system is healthy (and thus allow it to be CLOSED) to 1 minute.
    //      Application-wide default can be set via the fastbreak.defaultResetTimeoutInSeconds
    //      System Property, or 15 seconds will be used as a fallback default.
    .withResetTimeout(Duration.ofMinutes(1))
    // The ID will show up in logs so you know which circuit breaker tripped OPEN or CLOSED, etc.
    //      Default is "UNSPECIFIED".
    .withId("contrived-example-circuit-breaker")
    .build();
```

### Asynchronous Future Mode

Assume a `TaskService` exists with a `performAsyncTask()` method that returns a `CompletableFuture<TaskResult>`:

``` java
public class TaskService {
    // --snip--
    public CompletableFuture<TaskResult> performAsyncTask() {
        CompletableFuture<TaskResult> resultFuture = /* ... resultFuture creation goes here ... */;
        return resultFuture;
    }
    // --snip--    
}
```

Protecting those async calls with the circuit breaker defined above might look like this:
 
``` java
// If the circuit breaker is OPEN when the executeAsycCall(...) method is called, then a
//      CircuitBreakerOpenException will be thrown immediately before taskService is called.
// If the call takes longer than the circuit breaker's call timeout, then the resultFuture
//      will be completed exceptionally with a CircuitBreakerTimeoutException.
CompletableFuture<TaskResult> resultFuture = exampleCircuitBreaker.executeAsyncCall(
    taskService::performAsyncTask
);
// ... resultFuture processing goes here.
```

In real production code you might put the circuit breaker protection into the `TaskService` method itself, saving callers from having to remember to do it each time.

### Synchronous Mode

Assume a `TaskService` exists with a `performBlockingTask()` method that returns a `TaskResult`:

``` java
public class TaskService {
    // --snip--
    public TaskResult performBlockingTask() {
        TaskResult result = /* ... result creation goes here ... */;
        return result;
    }
    // --snip--    
}
```

Protecting those blocking synchronous calls with the circuit breaker defined above might look like this:

``` java
// If the circuit breaker is OPEN when the executeBlockingCall(...) method is called, then a
//      CircuitBreakerOpenException will be thrown immediately before taskService is called.
TaskResult result = exampleCircuitBreaker.executeBlockingCall(taskService::performBlockingTask);
// ... result processing goes here.
```

Note that this has some drawbacks over Asynchronous Mode:

* Due to the synchronous blocking nature you will not receive a `CircuitBreakerTimeoutException` exception until *after* the supplier finishes, even if it takes much longer than the call timeout value to complete.
* There is no way to know whether exceptions thrown by your `TaskResult` supplier are due to a bug in your application or the downstream call so we have to assume they are part of the circuit-breaker-protected call and count them against the healthy/unhealthy state of the circuit breaker.

<a name="convert_sync_to_async_mode"></a>
#### Converting a Synchronous Mode Task to Asynchronous Future Mode

If you want to avoid those synchronous mode drawbacks you can simply wrap the task in a `CompletableFuture` and execute it in Asynchronous Future Mode instead. Here is the same `performBlockingTask()` call from above, but converted to run in async future mode:

``` java
CompletableFuture<TaskResult> resultFuture = exampleCircuitBreaker.executeAsyncCall(
    () -> CompletableFuture.supplyAsync(taskService::performBlockingTask)
);
```

Just keep in mind that the thread pool underlying the default `CompletableFuture.supplyAsync(Supplier)` is limited, so you might starve that threadpool and create an artifical bottleneck depending on how long `taskService::performBlockingTask` takes and the kind of throughput you need. You can always use `CompletableFuture.supplyAsync(Supplier, Executor)` instead and pass in whatever `Executor` you want to fully control the threading behavior. 

### Asynchronous Callback Mode, a.k.a. Manual Mode

Assume a `TaskService` exists with a `void performAsyncTaskWithCallback(Consumer<TaskResult>, Consumer<Throwable>)` method where the result or error is communicated through callbacks:

``` java
public void performAsyncTaskWithCallback(Consumer<TaskResult> resultCallback, 
                                         Consumer<Throwable> errorCallback) {
    try {
        // ... executing the task asynchronously and generating the taskResult object goes here ...
        resultCallback.accept(taskResult);
    }
    catch(Throwable ex) {
        errorCallback.accept(ex);
    }
}
```

You can protect this scenario with the circuit breaker defined above by doing something like this:

``` java
// Allow the circuit breaker to throw a CircuitBreakerOpenException if the circuit is OPEN.
exampleCircuitBreaker.throwExceptionIfCircuitBreakerIsOpen();
// If we reach here then the circuit is CLOSED or HALF-OPEN (to allow a single healthcheck call through).
//      In either case we can execute the task.
taskService.performAsyncTaskWithCallback(
    taskResult -> {
        // Tell the circuit breaker about the TaskResult event so it can contribute to the circuit
        //      breaker's state.
        exampleCircuitBreaker.handleEvent(taskResult);
        // ... taskResult processing goes here ...
    },
    error -> {
        // Tell the circuit breaker about the error so it can contribute to the circuit breaker's state.
        exampleCircuitBreaker.handleException(error);
        // ... error processing goes here ...
    }
);
```

There are many different ways to handle callback scenarios - the event and error consumers might be defined elsewhere, they might be method references, you might need to pass the circuit breaker around and weave the `handleEvent` and `handleException` calls into your code manually in other ways, etc. But as long as you're careful and don't let the result fall through the cracks without updating the circuit breaker with a `handleEvent` or `handleException` method call, then the circuit breaker will work just fine.

This mode requires the most diligence on the application developer's part to implement correctly but it also provides the most flexibility and allows you to protect virtually anything with a circuit breaker.

## Fastbreak `CircuitBreaker` Lifecycle

A circuit breaker is in charge of determining whether a downstream service is healthy or unhealthy and preventing calls to the service when it is unhealthy to give it a chance to recover rather than letting it get overwhelmed. The output of a service call is either a normal result (an "event"), an exception, or the call may timeout without a result (timing out is an option that some circuit breaker implementations may or may not support). 

***An event doesn't always mean a successful healthy call however, and an exception doesn't always mean an unhealthy call.*** For example when making HTTP requests a 4xx error status code means the caller did something wrong not that the service is unhealthy, while a 5xx error status code generally means a potentially unhealthy service. Similarly the HTTP client may throw exceptions instead when it sees an error status code, so an exception representing a 4xx error might be considered healthy while an exception representing a 5xx error might be considered unhealthy. Or at least these assumptions may be true generally but not always, which is why Fastbreak's `CircuitBreaker` allows for flexibility in determining breaking/unhealthy calls. 

The lifecycle of a Fastbreak `CircuitBreaker` looks something like this:

* After enough unhealthy calls, a circuit breaker may decide the downstream service is unhealthy enough that it needs to be protected or that callers should fail-fast rather than waiting for a service that is slow and timing out, and the circuit may change to an OPEN state.
* When in an OPEN state, all calls passing through the circuit breaker will short-circuit-fail immediately with a `CircuitBreakerOpenException` before the call is executed.
* After a circuit enters the OPEN state it will periodically allow one or more calls through to see if the downstream service has stabilized and become healthy again. This is sometimes referred to as a HALF-OPEN state. The time between periodic checks is usually a configurable value per circuit breaker.
* When the circuit breaker detects that the HALF-OPEN calls indicate the service is healthy again then the circuit will change back to the CLOSED state, allowing all calls through.

**NOTE: Since `CircuitBreaker`s are inherently stateful, they should only be created once for a given downstream service being protected and reused for all calls against that service.**

### Lifecycle Event Notifications

You can be notified of state changes for Fastbreak circuit breakers by registering callback listeners via the following `CircuitBreaker` methods: `onClose(Runnable)`, `onHalfOpen(Runnable)`, and `onOpen(Runnable)`. The `Runnable`s passed in to those methods will be executed each time the circuit breaker's state changes to the appropriate state. This can be useful for metrics gathering, triggering production alarms, or any other purpose.

`CircuitBreaker` implementations should call these notification callbacks asynchronously on separate threads to avoid blocking the main application workflow. 

## Fastbreak `CircuitBreaker` Implementations

The Fastbreak `CircuitBreaker` class is an interface defining the API contract all Fastbreak circuit breakers must follow, allowing multiple different implementations. You can write your own if needed (please consider [contributing](CONTRIBUTING.md) back to this project if the result is potentially reusable by others), however Fastbreak includes several implementations that cover most use cases: 

### `CircuitBreakerImpl` 

`CircuitBreakerImpl` is the default implementation and is covered in the [quickstart](#quickstart) section. It is flexible and can cover many use cases by itself. Its behavior is modeled after the [Akka Circuit Breaker][akka cb]. In particular:

* Circuit breaking events are determined by passing the event through a configurable `BreakingEventStrategy`.
* Circuit breaking exceptions are determined by passing the exception through a configurable `BreakingExceptionStrategy`.
* After the configurable number of consecutive breaking failures the circuit will be set to OPEN state, causing all subsequent calls to short circuit and immediately throw a `CircuitBreakerOpenException` as long as the circuit is OPEN.
* Once opened, the circuit will stay open for a configurable "reset duration", after which a single call will be allowed through. This is the "HALF-OPEN" state.
* If the HALF-OPEN call succeeds then the circuit will be closed again, allowing all subsequent calls through. If the HALF-OPEN call fails, then the circuit will remain open for another reset duration.
* There is a configurable call timeout duration that can be set which will be used for `executeAsyncCall(Supplier)` and `executeBlockingCall(Callable)` calls. If the call takes longer than the call timeout duration then a `CircuitBreakerTimeoutException` will be thrown and will count against the number of consecutive failures allowed.

### `CircuitBreakerDelegate`

You may find yourself in a situation where you have one logical downstream system that should be protected by a single circuit breaker, but multiple calls that produce different event types and therefore cannot share the same `CircuitBreaker` instance. You can use `CircuitBreakerDelegate` to reuse an existing `CircuitBreaker` with a different event type, thus allowing multiple circuit breakers with different event types that all funnel to the same underlying circuit breaker instance that tallies the call successes/failures and controls the circuit state. 

A `CircuitBreakerDelegate` is created with two arguments: (1) the `CircuitBreaker` that should be delegated to for all calls, and (2) an event converter that knows how to convert the `CircuitBreakerDelegate`'s event type to the event type natively understood by the delegate `CircuitBreaker`.

For example, assume there is a downstream system that is called via HTTP, and there are two HTTP clients in your application that need to call this downstream system but produce different response object types, so they can't normally share the same `CircuitBreaker` instance. In this case you could create a base `CircuitBreaker<Integer>` to protect the downstream service that takes an integer HTTP status code as its event and considers 5xx HTTP status codes to be breaking:

``` java
CircuitBreaker<Integer> coreCircuitBreaker = CircuitBreakerImpl
    .<Integer>newBuilder(httpStatusCode -> (httpStatusCode >= 500))
    .build();
```

Then you could create multiple `CircuitBreakerDelegate`s that reuse the core circuit breaker, one for each HTTP client:

``` java
CircuitBreaker<FooHttpClientResponse> fooClientCircuitBreaker =
            new CircuitBreakerDelegate<>(coreCircuitBreaker, FooHttpClientResponse::getHttpStatusCode);
            
CircuitBreaker<BarHttpClientResponse> barClientCircuitBreaker =
            new CircuitBreakerDelegate<>(
                coreCircuitBreaker,
                barResponse -> barResponse.statusCode().asInt()
            );            
```

In both cases the `coreCircuitBreaker` is used as the underlying source of truth for the state of the wrapper circuit breaker, so what happens to one affects the other; i.e. if the `coreCircuitBreaker` trips OPEN then both wrapper `CircuitBreakerDelegate`s will be OPEN, and if the core circuit breaker is CLOSED then the wrapper circuit breakers will be closed. The `FooHttpClientResponse` and `BarHttpClientResponse` events from their respective wrapper circuit breakers will be converted to the event type understood natively by the core circuit breaker and contribute to the core's state. 
 
NOTE: Exceptions that are thrown when executing a `CircuitBreakerDelegate`'s `eventConverter` will *not* be passed on to the core delegate to be handled as failure/unhealthy or successful/healthy calls. Those errors would indicate a problem with your application, not the downstream service, so they are ignored wherever possible. The one exception to this rule is when calling `executeBlockingCall(Callable)`, where errors thrown by the `eventConverter` cannot be distinguished from any other error for technical reasons. **Bottom line: avoid any problems or unexpected behavior by making sure your `eventConverter` never throws exceptions.**
  
### `CircuitBreakerForHttpStatusCode`

`CircuitBreakerForHttpStatusCode` is a helper class for the common case of needing a circuit breaker to protect calling a downstream service via HTTP client. It is intended for the generic HTTP call case - it handles integer events meant to represent the HTTP response status code received from the downstream service, and considers any status code greater than or equal to 500 to be a circuit breaking/unhealthy call. 4xx errors indicate a problem with the call, not the called service, so they are not considered unhealthy. All exceptions are considered circuit breaking/unhealthy calls. 

This class also provides some static methods for retrieving "global" instances based on a key (the `getDefaultHttpStatusCodeCircuitBreakerForKey(...)` methods). These methods are thread safe so you can use them in a multithreaded environment to always retrieve the same circuit breaker instance for a given String key (e.g. the host being called, or a specific endpoint).

Keep in mind that instances of `CircuitBreakerForHttpStatusCode` only know how to handle integer events (HTTP status code). HTTP clients return response objects, so you'll likely need to wrap things up in a `CircuitBreakerDelegate` as described above to convert the HTTP client's response object to a HTTP status code for the circuit breaker.
 
Here is an example usage scenario:

``` java
// Retrieve the global circuit breaker protecting Foo Service.
CircuitBreaker<Integer> coreFooServiceCircuitBreaker =
    CircuitBreakerForHttpStatusCode.getDefaultHttpStatusCodeCircuitBreakerForKey("fooService");

// Create a wrapper that can handle the FooHttpClientResponse object and delegates
//      circuit breaker events and errors to coreFooServiceCircuitBreaker.
CircuitBreaker<FooHttpClientResponse> fooClientCircuitBreaker =
    new CircuitBreakerDelegate<>(coreFooServiceCircuitBreaker, FooHttpClientResponse::getHttpStatusCode);

// --snip--

// Use fooClientCircuitBreaker when calling Foo Service.
CompletableFuture<FooHttpClientResponse> fooServiceResponse =
    fooClientCircuitBreaker.executeAsyncCall(fooService::executeFooServiceCall);
// ... fooServiceResponse processing goes here.
```
 
**Again, `CircuitBreakerForHttpStatusCode` is simply a helper for a common use case; you do not need to use this class just because you're performing HTTP calls. If `CircuitBreakerForHttpStatusCode` is a natural fit for your use case then it is a convenient choice, but if it's not a good fit then don't try to shoehorn it - creating a custom circuit breaker for HTTP calls exactly to your specifications by using `CircuitBreakerImpl.newBuilder()` is quick and easy.**

## Further Details

Fastbreak is a small project. It only has a few classes and they are all fully javadocced. For further details please see the source code, including javadocs and unit tests.

<a name="license"></a>
## License

Fastbreak is released under the [Apache License, Version 2.0](http://www.apache.org/licenses/LICENSE-2.0)

[travis]:https://travis-ci.org/Nike-Inc/fastbreak
[travis img]:https://api.travis-ci.org/Nike-Inc/fastbreak.svg?branch=master

[license]:LICENSE.txt
[license img]:https://img.shields.io/badge/License-Apache%202-blue.svg

[toc]:#table_of_contents

[akka cb]:http://doc.akka.io/docs/akka/current/common/circuitbreaker.html