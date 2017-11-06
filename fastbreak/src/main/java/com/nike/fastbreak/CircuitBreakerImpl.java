package com.nike.fastbreak;

import com.nike.fastbreak.exception.CircuitBreakerOpenException;
import com.nike.fastbreak.exception.CircuitBreakerTimeoutException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

/**
 * Implementation of {@link CircuitBreaker} that is modeled after the
 * <a href="http://doc.akka.io/docs/akka/2.4.1/common/circuitbreaker.html">Akka circuit breaker</a> - see that link for
 * more info.
 *
 * <p>See the {@link Builder} (and associated static factory methods {@link #newBuilder()} and {@link
 * #newBuilder(BreakingEventStrategy)}) for an easy way to build instances of this class with the behavior options you
 * want.
 *
 * <p>Details:
 * <ul>
 *     <li>Circuit breaking events are determined by passing the event through {@link #breakingEventStrategy}</li>
 *     <li>
 *         Circuit breaking exceptions are determined by passing the exception through {@link
 *         #breakingExceptionStrategy}
 *     </li>
 *     <li>
 *         After {@link #maxConsecutiveFailuresAllowed} consecutive breaking failures the circuit will be set to OPEN
 *         state, causing all subsequent calls to short circuit and immediately throw a {@link
 *         CircuitBreakerOpenException} as long as the circuit is OPEN.
 *     </li>
 *     <li>
 *         Once opened, the circuit will stay open for {@link #resetTimeoutNanos}, after which a single call will be
 *         allowed through. This is the "half open" state.
 *     </li>
 *     <li>
 *         If the half-open call succeeds then the circuit will be closed again, allowing all calls through. If the
 *         half-open call fails, then the circuit will remain open for another {@link #resetTimeoutNanos}.
 *     </li>
 *     <li>
 *         For {@link #executeAsyncCall(Supplier)} and {@link #executeBlockingCall(Callable)} there's an optional
 *         {@link #callTimeoutNanos}. If the call takes longer than the {@link #callTimeoutNanos} then a {@link
 *         CircuitBreakerTimeoutException} will be thrown and will count against the number of consecutive failures
 *         allowed.
 *     </li>
 * </ul>
 *
 * @param <ET> The event type this circuit breaker knows how to handle. {@link #breakingEventStrategy} is used to
 *              determine whether a given event contributes to the consecutive breaking failures count or not.
 *
 * @author Nic Munroe
 */
@SuppressWarnings({"WeakerAccess", "OptionalUsedAsFieldOrParameterType"})
public class CircuitBreakerImpl<ET> implements CircuitBreaker<ET>, CircuitBreaker.ManualModeTask<ET> {

    private final Logger logger = LoggerFactory.getLogger(this.getClass());

    // Static defaults
    /**
     * Override the default max consecutive failures ({@link #FALLBACK_DEFAULT_MAX_CONSECUTIVE_FAILURES_ALLOWED}) by
     * setting this System Property key to an integer value on JVM startup.
     */
    public static final String DEFAULT_MAX_CONSECUTIVE_FAILURES_ALLOWED_SYSTEM_PROP_KEY =
        "fastbreak.defaultMaxConsecutiveFailuresAllowed";
    /**
     * Override the default reset timeout in seconds ({@link #FALLBACK_DEFAULT_RESET_TIMEOUT_SECONDS}) by setting this
     * System Property key to an integer value on JVM startup.
     */
    public static final String DEFAULT_RESET_TIMEOUT_SECONDS_SYSTEM_PROP_KEY =
        "fastbreak.defaultResetTimeoutInSeconds";

