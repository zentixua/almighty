package ua.zentix.almighty.bridge;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

import java.util.ArrayList;
import java.util.List;

/** Параметры вызова: чтение с проверкой, ошибка — {@link RpcException#badRequest} с именем параметра. */
public final class Args {
    private final JsonObject json;

    public Args(JsonObject json) {
        this.json = json == null ? new JsonObject() : json;
    }

    public boolean has(String name) {
        JsonElement e = json.get(name);
        return e != null && !e.isJsonNull();
    }

    public String string(String name) throws RpcException {
        if (!has(name)) throw RpcException.badRequest("Missing parameter " + name);
        return primitive(name, "a string").getAsString();
    }

    public String string(String name, String fallback) throws RpcException {
        return has(name) ? string(name) : fallback;
    }

    public int integer(String name, int min, int max) throws RpcException {
        if (!has(name)) throw RpcException.badRequest("Missing parameter " + name);
        return range(name, primitive(name, "a number"), min, max);
    }

    public int integer(String name, int fallback, int min, int max) throws RpcException {
        return has(name) ? integer(name, min, max) : fallback;
    }

    public long longValue(String name, long fallback, long min, long max) throws RpcException {
        if (!has(name)) return fallback;
        JsonPrimitive p = primitive(name, "a number");
        if (!p.isNumber()) throw RpcException.badRequest(name + ": expected a number");
        double d = p.getAsDouble();
        if (d != Math.rint(d)) throw RpcException.badRequest(name + ": expected an integer");
        long v = p.getAsLong();
        if (v < min || v > max) throw RpcException.badRequest(name + ": must be from " + min + " to " + max);
        return v;
    }

    public double number(String name, double fallback, double min, double max) throws RpcException {
        if (!has(name)) return fallback;
        JsonPrimitive p = primitive(name, "a number");
        if (!p.isNumber()) throw RpcException.badRequest(name + ": expected a number");
        double v = p.getAsDouble();
        if (!(v >= min && v <= max)) throw RpcException.badRequest(name + ": must be from " + min + " to " + max);
        return v;
    }

    public boolean bool(String name, boolean fallback) throws RpcException {
        if (!has(name)) return fallback;
        JsonPrimitive p = primitive(name, "true or false");
        if (!p.isBoolean()) throw RpcException.badRequest(name + ": expected true or false");
        return p.getAsBoolean();
    }

    /** Массив из {@code count} целых ({@code [x, y, z]}, {@code [x, z]}). */
    public int[] ints(String name, int count) throws RpcException {
        if (!has(name)) throw RpcException.badRequest("Missing parameter " + name);
        return ints(name, json.get(name), count);
    }

    public static int[] ints(String name, JsonElement e, int count) throws RpcException {
        if (!e.isJsonArray() || e.getAsJsonArray().size() != count) {
            throw RpcException.badRequest(name + ": expected an array of " + count + " integers");
        }
        int[] out = new int[count];
        for (int i = 0; i < count; i++) {
            JsonElement v = e.getAsJsonArray().get(i);
            if (!v.isJsonPrimitive() || !v.getAsJsonPrimitive().isNumber()) {
                throw RpcException.badRequest(name + ": expected an array of " + count + " integers");
            }
            double d = v.getAsDouble();
            if (d != Math.rint(d) || Math.abs(d) > 1e9) throw RpcException.badRequest(name + ": coordinates must be integers");
            out[i] = (int) d;
        }
        return out;
    }

    public JsonArray array(String name) throws RpcException {
        if (!has(name) || !json.get(name).isJsonArray()) throw RpcException.badRequest(name + ": expected an array");
        return json.getAsJsonArray(name);
    }

    public JsonObject object(String name) throws RpcException {
        if (!has(name) || !json.get(name).isJsonObject()) throw RpcException.badRequest(name + ": expected an object");
        return json.getAsJsonObject(name);
    }

    /** Список строк; одна строка — список из неё. */
    public List<String> strings(String name) throws RpcException {
        if (!has(name)) throw RpcException.badRequest("Missing parameter " + name);
        JsonElement e = json.get(name);
        List<String> out = new ArrayList<>();
        if (e.isJsonPrimitive() && e.getAsJsonPrimitive().isString()) {
            out.add(e.getAsString());
            return out;
        }
        if (!e.isJsonArray()) throw RpcException.badRequest(name + ": expected a string or an array of strings");
        for (JsonElement v : e.getAsJsonArray()) {
            if (!v.isJsonPrimitive() || !v.getAsJsonPrimitive().isString()) {
                throw RpcException.badRequest(name + ": expected an array of strings");
            }
            out.add(v.getAsString());
        }
        return out;
    }

    public JsonObject raw() {
        return json;
    }

    private JsonPrimitive primitive(String name, String expected) throws RpcException {
        JsonElement e = json.get(name);
        if (!e.isJsonPrimitive()) throw RpcException.badRequest(name + ": expected " + expected);
        return e.getAsJsonPrimitive();
    }

    private static int range(String name, JsonPrimitive p, int min, int max) throws RpcException {
        if (!p.isNumber()) throw RpcException.badRequest(name + ": expected a number");
        double d = p.getAsDouble();
        if (d != Math.rint(d)) throw RpcException.badRequest(name + ": expected an integer");
        if (d < min || d > max) throw RpcException.badRequest(name + ": must be from " + min + " to " + max);
        return (int) d;
    }
}
