package com.nike.fastbreak;

import com.nike.fastbreak.exception.CircuitBreakerOpenException;
import com.nike.fastbreak.exception.CircuitBreakerTimeoutException;

import org.slf4j.LoggerFactory;

import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Interface describing the API for a circuit breaker. A circuit breaker is typically used to help stabilize distributed
 * systems when there are problems by detecting when a downstream dependency is unhealthy and preventing calls to that
 * service for a while to give it a chance to become healthy again.
 *
 * <p><b>See {@link CircuitBreakerImpl}</b> for a concrete implementation of this interface that covers many use cases.
 *
 * <p><b>CIRCUIT BREAKER LIFECYCLE:</b>
 * <ul>
 *     <li>
 *         A circuit breaker is in charge of determining whether something indicates an unhealthy downstream service.
 *         The output of a call is either a normal result (an "event"), an exception, or the call may timeout without a
 *         result (timing out is an option that some circuit breaker implementations may or may not support). But an
 *         event doesn't always mean a successful healthy call, and an exception doesn't always mean an unhealthy call.
 *         For example when making HTTP requests a 4xx error status code means the caller did something wrong not that
 *         the service is unhealthy, while a 5xx error status code generally means an unhealthy service. Similarly the
 *         HTTP client may throw exceptions instead when it sees an error status code, so an exception representing a
 *         4xx error might be considered healthy while an exception representing a 5xx error might be considered
 *         unhealthy. Or at least that may be true generally but not always, which is why {@link CircuitBreaker} allows
 *         for flexibility in determining breaking/unhealthy calls.
 *     </li>
 *     <li>
 *         After enough unhealthy calls, a circuit breaker may decide the downstream service is unhealthy enough that it
 *         needs to be protected or that callers should fail-fast rather than waiting for a service that is slow and
 *         timing out, and the circuit may change to an OPEN state.
 *     </li>
 *     <li>
 *         When in an OPEN state, all calls passing through the circuit breaker will short-circuit-fail immediately with
 *         a {@link CircuitBreakerOpenException} before the call is executed.
 *     </li>
 *     <li>
 *         After a circuit enters the OPEN state it will periodically allow one or more calls through to see if the
 *         downstream service has stabilized and become healthy again. This is sometimes referred to as a HALF-OPEN
 *         state. The time between periodic checks is usually a configurable value per circuit breaker.
 *     </li>
 *     <li>
 *         When the circuit breaker detects that the HALF-OPEN calls indicate the service is healthy again then the
 *         circuit will change back to the CLOSED state, allowing all calls through.
 *     </li>
 * </ul>
 *
 * <p><b>USAGE:</b>
 * <br>
 * There are three ways to use this API to protect calls with circuit breaking capabilities. All three
 * methods/procedures contribute to the circuit breaker's behavior in the same way, so they can be mixed and matched as
 * needed by your application - they are simply suited to different scenarios.
 * <ul>
 *     <li>
 *         <b>Asynchronous future-based calls using {@link #executeAsyncCall(Supplier)}:</b> This is the preferred
 *         method when possible. You simply need to call this method and you'll receive the full circuit breaker
 *         treatment, complete with short-circuiting call timeouts (if applicable). The drawback to this method is you
 *         have to be aware of threading issues - i.e. will the future run in a large enough threadpool that it won't
 *         block waiting for an open thread?
 *     </li>
 *     <li>
 *         <b>Synchronous blocking calls using {@link #executeBlockingCall(Callable)}:</b> This is another all-in-one
 *         approach to using the circuit breaker similar to {@link #executeAsyncCall(Supplier)}, however it will block
 *         the calling thread while the call is performed. Another drawback is that while {@link
 *         com.nike.fastbreak.exception.CircuitBreakerTimeoutException} will be thrown when the call takes longer than
 *         the call timeout (if there is one), the blocking nature of the method means it is not short circuiting - you
 *         will only receive the timeout exception after the entire call has finished, no matter how long it takes. You
 *         can avoid this and receive the short circuiting benefits by wrapping the call in a {@link CompletableFuture}
 *         and using {@link #executeAsyncCall(Supplier)} instead.
 *     </li>
 *     <li>
 *         <b>Manual execution using {@link ManualModeTask#throwExceptionIfCircuitBreakerIsOpen()}, {@link
 *         ManualModeTask#handleEvent(Object)}, and {@link ManualModeTask#handleException(Throwable)}:</b> See the
 *         class-level javadocs for {@link ManualModeTask} for usage details.
 *         <p><b>WARNING:</b> The usage contract for manual mode must be carefully adhered to or you risk messing up the
 *         state of the circuit breaker. Again, please see the class-level javadocs for {@link ManualModeTask} for
 *         details.
 *     </li>
 * </ul>
 *
 * <p><b>STATE CHANGE NOTIFICATIONS:</b> <br> You can be notified of state changes for circuit breakers by registering
 * callback listeners via {@link #onClose(Runnable)}, {@link #onHalfOpen(Runnable)}, and {@link #onOpen(Runnable)}.
 *
 * <p><b>REUSING THE SAME CIRCUIT BREAKER IN DIFFERENT SITUATIONS</b>
 * <br>You may find yourself in a situation where you have one logical downstream system that should be protected by a
 * single circuit breaker, but multiple calls that produce different events and therefore cannot share the same {@link
 * CircuitBreaker} instance. You can use {@link CircuitBreakerDelegate} to accomplish this by having multiple circuit
 * breakers that all funnel to the same underlying instance that tallies the call successes/failures and controls the
 * circuit state. See {@link CircuitBreakerDelegate} for more details.
 *
 * @param <ET> The event type this circuit breaker knows how to handle. A {@link BreakingEventStrategy} is typically
 *             used to determine whether a given event can contribute to the circuit breaker opening or not.
 * @author Nic Munroe
 */
@SuppressWarnings("WeakerAccess")
public interface CircuitBreaker<ET> {

    /**
     * An all-in-one method to execute an async future call with circuit breaker protection.
     * <ul>
     *     <li>
     *         A {@link CircuitBreakerOpenException} will be thrown immediately if the circuit is OPEN before the
     *         supplier's {@link Supplier#get()} method is called so that you don't incur the cost of executing
     *         the supplier if the circuit is OPEN.
     *     </li>
     *     <li>
     *         The returned future will be {@link CompletableFuture#completeExceptionally(Throwable)} with a {@link
     *         com.nike.fastbreak.exception.CircuitBreakerTimeoutException} if this circuit breaker is configured with a
     *         call timeout and the future takes longer than the timeout to complete.
     *     </li>
     *     <li>
     *         When the future completes for any reason, the event or exception result will contribute to this circuit
     *         breaker's OPEN/CLOSED state calculations and transitions. This will happen automatically without any
     *         intervention from you.
     *     </li>
     * </ul>
     * NOTE: A {@link RuntimeException} other than {@link CircuitBreakerOpenException} may be thrown if the {@code
     * eventFutureSupplier} argument throws one. These supplier-generated errors will not count toward the
     * healthy/unhealthy OPEN/CLOSED state of the circuit breaker - they are considered a bug in your application, not
     * the downstream service call being protected by the circuit breaker. Only exceptions generated by the future will
     * count towards the circuit breaking logic. <b>TO PREVENT UNEXPECTED PROBLEMS MAKE SURE YOUR {@code
     * eventFutureSupplier} NEVER THROWS ANY EXCEPTIONS!</b>
     *
     * @param eventFutureSupplier A {@link Supplier} that provides the future to execute. This supplier will not be run
     *                            if the circuit is OPEN and a {@link CircuitBreakerOpenException} is thrown.
     * @return The {@link CompletableFuture} that was supplied by {@code eventFutureSupplier} after the OPEN circuit
     * check passed and the future was instrumented with post-call circuit breaker logic and timeout check (if this
     * circuit breaker instance is configured for call timeouts). The circuit breaker protection logic is very fast to
     * apply so this method should return immediately.
     * @throws CircuitBreakerOpenException if the circuit is OPEN when this method is called.
     */
    CompletableFuture<ET> executeAsyncCall(Supplier<CompletableFuture<ET>> eventFutureSupplier)
        throws CircuitBreakerOpenException;

    /**
     * (<b>SIDE NOTE:</b> Due to the drawbacks of this synchronous blocking method you may want to consider wrapping
     * your {@code eventSupplier} logic in a {@link CompletableFuture} and calling {@link #executeAsyncCall(Supplier)}
     * instead.)
     *
     * <p>This is an all-in-one method to execute a synchronous blocking call with circuit breaker protection.
     * <ul>
     *     <li>
     *         A {@link CircuitBreakerOpenException} will be thrown immediately if the circuit is OPEN.
     *     </li>
     *     <li>
     *         A {@link CircuitBreakerTimeoutException} will be thrown if this circuit breaker is configured with a call
     *         timeout and the {@code eventSupplier} takes longer than the timeout to complete.
     *         <p><b>WARNING: Given the synchronous blocking nature of this method you will not receive the timeout
     *         exception until *after* the supplier finishes, even if it takes much longer than the call timeout value
     *         to complete.</b>
     *     </li>
     *     <li>
     *         When the {@code eventSupplier} completes for any reason the event or exception result will contribute to
     *         this circuit breaker's OPEN/CLOSED state calculations and transitions. This will happen automatically
     *         without any intervention from you. Note that if the {@code eventSupplier} takes longer than the call
     *         timeout to complete then the {@link CircuitBreakerTimeoutException} is used to contribute to the circuit
     *         breaker's state instead of the actual result of the call (which is ignored in that case).
     *     </li>
     * </ul>
     * NOTE: An exception other than {@link CircuitBreakerOpenException} and {@link CircuitBreakerTimeoutException} may
     * be thrown if the {@code eventFutureSupplier} argument throws one when executed. Unlike {@link
     * #executeAsyncCall(Supplier)} these supplier-generated errors <b>will</b> count toward the healthy/unhealthy
     * OPEN/CLOSED state of the circuit breaker - there is no way to know whether the exception is due to a bug in your
     * application or the downstream call so we have to assume it's part of the circuit-breaker-protected call.
     * Therefore make sure your {@code eventSupplier} only throws exceptions that should contribute to the circuit
     * breaker state.
     *
     * @param eventSupplier The synchronous/blocking call to execute. This will not be run if the circuit is OPEN and a
     *                      {@link CircuitBreakerOpenException} is thrown.
     * @return The event that was supplied by {@code eventSupplier} after the OPEN circuit check passed and the
     * post-call circuit breaker logic and timeout check were performed (if this circuit breaker instance is configured
     * for call timeouts). Since this method is blocking it will not return until after {@code eventSupplier}
     * completes.
     * @throws CircuitBreakerOpenException    if the circuit is OPEN when this method is called.
     * @throws CircuitBreakerTimeoutException if this circuit breaker is configured to support call timeouts and the
     *              {@code eventSupplier} takes longer than the call timeout value to complete. <b>Given the synchronous
     *              blocking nature of this method you will not receive this exception until after the supplier
     *              finishes, even if it takes much longer than the call timeout. These exceptions are passed through
     *              the circuit breaker logic and will count towards circuit breaker state changes.</b>
     * @throws Exception if {@code eventSupplier} throws an exception. These exceptions are passed through the circuit
     *                   breaker logic and will count towards circuit breaker state changes.
     */
    @SuppressWarnings("DuplicateThrows")
    ET executeBlockingCall(Callable<ET> eventSupplier)
        throws CircuitBreakerOpenException, CircuitBreakerTimeoutException, Exception;

    /**
     * This method is used to protect a task with this circuit breaker (and have the task's result contribute to this
     * circuit breaker's state) in a manual/callback style, where you control and are fully responsible for the circuit
     * breaker lifecycle for the task. This requires carefully writing your code to <b>guarantee</b> that the {@link
     * ManualModeTask} contract is followed - if you fail to follow the manual mode contract then you risk causing the
     * circuit breaker's state to be incorrect, potentially leading to circuits that are always stuck in an OPEN or
     * CLOSED state, or any number of other odd/subtle bugs.
     *
     * <p>That said, this manual/callback mode system allows you to protect tasks with a circuit breaker in situations
     * that otherwise couldn't be protected (e.g. when the result of a task is communicated to you via asynchronous
     * callback). It provides you maximum flexibility, and the price to pay for that flexibility is that it requires
     * careful usage on your part.
     *
     * <p><b>See the class-level javadocs of {@link ManualModeTask} for usage details.</b>
     *
     * @return A new {@link ManualModeTask} for protecting a task with this circuit breaker and having that task's
     * result contribute to this circuit breaker's state (again - {@link #executeAsyncCall(Supplier)} or {@link
     * #executeBlockingCall(Callable)} is preferred over this manual mode if possible, but if those all-in-one methods
     * can't be used for a task then manual mode does work well).
     */
    ManualModeTask<ET> newManualModeTask();

    /**
     * The given {@link Runnable} will be used as a notification callback whenever this circuit breaker's state changes
     * to CLOSED.
     */
    CircuitBreaker<ET> onClose(Runnable listener);

    /**
     * The given {@link Runnable} will be used as a notification callback whenever this circuit breaker allows a
     * HALF-OPEN check call through.
     */
    CircuitBreaker<ET> onHalfOpen(Runnable listener);

    /**
     * The given {@link Runnable} will be used as a notification callback whenever this circuit breaker's state changes
     * to OPEN.
     */
    CircuitBreaker<ET> onOpen(Runnable listener);

    /**
     * Interface describing the logic that determines whether or not an event is a "circuit breaker failure" that
     * indicates the downstream service is potentially unhealthy. In java 8 these can be generated easily using lambdas,
     * e.g.: {@code event -> (event == null || !event.isSuccessful())}
     *
     * <p><b>IMPORTANT NOTE:</b> Implementations of this interface should *NEVER* throw an exception of any kind. Always
     * do null checks and make sure no other exceptions can be thrown either.
     *
     * @param <E> The event type that this instance knows how to handle.
     */
    @FunctionalInterface
    interface BreakingEventStrategy<E> {

        /**
         * @return true if the given event indicates a failure/unhealthy call, false if it indicates a
         * successful/healthy call.
         */
        boolean isEventACircuitBreakerFailure(E event);
    }

    /**
     * Interface describing the logic that determines whether or not an exception is a "circuit breaker failure" that
     * indicates the downstream service is potentially unhealthy. In java 8 these can be generated easily using lambdas,
     * e.g.: {@code ex -> true}
     *
     * <p><b>IMPORTANT NOTE:</b> Implementations of this interface should *NEVER* throw an exception of any kind. Always
     * do null checks and make sure no other exceptions can be thrown either.
     */
    @FunctionalInterface
    interface BreakingExceptionStrategy {

        /**
         * @return true if the given exception indicates a failure/unhealthy call, false if it indicates a
         * successful/healthy call.
         */
        boolean isExceptionACircuitBreakerFailure(Throwable throwable);
    }

    /**
     * This is an interface for a "manual mode" or "callback mode" task. It allows you to protect a task with a circuit
     * breaker and have that task's result contribute back to the circuit breaker's state in a fully decoupled way where
     * you control everything - and are therefore fully responsible for the circuit breaker lifecycle in relation to the
     * task. This means that if you don't carefully construct your code to guarantee that the circuit breaker's
     * lifecycle is handled properly for a task (e.g. an unexpected exception slips through your code that doesn't have
     * a finally block and causes {@link #handleEvent(Object)} or {@link #handleException(Throwable)} to be skipped when
     * the task is complete) then the circuit breaker's state might get messed up - the circuit breaker might get
     * permanently stuck in an OPEN or CLOSED state, or some other odd/subtle bugs might creep in.
     *
     * <p>Given the potential for mistakes this manual procedure should be used with care, however it provides maximum
     * flexibility and allows you to do circuit breaking in any scenario, including situations where {@link
     * #executeAsyncCall(Supplier)} or {@link #executeBlockingCall(Callable)} are not options, e.g. when the result of a
     * call is communicated to you via asynchronous callback.
     *
     * <p>Typical usage procedure would look something like the following:
     * <pre>
     * <ul>
     *      <li>
     *          Call the relevant circuit breaker's {@link #newManualModeTask()} to get a handle on a {@link
     *          ManualModeTask}, which will be used to perform the circuit breaker lifecycle for the task.
     *          <p>NOTE: A {@link ManualModeTask} should never be reused - it is intended to be used only once. Each
     *          execution of a task should call {@link #newManualModeTask()} to get a new {@link ManualModeTask}.
     *      </li>
     *      <li>
     *          Call {@link #throwExceptionIfCircuitBreakerIsOpen()} before executing the
     *          circuit-breaker-protected task to give the circuit breaker a chance to fail fast if it is in an OPEN
     *          state.
     *          <p>NOTE: This conceptually demarcates the start of the task being protected by the circuit breaker.
     *      </li>
     *      <li>
     *          Collect the result of the task. If it is an exception pass it in to {@link #handleException(Throwable)},
     *          otherwise pass in the result event to {@link #handleEvent(Object)}. These {@code handle*(...)} methods
     *          will determine whether the result indicates a healthy or unhealthy service and will contribute to the
     *          circuit breaker's state, triggering OPEN/CLOSED state changes appropriately.
     *          <p>NOTE: This conceptually demarcates the end of the task being protected by the circuit breaker.
     *          After you call one of these methods the {@link ManualModeTask} should be considered completed and you
     *          should not interact with it again.
     *      </li>
     *      <li>
     *          Note that this manual procedure cannot detect call timeouts for you. If you want call timeout
     *          functionality during the manual procedure you will need to detect and handle them yourself. Just make
     *          sure that you pass the error to {@link #handleException(Throwable)} if you cancel the call due to a
     *          timeout so that it can contribute to the circuit breaker's state.
     *      </li>
     * </ul>
     * </pre>
     * <b>NOTE:</b> This entire procedure must be redone for every execution of a task! Do not reuse a {@link
     * ManualModeTask} returned by {@link #newManualModeTask()} for multiple task executions - the procedure should
     * only be followed once, and then any further task executions should start over with a fresh {@link
     * #newManualModeTask()} call. Furthermore, {@code TaskB} should never call any of the {@link ManualModeTask}
     * methods for {@code TaskA} and vice versa - the usage procedure follows a single task execution instance from
     * beginning to end and does not cross-contaminate.
     *
     * @param <ET>
     *     The event type of the circuit breaker this manual mode task came from. It is the event type the circuit
     *     breaker (and therefore this manual mode task) knows how to handle. A {@link BreakingEventStrategy} is
     *     typically used to determine whether a given event can contribute to the circuit breaker opening or not.
     */
    interface ManualModeTask<ET> {

        /**
         * This method is called before executing a manual task, and will throw a {@link CircuitBreakerOpenException} if
         * the circuit is in an OPEN state. This is the mechanism for failing fast and short-circuiting before making a
         * call to an unhealthy service. If the circuit is CLOSED (healthy) or this given call is allowed through for a
         * HALF-OPEN check then nothing will happen. No other exception should be thrown from this method.
         *
         * <p><b>NOTE: Do *not* call this method unless you're committed to doing the full manual procedure described in
         * the {@link ManualModeTask} class-level javadocs, including calling {@link #handleEvent(Object)} or {@link
         * #handleException(Throwable)} with the result of the task.</b> This is important because if the circuit
         * breaker decides your call to this method is a HALF-OPEN check that should be allowed through and you fail to
         * notify it of the result of the call then the circuit may stay OPEN when it should be CLOSED, and it won't get
         * a chance to do another check until the next HALF-OPEN timeout passes. <b>For the same reasons you should not
         * call this method as a check to see if the circuit breaker is OPEN or CLOSED - use the {@link
         * #onOpen(Runnable)}, {@link #onHalfOpen(Runnable)}, and {@link #onClose(Runnable)} listeners to track a
         * circuit breaker's state.</b>
         *
         * @throws CircuitBreakerOpenException
         *     if the circuit is OPEN.
         */
        void throwExceptionIfCircuitBreakerIsOpen() throws CircuitBreakerOpenException;

        /**
         * A convenience method for converting a different event type into the event type this circuit breaker knows how
         * to handle and then calling {@link #handleEvent(Object)} on it. This method should never throw any exception
         * under any circumstances - if an unexpected exception occurs it will be caught and logged, but not
         * propagated.
         *
         * @param event
         *     The event of a type that this circuit breaker cannot natively handle.
         * @param eventConverter
         *     A function that converts the other-typed event to the type that this circuit breaker can natively
         *     handle.
         * @param <O>
         *     The type of the non-native event.
         */
        default <O> void handleEvent(O event, Function<O, ET> eventConverter) {
            try {
                handleEvent(eventConverter.apply(event));
            }
            catch (Throwable t) {
                LoggerFactory.getLogger(CircuitBreaker.class).error(
                    "Unexpected exception caught while trying to handleEvent(...) with an eventConverter. This"
                    + " indicates the CircuitBreaker is malfunctioning, but since this method should never throw an "
                    + "exception it will be swallowed.",
                    t
                );
            }
        }

        /**
         * This method is called after a circuit-breaker-protected manual task has completed without an exception being
         * thrown. This method is responsible for determining whether or not the event is a failure/unhealthy event or a
         * success/healthy event (it must be one or the other) and handles any OPEN/CLOSED circuit state changes
         * resulting from the event. This method should never throw any exception under any circumstances - if an
         * unexpected exception occurs it should be caught and logged, but not propagated.
         *
         * @param event
         *     The result of the call - may indicate either a healthy/successful call or unhealthy/failure call
         *     depending on this circuit breaker's logic.
         */
        void handleEvent(ET event);

        /**
         * This method is called after a circuit-breaker-protected manual task has completed with an exception being
         * thrown. This method is responsible for determining whether or not the exception is a failure/unhealthy error
         * or a success/healthy error (it must be one or the other) and handles any OPEN/CLOSED circuit state changes
         * resulting from the exception. This method should never throw any exception under any circumstances - if an
         * unexpected exception occurs it will be caught and logged, but not propagated.
         *
         * <p>NOTE: It is recommended that implementations of this method "unwrap" any {@link
         * java.util.concurrent.CompletionException} or {@link java.util.concurrent.ExecutionException} errors by
         * recursively digging through the {@link Throwable#getCause()} until you find the first exception that is not a
         * {@code CompletionException} or {@code ExecutionException} (or until the cause is null and you can't unwrap
         * anymore), and use that unwrapped exception to do the unhealthy/healthy determination. When a future fails it
         * often wraps the real exception in a {@code CompletionException} or {@code ExecutionException}, and that's
         * generally not helpful for a {@link BreakingExceptionStrategy} to have to deal with.
         *
         * <p>ALSO NOTE: {@link CircuitBreakerOpenException} should generally not be considered a success/healthy or
         * failure/unhealthy. It's just the status quo while the circuit is open and should therefore not contribute to
         * any circuit breaker state changes. Most circuit breaker implementations should simply ignore this exception
         * type entirely.
         *
         * @param throwable
         *     The exception result of the call - may indicate either a healthy/successful call or unhealthy/failure
         *     call depending on this circuit breaker's logic.
         */
        void handleException(Throwable throwable);

        /**
         * @return The {@link CircuitBreaker} that generated this {@link ManualModeTask}.
         */
        CircuitBreaker<ET> originatingCircuitBreaker();

    }
}