    /**
     * This is the default number of max consecutive failures that will be used if unspecified for a given circuit
     * breaker. It will be set to {@link #DEFAULT_MAX_CONSECUTIVE_FAILURES_ALLOWED_SYSTEM_PROP_KEY} if that System
     * Property is set (and greater than or equal to 1), otherwise it will fallback to {@link
     * #FALLBACK_DEFAULT_MAX_CONSECUTIVE_FAILURES_ALLOWED}.
     */
    public static final int DEFAULT_MAX_CONSECUTIVE_FAILURES_ALLOWED;
    /**
     * This is the default reset timeout value (in seconds) will be used if unspecified for a given circuit
     * breaker. It will be set to {@link #DEFAULT_RESET_TIMEOUT_SECONDS_SYSTEM_PROP_KEY} if that System
     * Property is set (and greater than or equal to 1), otherwise it will fallback to {@link
     * #FALLBACK_DEFAULT_RESET_TIMEOUT_SECONDS}.
     */
    public static final long DEFAULT_RESET_TIMEOUT_SECONDS;

    /**
     * This will be used to set {@link #DEFAULT_MAX_CONSECUTIVE_FAILURES_ALLOWED} if the {@link
     * #DEFAULT_MAX_CONSECUTIVE_FAILURES_ALLOWED_SYSTEM_PROP_KEY} System Property is not set.
     */
    public static final int FALLBACK_DEFAULT_MAX_CONSECUTIVE_FAILURES_ALLOWED = 20;
    /**
     * This will be used to set {@link #DEFAULT_RESET_TIMEOUT_SECONDS} if the {@link
     * #DEFAULT_RESET_TIMEOUT_SECONDS_SYSTEM_PROP_KEY} System Property is not set.
     */
    public static final long FALLBACK_DEFAULT_RESET_TIMEOUT_SECONDS = 15;

    /**
     * The default {@link BreakingEventStrategy} that will be used if not specified for a given circuit breaker
     * instance - assumes all events are successful/healthy events.
     */
    public static final BreakingEventStrategy<?> DEFAULT_BREAKING_EVENT_STRATEGY = event -> false;
    /**
     * The default {@link BreakingExceptionStrategy} that will be used if not specified for a given circuit breaker
     * instance - assumes all exceptions are failure/unhealthy events.
     */
    public static final BreakingExceptionStrategy DEFAULT_BREAKING_EXCEPTION_STRATEGY = throwable -> true;

    // The default ScheduledExecutorService and ExecutorService will not be created until required - that way if your
    //      application never needs the defaults (because you pass in custom ones for each circuit breaker) you won't
    //      have extra threads spun up that will never be used.
    private static ScheduledExecutorService DEFAULT_SCHEDULED_EXECUTOR_SERVICE;
    private static ExecutorService DEFAULT_STATE_CHANGE_NOTIFICATION_EXECUTOR_SERVICE;

    static {
        // Retrieve the configurable defaults from System properties if available, otherwise use hardcoded defaults.
        DEFAULT_MAX_CONSECUTIVE_FAILURES_ALLOWED =
            Math.max(
                Integer.parseInt(
                    System.getProperty(DEFAULT_MAX_CONSECUTIVE_FAILURES_ALLOWED_SYSTEM_PROP_KEY,
                                       String.valueOf(FALLBACK_DEFAULT_MAX_CONSECUTIVE_FAILURES_ALLOWED)
                    )
                ),
                1 // Minimum of 1, no matter what is found in the System Properties.
            );
        DEFAULT_RESET_TIMEOUT_SECONDS =
            Math.max(
                Long.parseLong(
                    System.getProperty(DEFAULT_RESET_TIMEOUT_SECONDS_SYSTEM_PROP_KEY,
                                       String.valueOf(FALLBACK_DEFAULT_RESET_TIMEOUT_SECONDS)
                    )
                ),
                1 // Minimum of 1, no matter what is found in the System Properties.
            );
    }

    // Things that may change during CircuitBreaker execution (either reference changes or object state changes)
    protected int consecutiveFailureCount = 0;
    protected final AtomicBoolean halfOpenAllowSingleCall = new AtomicBoolean(false);
    protected final AtomicReference<ScheduledFuture<?>> halfOpenScheduledFuture = new AtomicReference<>();
    protected State currentState = State.CLOSED;
    protected final List<Runnable> onCloseListeners = new CopyOnWriteArrayList<>();
    protected final List<Runnable> onHalfOpenListeners = new CopyOnWriteArrayList<>();
    protected final List<Runnable> onOpenListeners = new CopyOnWriteArrayList<>();

