package dev.jobhunter.strategy.aggregator;

/**
 * Thrown when the GlobalMove (the-global-move) session is unconfigured or has
 * been marked dead. Signals that the endpoint requires authentication and
 * cannot be fetched without an operator re-capture of the session cookie
 * (see {@code scripts/globalmove-login.sh}).
 */
public class GlobalMoveSessionException extends RuntimeException {

    public GlobalMoveSessionException(String message) {
        super(message);
    }

    public GlobalMoveSessionException(String message, Throwable cause) {
        super(message, cause);
    }
}
