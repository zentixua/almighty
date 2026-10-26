package ua.zentix.almighty.bridge;

import com.google.gson.JsonElement;

import java.util.concurrent.CompletableFuture;

/**
 * Bridge method. Called on the HTTP thread: parses parameters there and accesses the world only through
 * the server thread ({@code GmServer.onMain}) or a job within the tick budget. Parse errors throw
 * {@link RpcException} immediately; server-thread errors complete the future exceptionally.
 */
@FunctionalInterface
public interface Method {
    CompletableFuture<JsonElement> call(Args args) throws RpcException;
}