    // Immutables (set in the constructor)
    protected final ScheduledExecutorService scheduler;
    protected final ExecutorService stateChangeNotificationExecutor;
    protected final long resetTimeoutNanos;
    protected final String resetTimeoutDurationString;
    protected final Optional<Long> callTimeoutNanos;
    protected final int maxConsecutiveFailuresAllowed;
    protected final String id;
    protected final BreakingEventStrategy<ET> breakingEventStrategy;
    protected final BreakingExceptionStrategy breakingExceptionStrategy;

    protected enum State {
        CLOSED, OPEN
    }

    /**
     * Constructor that passes {@link Optional#empty()} to {@link CircuitBreakerImpl#CircuitBreakerImpl(Optional,
     * Optional, Optional, Optional, Optional, Optional, Optional, Optional)} for every argument, creating an instance
     * that uses defaults for everything. See that constructor for details on the defaults that will be used.
     */
    public CircuitBreakerImpl() {
        this(Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
             Optional.empty(), Optional.empty());
    }

    /**
     * The kitchen-sink constructor.
     *
     * @param scheduler The scheduler to use for scheduling half-open timeouts and call timeouts. If this is empty, then
     *                  a default scheduler will be used. Passing in your own can save you some threads if you already
     *                  have a scheduler you're using (e.g. Netty event loop).
     * @param stateChangeNotificationExecutor
     *          An executor that will be used for executing the {@link #onClose(Runnable)}, {@link
     *          #onHalfOpen(Runnable)}, and {@link #onOpen(Runnable)} notifications. If this is empty, then a default
     *          executor will be used. If you want control over the extra threads then you should pass in your own
     *          executor.
     * @param maxConsecutiveFailuresAllowed
     *          The max number of consecutive failures that are allowed before the circuit enters OPEN state. If this is
     *          empty then a default will be used. The default value can be controlled by the {@link
     *          #DEFAULT_MAX_CONSECUTIVE_FAILURES_ALLOWED_SYSTEM_PROP_KEY} System property, or if all else fails a
     *          hardcoded default value will be used ({@link #FALLBACK_DEFAULT_MAX_CONSECUTIVE_FAILURES_ALLOWED}).
     * @param resetTimeout The amount of time to wait after entering OPEN state before allowing a half-open call
     *                     through. If this is empty then a default will be used. The default value can be controlled by
     *                     the {@link #DEFAULT_RESET_TIMEOUT_SECONDS_SYSTEM_PROP_KEY} System property, or if all else
     *                     fails a hardcoded default value will be used
     *                     ({@link #FALLBACK_DEFAULT_RESET_TIMEOUT_SECONDS}).
     * @param callTimeout The amount of time to allow a call to execute (via {@link #executeAsyncCall(Supplier)} or
     *                    {@link #executeBlockingCall(Callable)}) before a {@link CircuitBreakerTimeoutException} will
     *                    be thrown. If this is empty then calls will never timeout.
     * @param id The ID of this circuit breaker. Used in log messages to ID this instance. If this is empty then {@link
     *           CircuitBreaker#DEFAULT_ID} + "-" + {@link UUID#randomUUID()} will be used.
     * @param breakingEventStrategy The function to decide whether an event contributes to the consecutive breaking
     *                              failures count or not.
     * @param breakingExceptionStrategy The function to decide whether an exception contributes to the consecutive
     *                                  breaking failures count or not.
     */
    public CircuitBreakerImpl(Optional<ScheduledExecutorService> scheduler,
                              Optional<ExecutorService> stateChangeNotificationExecutor,
                              Optional<Integer> maxConsecutiveFailuresAllowed,
                              Optional<Duration> resetTimeout,
                              Optional<Duration> callTimeout,
                              Optional<String> id,
                              Optional<BreakingEventStrategy<ET>> breakingEventStrategy,
                              Optional<BreakingExceptionStrategy> breakingExceptionStrategy) {

        this.scheduler = scheduler.orElseGet(this::getDefaultScheduledExecutorService);
        this.stateChangeNotificationExecutor =
            stateChangeNotificationExecutor.orElseGet(this::getDefaultStateChangeNotificationExecutorService);
        this.maxConsecutiveFailuresAllowed =
            maxConsecutiveFailuresAllowed.orElse(DEFAULT_MAX_CONSECUTIVE_FAILURES_ALLOWED);
        this.resetTimeoutNanos = resetTimeout.map(Duration::toNanos)
                                             .orElse(Duration.ofSeconds(DEFAULT_RESET_TIMEOUT_SECONDS).toNanos());
        this.resetTimeoutDurationString = Duration.ofNanos(this.resetTimeoutNanos).toString();
        this.callTimeoutNanos = callTimeout.map(Duration::toNanos);
        this.id = id.orElse(DEFAULT_ID + "-" + UUID.randomUUID().toString());
        //noinspection unchecked
        this.breakingEventStrategy =
            breakingEventStrategy.orElse((BreakingEventStrategy<ET>) DEFAULT_BREAKING_EVENT_STRATEGY);
        this.breakingExceptionStrategy = breakingExceptionStrategy.orElse(DEFAULT_BREAKING_EXCEPTION_STRATEGY);
    }

