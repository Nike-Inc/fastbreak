package com.nike.fastbreak.exception;

/**
 * Extension of {@link CircuitBreakerException} that indicates a call was attempted against the circuit breaker when the
 * circuit was OPEN.
 *
 * @author Nic Munroe
 */
@SuppressWarnings("WeakerAccess")
public class CircuitBreakerOpenException extends CircuitBreakerException {

    public CircuitBreakerOpenException(String circuitBreakerId, String message) {
        super(circuitBreakerId, message);
    }

    public CircuitBreakerOpenException(String circuitBreakerId, String message, Throwable cause) {
        super(circuitBreakerId, message, cause);
    }

    public CircuitBreakerOpenException(String circuitBreakerId, Throwable cause) {
        super(circuitBreakerId, cause);
    }
}
