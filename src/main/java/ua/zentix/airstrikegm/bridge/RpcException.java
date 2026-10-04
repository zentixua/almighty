package ua.zentix.airstrikegm.bridge;

/** Ошибка вызова моста: код HTTP и короткое имя для клиента, текст — для ведущего. */
public final class RpcException extends Exception {
    private final int status;
    private final String code;

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

    public int status() {
        return status;
    }

    public String code() {
        return code;
    }
}