    /**
     * @return A new blank {@link Builder} for creating a {@link CircuitBreakerImpl} with the options you want.
     */
    public static <E> Builder<E> newBuilder() {
        return new Builder<>();
    }

    /**
     * @return A new {@link Builder} for creating a {@link CircuitBreakerImpl} with the options you want that is
     *          initialized with the given {@link BreakingEventStrategy}.
     */
    public static <E> Builder<E> newBuilder(BreakingEventStrategy<E> breakingEventStrategy) {
        return new Builder<>(breakingEventStrategy);
    }

    protected ScheduledExecutorService getDefaultScheduledExecutorService() {
        synchronized (CircuitBreakerImpl.class) {
            if (DEFAULT_SCHEDULED_EXECUTOR_SERVICE == null) {
                logger.info("Creating default ScheduledExecutorService for CircuitBreaker");
                ScheduledThreadPoolExecutor schedulerToUse = new ScheduledThreadPoolExecutor(8);
                // Set the scheduler to remove the tasks immediately upon cancellation (prevents holding on to memory
                //      that we'd prefer get GC'd immediately).
                schedulerToUse.setRemoveOnCancelPolicy(true);
                DEFAULT_SCHEDULED_EXECUTOR_SERVICE = schedulerToUse;
            }
        }

        return DEFAULT_SCHEDULED_EXECUTOR_SERVICE;
    }

    protected ExecutorService getDefaultStateChangeNotificationExecutorService() {
        synchronized (CircuitBreakerImpl.class) {
            if (DEFAULT_STATE_CHANGE_NOTIFICATION_EXECUTOR_SERVICE == null) {
                logger.info("Creating default state change notification ExecutorService for CircuitBreaker");
                DEFAULT_STATE_CHANGE_NOTIFICATION_EXECUTOR_SERVICE = Executors.newCachedThreadPool();
            }
        }

        return DEFAULT_STATE_CHANGE_NOTIFICATION_EXECUTOR_SERVICE;
    }

    @Override
    public ManualModeTask<ET> newManualModeTask() {
        // This CircuitBreaker implementation does not have any state that matters on a per-task basis, so we are safe
        //      to implement ManualModeTask at the CircuitBreaker level and save the cost of creating new ManualModeTask
        //      objects every time this method is called.
        return this;
    }

