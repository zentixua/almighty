package ua.zentix.almighty.bridge;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.CompletableFuture;

/**
 * The one choke point between transports and methods: {@code /rpc}, the MCP endpoint and in-process callers all
 * call {@link #invoke}. Checks that apply to every call (the gate, the deny list, audit) attach here, so no
 * transport can bypass them.
 */
public final class Dispatch {
    private final Map<String, Method> methods;

    public Dispatch(Map<String, Method> methods) {
        this.methods = Map.copyOf(methods);
    }

    /** Call a method as {@code caller}; parse errors throw at once, server-thread errors complete the future. */
    public CompletableFuture<JsonElement> invoke(Caller caller, String name, JsonObject params) throws RpcException {
        Method method = methods.get(name);
        if (method == null) {
            throw RpcException.notFound("No method " + name + "; methods: " + String.join(", ", new TreeSet<>(methods.keySet())));
        }
        return method.call(new Args(params, caller));
    }

    public Set<String> names() {
        return methods.keySet();
    }
}
