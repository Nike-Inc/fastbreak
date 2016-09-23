package com.nike.fastbreak.exception;

/**
 * Abstract class indicating a circuit breaker exception. Contains the {@link #circuitBreakerId} of the circuit breaker
 * that threw the exception.
 *
 * @author Nic Munroe
 */
@SuppressWarnings("WeakerAccess")
public abstract class CircuitBreakerException extends RuntimeException {

    public final String circuitBreakerId;

    public CircuitBreakerException(String circuitBreakerId, String message) {
        super(message);
        this.circuitBreakerId = circuitBreakerId;
    }

    public CircuitBreakerException(String circuitBreakerId, String message, Throwable cause) {
        super(message, cause);
        this.circuitBreakerId = circuitBreakerId;
    }

    public CircuitBreakerException(String circuitBreakerId, Throwable cause) {
        super(cause);
        this.circuitBreakerId = circuitBreakerId;
    }
}