    @Override
    public void throwExceptionIfCircuitBreakerIsOpen() throws CircuitBreakerOpenException {
        // If the circuit is closed then there's no need for an exception
        if (State.CLOSED.equals(currentState))
            return;

        // The circuit is open. See if it's half-open or fully open. Half-open should allow a single call through.
        //      Fully open should throw an exception.
        boolean allowThisCallThrough = halfOpenAllowSingleCall.getAndSet(false);
        if (allowThisCallThrough) {
            logger.debug("Allowing a single half-open call through. circuit_breaker_id={}", id);
            // Schedule another half-open timeout check in case this call fails.
            scheduleHalfOpenStateTimeout();
            return;
        }

        // The circuit is fully open - throw the exception
        throw new CircuitBreakerOpenException(
            id, "Cannot make the desired call - the circuit breaker is open. circuit_breaker_id=" + id
        );
    }

    @Override
    public void handleEvent(ET event) {
        try {
            if (isEventACircuitBreakerFailure(event)) {
                // This event is one that might have tripped the circuit breaker.
                processFailureCall();
            }
            else {
                // This event is considered a successful call.
                processSuccessfulCall();
            }
        }
        catch (Throwable t) {
            logger.error(
                "Unexpected exception caught while trying to handleEvent(...). This indicates the CircuitBreaker is "
                + "malfunctioning, but since this method should never throw an exception it will be swallowed.", t
            );
        }
    }

    @Override
    public void handleException(Throwable throwable) {
        try {
            throwable = unwrapAsyncExceptions(throwable);

            // CircuitBreakerOpenExceptions are not considered successes or failures. It's just the status quo while the
            //      circuit is open.
            if (throwable instanceof CircuitBreakerOpenException)
                return;

            if (isExceptionACircuitBreakerFailure(throwable)) {
                // This exception is one that might have tripped the circuit breaker.
                processFailureCall();
            }
            else {
                // This exception is considered a successful call.
                processSuccessfulCall();
            }
        }
        catch (Throwable t) {
            logger.error(
                "Unexpected exception caught while trying to handleException(...). This indicates the CircuitBreaker is "
                + "malfunctioning, but since this method should never throw an exception it will be swallowed.", t
            );
        }
    }

    @Override
    public CircuitBreaker<ET> originatingCircuitBreaker() {
        return this;
    }

    @Override
    public CompletableFuture<ET> executeAsyncCall(Supplier<CompletableFuture<ET>> eventFutureSupplier)
        throws CircuitBreakerOpenException
    {
        // Explode if the circuit breaker is open
        throwExceptionIfCircuitBreakerIsOpen();

        // No CircuitBreakerOpenException was thrown so this call is allowed through.

        // Get the future and wire it up to call the appropriate handle*() method when it finishes.
        CompletableFuture<ET> eventFuture = eventFutureSupplier.get();
        eventFuture.whenComplete((event, error) -> {
            if (error == null)
                handleEvent(event);
            else
                handleException(error);
        });

        // If desired then schedule a timeout for this future in case it takes too long.
        callTimeoutNanos.ifPresent(timeoutNanos -> {
            // Schedule the timeout check
            ScheduledFuture<?> timeoutCheckFuture = scheduler.schedule(() -> {
                if (!eventFuture.isDone()) {
                    eventFuture.completeExceptionally(generateNewCircuitBreakerTimeoutException(timeoutNanos));
                }
            }, timeoutNanos, TimeUnit.NANOSECONDS);

            // Configure the event future to delete the timeout check immediately when it finishes. This is necessary
            //      for the event future to be GC'd immediately. If we don't do this then the event future and all its
            //      memory is stuck waiting for the timeout check future to complete.
            eventFuture.whenComplete((event, error) -> {
                if (!timeoutCheckFuture.isDone())
                    timeoutCheckFuture.cancel(false);
            });
        });

        return eventFuture;
    }

