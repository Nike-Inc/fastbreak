package com.nike.fastbreak;

import com.nike.fastbreak.exception.CircuitBreakerOpenException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * This class is a {@link CircuitBreaker} that allows you to delegate events of a specific type to a different {@link
 * CircuitBreaker} that doesn't have the ability to naturally handle those events. It does this by taking in the {@link
 * #delegate} circuit breaker that should be used as the source of truth for this instance and an {@link
 * #eventConverter} that knows how to convert events of this instance's event type to the delegate's event type. All
 * {@link CircuitBreaker} methods are supported and ultimately funnel through the {@link #delegate}, so events of this
 * instance's event type will contribute to the delegate's circuit breaker state. This means if the delegate is OPEN
 * then this instance will be OPEN and if the delegate is CLOSED then this instance will be CLOSED, etc, and events and
 * exceptions handled here will affect the delegate as well.
 *
 * <p>NOTE: Exceptions that are thrown when executing {@link #eventConverter} will <b>not</b> be passed on to {@link
 * #delegate} to be handled as failure/unhealthy or successful/healthy calls. Those errors would indicate a problem with
 * your application, not the downstream service, so they are ignored wherever possible. The one exception to this rule
 * is {@link #executeBlockingCall(Callable)}, where errors thrown by {@link #eventConverter} cannot be distinguished
 * from any other error for technical reasons. <b>Bottom line:</b> avoid any problems or unexpected behavior by making
 * sure your {@link #eventConverter} never throws exceptions (whenever possible).
 *
 * @param <ET> The event type of this wrapper circuit breaker instance.
 * @param <Del_ET> The event type of the underlying delegate circuit breaker instance.
 *
 * @author Nic Munroe
 */
@SuppressWarnings("WeakerAccess")
public class CircuitBreakerDelegate<ET, Del_ET> implements CircuitBreaker<ET> {

    private static final Logger logger = LoggerFactory.getLogger(CircuitBreakerDelegate.class);

    protected final CircuitBreaker<Del_ET> delegate;
    protected final Function<ET, Del_ET> eventConverter;

    /**
     * Creates a new instance.
     *
     * @param delegate       The {@link CircuitBreaker} that this instance should delegate to.
     * @param eventConverter The converter function that translates this instance's event type to the delegate's event
     *                       type. NOTE: Exceptions that are thrown when executing {@link #eventConverter} will
     *                       <b>not</b> be passed on to {@link #delegate} to be handled as failure/unhealthy or
     *                       successful/healthy calls. Those errors would indicate a problem with your application, not
     *                       the downstream service, so they are ignored wherever possible. The one exception to this
     *                       rule is {@link #executeBlockingCall(Callable)}, where errors thrown by {@link
     *                       #eventConverter} cannot be distinguished from any other error for technical reasons.
     *                       <b>Bottom line:</b> avoid any problems or unexpected behavior by making sure your {@link
     *                       #eventConverter} never throws exceptions (whenever possible).
     */
    public CircuitBreakerDelegate(CircuitBreaker<Del_ET> delegate, Function<ET, Del_ET> eventConverter) {
        this.delegate = delegate;
        this.eventConverter = eventConverter;
    }

    @Override
    public void throwExceptionIfCircuitBreakerIsOpen() throws CircuitBreakerOpenException {
        delegate.throwExceptionIfCircuitBreakerIsOpen();
    }

    @Override
    public void handleEvent(ET event) {
        try {
            delegate.handleEvent(eventConverter.apply(event));
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
            delegate.handleException(throwable);
        }
        catch (Throwable t) {
            logger.error(
                "Unexpected exception caught while trying to handleException(...). This indicates the CircuitBreaker is "
                + "malfunctioning, but since this method should never throw an exception it will be swallowed.", t
            );
        }
    }

    @Override
    public CompletableFuture<ET> executeAsyncCall(Supplier<CompletableFuture<ET>> eventFutureSupplier) {
        final EventHolder<ET> origEventHolder = new EventHolder<>();

        // Create a new supplier that executes the eventFutureSupplier argument, stores the result for later, and
        // converts it to the necessary type for the delegate.
        Supplier<CompletableFuture<Del_ET>> coreSupplier = () ->
            eventFutureSupplier.get().thenApplyAsync(origEvent -> {
                origEventHolder.event = origEvent;
                return eventConverter.apply(origEvent);
        });

        // Do the delegate call.
        CompletableFuture<Del_ET> delegateFuture = delegate.executeAsyncCall(coreSupplier);

        // Return the delegateFuture, but modified to expose the original event rather than the delegate event.
        return delegateFuture.thenApply(delegateResult -> origEventHolder.event);
    }

    @Override
    public ET executeBlockingCall(Callable<ET> eventSupplier) throws Exception {
        final EventHolder<ET> origEventHolder = new EventHolder<>();

        // Create a new Callable that executes the eventSupplier argument, stores the result for later, and converts
        // it to the necessary type for the delegate.
        Callable<Del_ET> coreSupplier = () -> {
            ET origEvent = eventSupplier.call();
            origEventHolder.event = origEvent;
            return eventConverter.apply(origEvent);
        };

        // Do the delegate call.
        delegate.executeBlockingCall(coreSupplier);

        // Return the original event.
        return origEventHolder.event;
    }

    @Override
    public CircuitBreakerDelegate<ET, Del_ET> onClose(Runnable listener) {
        delegate.onClose(listener);
        return this;
    }

    @Override
    public CircuitBreakerDelegate<ET, Del_ET> onHalfOpen(Runnable listener) {
        delegate.onHalfOpen(listener);
        return this;
    }

    @Override
    public CircuitBreakerDelegate<ET, Del_ET> onOpen(Runnable listener) {
        delegate.onOpen(listener);
        return this;
    }

    protected static class EventHolder<T> {
        public T event = null;
    }
}
