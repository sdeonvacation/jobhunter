package dev.jobhub.linkedin;

public class McpClientException extends RuntimeException {

    private final int errorCode;

    public McpClientException(String message) {
        super(message);
        this.errorCode = 0;
    }

    public McpClientException(String message, int errorCode) {
        super(message);
        this.errorCode = errorCode;
    }

    public McpClientException(String message, Throwable cause) {
        super(message, cause);
        this.errorCode = 0;
    }

    public int getErrorCode() {
        return errorCode;
    }
}