    @Override
    public ET executeBlockingCall(Callable<ET> eventSupplier) throws Exception {
        // Explode if the circuit breaker is open
        throwExceptionIfCircuitBreakerIsOpen();

        // No CircuitBreakerOpenException was thrown so this call is allowed through.

        // Get the event or an exception thrown by the eventSupplier and time how long it takes.
        ET event = null;
        Exception eventException = null;
        long beforeCallNanos = System.nanoTime();
        try {
            // Time the call so we know whether we need to throw a CircuitBreakerTimeoutException or not.
            event = eventSupplier.call();
        }
        catch (Exception ex) {
            eventException = ex;
        }
        long afterCallNanos = System.nanoTime();

        // Determine if the call took longer than the callTimeout.
        Exception timeoutException = null;
        if (callTimeoutNanos.isPresent()) {
            long timeoutNanos = callTimeoutNanos.get();
            if ((afterCallNanos - beforeCallNanos) > timeoutNanos) {
                // The call took longer than our callTimeout value.
                //noinspection ThrowableResultOfMethodCallIgnored
                timeoutException = generateNewCircuitBreakerTimeoutException(timeoutNanos);
            }
        }

        // Determine if we need to handleException(). Call timeouts take precedence.
        Exception exceptionToHandle = (timeoutException == null) ? eventException : timeoutException;
        if (exceptionToHandle != null) {
            handleException(exceptionToHandle);
            throw exceptionToHandle;
        }

        // Call completed under the timeout limit (or there was no timeout limit), and the call did not throw an
        //      exception. Handle the event and return it.
        handleEvent(event);
        return event;
    }

    @Override
    public CircuitBreakerImpl<ET> onClose(Runnable listener) {
        onCloseListeners.add(listener);
        return this;
    }

    @Override
    public CircuitBreakerImpl<ET> onHalfOpen(Runnable listener) {
        onHalfOpenListeners.add(listener);
        return this;
    }

    @Override
    public CircuitBreakerImpl<ET> onOpen(Runnable listener) {
        onOpenListeners.add(listener);
        return this;
    }

    @Override
    public String getId() {
        return id;
    }

    protected CircuitBreakerTimeoutException generateNewCircuitBreakerTimeoutException(long timeoutNanos) {
        Duration duration = Duration.ofNanos(timeoutNanos);
        return new CircuitBreakerTimeoutException(
            id,
            "The CompletableFuture took longer than the CircuitBreaker timeout of " + duration.toString()
            + ". circuit_breaker_id=" + id
        );
    }

    protected void processSuccessfulCall() {
        boolean stateChanged = false;
        synchronized (this) {
            // Reset the consecutive failure counter, make sure we're set to CLOSED state, and capture whether this is a
            //      state change or not.
            consecutiveFailureCount = 0;
            if (!State.CLOSED.equals(currentState)) {
                logger.info(
                    "Setting circuit breaker state to CLOSED after successful call. "
                    + "circuit_breaker_state_changed_to={}, circuit_breaker_id={}",
                    State.CLOSED.name(), id
                );
                currentState = State.CLOSED;
                // Cancel any existing half-open scheduled timer.
                ScheduledFuture<?> oldHalfOpenScheduledFuture = halfOpenScheduledFuture.getAndSet(null);
                if (oldHalfOpenScheduledFuture != null && !oldHalfOpenScheduledFuture.isDone()) {
                    logger.debug(
                        "Cancelling half-open timeout check now that the circuit is closed. circuit_breaker_id={}", id
                    );
                    oldHalfOpenScheduledFuture.cancel(false);
                }

                stateChanged = true;
            }
        }

        if (stateChanged) {
            // On this particular call we went from OPEN to CLOSED. Notify listeners.
            notifyOnCloseListeners();
        }
    }

    protected void processFailureCall() {
        boolean stateChanged = false;
        synchronized (this) {
            // Increment the consecutive failure counter and then see if the breaker is tripped.
            consecutiveFailureCount++;
            if (consecutiveFailureCount >= maxConsecutiveFailuresAllowed) {
                // We are over the limit. Make sure our state is set to OPEN, and capture whether this is a state change
                //      or not.
                if (!State.OPEN.equals(currentState)) {
                    logger.info(
                        "Tripping circuit breaker OPEN after {} consecutive failure calls. "
                        + "circuit_breaker_state_changed_to={}, circuit_breaker_id={}",
                        consecutiveFailureCount, State.OPEN.name(), id
                    );
                    halfOpenAllowSingleCall.set(false);
                    currentState = State.OPEN;
                    // Schedule the half-open timeout
                    scheduleHalfOpenStateTimeout();
                    stateChanged = true;
                }
            }
        }

        if (stateChanged) {
            // On this particular call we went from CLOSED to OPEN, so the circuit is tripped. Notify listeners.
            notifyOnOpenListeners();
        }
    }

