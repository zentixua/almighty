package ua.zentix.almighty.bridge;

/** Bridge call error: HTTP status and short name for the client, message for the game master. */
public final class RpcException extends Exception {
    private final int status;
    private final String code;
    private com.google.gson.JsonObject details;

    public RpcException(int status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    public static RpcException badRequest(String message) {
        return new RpcException(400, "bad_request", message);
    }

    public static RpcException notFound(String message) {
        return new RpcException(404, "not_found", message);
    }

    public static RpcException conflict(String message) {
        return new RpcException(409, "conflict", message);
    }

    public static RpcException unavailable(String message) {
        return new RpcException(503, "unavailable", message);
    }

    /** Extra fields for the error object (for example the {@code allowed} hint of a policy refusal). */
    public RpcException detail(String key, com.google.gson.JsonElement value) {
        if (details == null) details = new com.google.gson.JsonObject();
        details.add(key, value);
        return this;
    }

    public RpcException detail(String key, String value) {
        return detail(key, new com.google.gson.JsonPrimitive(value));
    }

    public com.google.gson.JsonObject details() {
        return details;
    }

    public int status() {
        return status;
    }

    public String code() {
        return code;
    }
}
