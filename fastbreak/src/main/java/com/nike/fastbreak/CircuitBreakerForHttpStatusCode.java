package com.nike.fastbreak;

import com.nike.fastbreak.exception.CircuitBreakerTimeoutException;

import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ScheduledExecutorService;
import java.util.function.Supplier;

/**
 * Simple implementation of {@link CircuitBreaker} intended for the generic HTTP call case - it handles integer events
 * meant to represent the HTTP response status code, and considers any status code greater than or equal to 500 to be a
 * circuit breaking/unhealthy call. Exceptions fall through to the default {@link CircuitBreaker} behavior, so all
 * exceptions are considered circuit breaking/unhealthy calls. 4xx errors indicate a problem with the call, not the
 * called service, so they are not considered unhealthy.
 *
 * <p>This class also provides some static methods for retrieving instances based on a key. These methods are thread
 * safe so you can use them in a multithreaded environment to always retrieve the same circuit breaker instance for a
 * given String key (e.g. the host being called, or a specific endpoint).
 *
 * <p>NOTE: If you need different event logic than {@link #DEFAULT_HTTP_RESPONSE_BREAKING_EVENT_STRATEGY} then you
 * should create your own instance of {@link CircuitBreakerImpl} and pass in the logic you need.
 *
 * @author Nic Munroe
 */
@SuppressWarnings({"WeakerAccess", "OptionalUsedAsFieldOrParameterType"})
public class CircuitBreakerForHttpStatusCode extends CircuitBreakerImpl<Integer> {

    /**
     * The event logic used for all instances of this class for determining if a status code indicates a
     * failure/unhealthy call. Status codes greater than or equal to 500 are considered failure/unhealthy calls, all
     * others are considered successful/healthy. 4xx errors indicate a problem with the call, not the called service, so
     * they are not considered unhealthy.
     */
    public static final BreakingEventStrategy<Integer> DEFAULT_HTTP_RESPONSE_BREAKING_EVENT_STRATEGY =
        statusCode -> (statusCode == null || statusCode >= 500);

    private static final ConcurrentMap<String, CircuitBreaker<Integer>> defaultKeyToCircuitBreakerMap =
        new ConcurrentHashMap<>();

    /**
     * Constructor that passes {@link Optional#empty()} to {@link
     * CircuitBreakerForHttpStatusCode#CircuitBreakerForHttpStatusCode(Optional, Optional, Optional, Optional, Optional,
     * Optional)} for every argument, creating an instance that uses defaults for everything. See that constructor for
     * details on the defaults that will be used.
     */
    public CircuitBreakerForHttpStatusCode() {
        this(Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
             Optional.empty());
    }

    /**
     * The kitchen-sink constructor.
     *
     * @param scheduler The scheduler to use for scheduling half-open timeouts and call timeouts. If this is empty, then
     *                  a default scheduler will be used. Passing in your own can save you some threads if you already
     *                  have a scheduler you're using (e.g. Netty event loop).
     * @param stateChangeNotificationExecutor
     *              An executor that will be used for executing the {@link #onClose(Runnable)}, {@link
     *              #onHalfOpen(Runnable)}, and {@link #onOpen(Runnable)} notifications. If this is empty, then a
     *              default executor will be used. If you want control over the extra threads then you should pass in
     *              your own executor.
     * @param maxConsecutiveFailuresAllowed
     *              The max number of consecutive failures that are allowed before the circuit enters OPEN state. If
     *              this is empty then a default will be used. The default value can be controlled by the {@link
     *              #DEFAULT_MAX_CONSECUTIVE_FAILURES_ALLOWED_SYSTEM_PROP_KEY} System property, or if all else fails a
     *              hardcoded default value will be used.
     * @param resetTimeout The amount of time to wait after entering OPEN state before allowing a half-open call
     *                     through. If this is empty then a default will be used. The default value can be controlled by
     *                     the {@link #DEFAULT_RESET_TIMEOUT_SECONDS_SYSTEM_PROP_KEY} System property, or if all else
     *                     fails a hardcoded default value will be used.
     * @param callTimeout The amount of time to allow a call to execute (via {@link #executeAsyncCall(Supplier)} or
     *                    {@link #executeBlockingCall(Callable)}) before a {@link CircuitBreakerTimeoutException} will
     *                    be thrown. If this is empty then calls will never timeout.
     * @param id The ID of this circuit breaker. Used in log messages to ID this instance. If this is empty then {@link
     *           #DEFAULT_ID} will be used.
     */
    public CircuitBreakerForHttpStatusCode(Optional<ScheduledExecutorService> scheduler,
                                           Optional<ExecutorService> stateChangeNotificationExecutor,
                                           Optional<Integer> maxConsecutiveFailuresAllowed,
                                           Optional<Duration> resetTimeout,
                                           Optional<Duration> callTimeout,
                                           Optional<String> id) {
        super(scheduler,
              stateChangeNotificationExecutor,
              maxConsecutiveFailuresAllowed,
              resetTimeout,
              callTimeout,
              id,
              Optional.of(DEFAULT_HTTP_RESPONSE_BREAKING_EVENT_STRATEGY),
              Optional.empty());
    }

    /**
     * A convenience method for calling {@link #getDefaultHttpStatusCodeCircuitBreakerForKey(String, Optional,
     * Optional)} with empty Optionals (thus requesting default values).
     *
     * @return The {@link CircuitBreakerForHttpStatusCode} instance associated with the given key (a new one will be
     * created if necessary). Subsequent calls with the same key will always return the same circuit breaker instance.
     * This method is thread safe.
     */
    public static CircuitBreaker<Integer> getDefaultHttpStatusCodeCircuitBreakerForKey(String key) {
        return getDefaultHttpStatusCodeCircuitBreakerForKey(key, Optional.empty(), Optional.empty());
    }

    /**
     * @param key The key associated with the desired circuit breaker instance.
     * @param scheduler The scheduler you want the circuit breaker to use, or empty if you want the default. <b>This
     *                  only matters the first time a circuit breaker for a given key is requested - subsequent calls
     *                  will use the already-generated circuit breaker and this argument will be ignored.</b>
     * @param stateChangeNotificationExecutor The state change notification executor you want the circuit breaker to
     *                                        use, or empty if you want the default. <b>This only matters the first time
     *                                        a circuit breaker for a given key is requested - subsequent calls will use
     *                                        the already-generated circuit breaker and this argument will be
     *                                        ignored.</b>
     * @return The {@link CircuitBreakerForHttpStatusCode} instance associated with the given key (a new one will be
     * created if necessary). Subsequent calls with the same key will always return the same circuit breaker instance.
     * This method is thread safe.
     */
    public static CircuitBreaker<Integer> getDefaultHttpStatusCodeCircuitBreakerForKey(
        String key,
        Optional<ScheduledExecutorService> scheduler,
        Optional<ExecutorService> stateChangeNotificationExecutor
    ) {
        String keyToUse = (key == null) ? "" : key;

        // Grab the default CircuitBreaker for the given key from our ConcurrentMap, creating a new circuit breaker for
        //      this key if one does not already exist.
        return defaultKeyToCircuitBreakerMap.computeIfAbsent(keyToUse, k ->
            new CircuitBreakerForHttpStatusCode(
                scheduler,
                stateChangeNotificationExecutor,
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.of("default-http-status-cb-for-" + keyToUse)
            ));
    }
}