    protected void scheduleHalfOpenStateTimeout() {
        logger.debug("Scheduling half-open state to occur in {}. circuit_breaker_id={}",
                     resetTimeoutDurationString, id);

        // Schedule a half-open check after the reset timeout
        halfOpenScheduledFuture.set(scheduler.schedule(() -> {
            boolean stateChanged = false;
            synchronized (this) {
                if (State.CLOSED.equals(currentState)) {
                    // The circuit has closed since this timer started. We have nothing to do.
                    logger.debug(
                        "Half-open state check - breaker is already CLOSED. Nothing to do. circuit_breaker_id={}", id);
                }
                else {
                    // The circuit is still open. Allow a single half-open call.
                    logger.info(
                        "Half-open state check - breaker is OPEN. The next call will be allowed through in a half-open "
                        + "state. circuit_breaker_state_changed_to={}, circuit_breaker_id={}", "HALF_OPEN", id
                    );
                    halfOpenAllowSingleCall.set(true);
                    stateChanged = true;
                }
            }

            if (stateChanged) {
                notifyOnHalfOpenListeners();
            }
        }, resetTimeoutNanos, TimeUnit.NANOSECONDS));
    }

    protected void notifyOnCloseListeners() {
        onCloseListeners.forEach(stateChangeNotificationExecutor::execute);
    }

    protected void notifyOnHalfOpenListeners() {
        onHalfOpenListeners.forEach(stateChangeNotificationExecutor::execute);
    }

    protected void notifyOnOpenListeners() {
        onOpenListeners.forEach(stateChangeNotificationExecutor::execute);
    }

    protected boolean isEventACircuitBreakerFailure(ET event) {
        return breakingEventStrategy.isEventACircuitBreakerFailure(event);
    }

    protected boolean isExceptionACircuitBreakerFailure(Throwable throwable) {
        return breakingExceptionStrategy.isExceptionACircuitBreakerFailure(throwable);
    }

    protected Throwable unwrapAsyncExceptions(Throwable error) {
        if (error == null || error.getCause() == null)
            return error;

        if (error instanceof CompletionException || error instanceof ExecutionException) {
            error = error.getCause();
            // Recursively unwrap until we get something that is not unwrappable
            error = unwrapAsyncExceptions(error);
        }

        return error;
    }

    /**
     * Builder class for {@link CircuitBreakerImpl}.
     *
     * @param <E> The event type the resulting circuit breaker knows how to handle. {@link #breakingEventStrategy} is
     *            used to determine whether a given event contributes to the consecutive breaking failures count or not.
     */
    public static class Builder<E> {
        private ScheduledExecutorService scheduler;
        private ExecutorService stateChangeNotificationExecutor;
        private Integer maxConsecutiveFailuresAllowed;
        private Duration resetTimeout;
        private Duration callTimeout;
        private String id;
        private BreakingEventStrategy<E> breakingEventStrategy;
        private BreakingExceptionStrategy breakingExceptionStrategy;

        public Builder() {
            // Do nothing
        }

        public Builder(BreakingEventStrategy<E> breakingEventStrategy) {
            withBreakingEventStrategy(breakingEventStrategy);
        }

        /**
         * @param scheduler The scheduler to use for scheduling half-open timeouts and call timeouts. If this is null,
         *                  then a default scheduler will be used. Passing in your own can save you some threads if you
         *                  already have a scheduler you're using (e.g. Netty event loop).
         * @return this builder instance.
         */
        public Builder<E> withScheduler(ScheduledExecutorService scheduler) {
            this.scheduler = scheduler;
            return this;
        }

