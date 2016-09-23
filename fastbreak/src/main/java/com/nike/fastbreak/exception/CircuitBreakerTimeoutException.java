package com.nike.fastbreak.exception;

/**
 * Extension of {@link CircuitBreakerException} that indicates a call took longer than the circuit breaker's call
 * timeout value.
 *
 * @author Nic Munroe
 */
@SuppressWarnings("WeakerAccess")
public class CircuitBreakerTimeoutException extends CircuitBreakerException {

    public CircuitBreakerTimeoutException(String circuitBreakerId, String message) {
        super(circuitBreakerId, message);
    }

    public CircuitBreakerTimeoutException(String circuitBreakerId, String message, Throwable cause) {
        super(circuitBreakerId, message, cause);
    }

    public CircuitBreakerTimeoutException(String circuitBreakerId, Throwable cause) {
        super(circuitBreakerId, cause);
    }
}