        /**
         * @param stateChangeNotificationExecutor
         *          An executor that will be used for executing the {@link #onClose(Runnable)}, {@link
         *          #onHalfOpen(Runnable)}, and {@link #onOpen(Runnable)} notifications. If this is null, then a
         *          default executor will be used. If you want control over the extra threads then you should pass in
         *          your own executor.
         * @return this builder instance.
         */
        public Builder<E> withStateChangeNotificationExecutor(ExecutorService stateChangeNotificationExecutor) {
            this.stateChangeNotificationExecutor = stateChangeNotificationExecutor;
            return this;
        }

        /**
         * @param maxConsecutiveFailuresAllowed
         *          The max number of consecutive failures that are allowed before the circuit enters OPEN state. If
         *          this is null then a default will be used. The default value can be controlled by the {@link
         *          #DEFAULT_MAX_CONSECUTIVE_FAILURES_ALLOWED_SYSTEM_PROP_KEY} System property, or if all else fails a
         *          hardcoded default value will be used ({@link #FALLBACK_DEFAULT_MAX_CONSECUTIVE_FAILURES_ALLOWED}).
         * @return this builder instance.
         */
        public Builder<E> withMaxConsecutiveFailuresAllowed(Integer maxConsecutiveFailuresAllowed) {
            this.maxConsecutiveFailuresAllowed = maxConsecutiveFailuresAllowed;
            return this;
        }

        /**
         * @param resetTimeout The amount of time to wait after entering OPEN state before allowing a half-open call
         *                     through. If this is null then a default will be used. The default value can be
         *                     controlled by the {@link #DEFAULT_RESET_TIMEOUT_SECONDS_SYSTEM_PROP_KEY} System property,
         *                     or if all else fails a hardcoded default value will be used ({@link
         *                     #FALLBACK_DEFAULT_RESET_TIMEOUT_SECONDS}).
         * @return this builder instance.
         */
        public Builder<E> withResetTimeout(Duration resetTimeout) {
            this.resetTimeout = resetTimeout;
            return this;
        }

        /**
         * @param callTimeout The amount of time to allow a call to execute (via {@link #executeAsyncCall(Supplier)} or
         *                    {@link #executeBlockingCall(Callable)}) before a {@link CircuitBreakerTimeoutException}
         *                    will be thrown. If this is null then calls will never timeout.
         * @return this builder instance.
         */
        public Builder<E> withCallTimeout(Duration callTimeout) {
            this.callTimeout = callTimeout;
            return this;
        }

        /**
         * @param id The ID of the circuit breaker. Used in log messages to ID this instance. If this is empty then
         *          {@link #DEFAULT_ID} will be used.
         * @return this builder instance.
         */
        public Builder<E> withId(String id) {
            this.id = id;
            return this;
        }

        /**
         * @param breakingEventStrategy The function to decide whether an event contributes to the consecutive breaking
         *                              failures count or not.
         * @return this builder instance.
         */
        public Builder<E> withBreakingEventStrategy(BreakingEventStrategy<E> breakingEventStrategy) {
            this.breakingEventStrategy = breakingEventStrategy;
            return this;
        }

        /**
         * @param breakingExceptionStrategy The function to decide whether an exception contributes to the consecutive
         *                                  breaking failures count or not.
         * @return this builder instance.
         */
        public Builder<E> withBreakingExceptionStrategy(BreakingExceptionStrategy breakingExceptionStrategy) {
            this.breakingExceptionStrategy = breakingExceptionStrategy;
            return this;
        }

        /**
         * @return The {@link CircuitBreakerImpl} created from the options set on this builder.
         */
        public CircuitBreakerImpl<E> build() {
            return new CircuitBreakerImpl<>(
                Optional.ofNullable(scheduler), Optional.ofNullable(stateChangeNotificationExecutor),
                Optional.ofNullable(maxConsecutiveFailuresAllowed), Optional.ofNullable(resetTimeout),
                Optional.ofNullable(callTimeout), Optional.ofNullable(id),
                Optional.ofNullable(breakingEventStrategy), Optional.ofNullable(breakingExceptionStrategy)
            );
        }
    }
}
