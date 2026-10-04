package ua.zentix.airstrikegm.bridge;

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
        if (!has(name)) throw RpcException.badRequest("Нет параметра " + name);
        return primitive(name, "строка").getAsString();
    }

    public String string(String name, String fallback) throws RpcException {
        return has(name) ? string(name) : fallback;
    }

    public int integer(String name, int min, int max) throws RpcException {
        if (!has(name)) throw RpcException.badRequest("Нет параметра " + name);
        return range(name, primitive(name, "число"), min, max);
    }

    public int integer(String name, int fallback, int min, int max) throws RpcException {
        return has(name) ? integer(name, min, max) : fallback;
    }

    public long longValue(String name, long fallback, long min, long max) throws RpcException {
        if (!has(name)) return fallback;
        JsonPrimitive p = primitive(name, "число");
        if (!p.isNumber()) throw RpcException.badRequest(name + ": ожидается число");
        double d = p.getAsDouble();
        if (d != Math.rint(d)) throw RpcException.badRequest(name + ": ожидается целое");
        long v = p.getAsLong();
        if (v < min || v > max) throw RpcException.badRequest(name + ": от " + min + " до " + max);
        return v;
    }

    public double number(String name, double fallback, double min, double max) throws RpcException {
        if (!has(name)) return fallback;
        JsonPrimitive p = primitive(name, "число");
        if (!p.isNumber()) throw RpcException.badRequest(name + ": ожидается число");
        double v = p.getAsDouble();
        if (!(v >= min && v <= max)) throw RpcException.badRequest(name + ": от " + min + " до " + max);
        return v;
    }

    public boolean bool(String name, boolean fallback) throws RpcException {
        if (!has(name)) return fallback;
        JsonPrimitive p = primitive(name, "true или false");
        if (!p.isBoolean()) throw RpcException.badRequest(name + ": ожидается true или false");
        return p.getAsBoolean();
    }

    /** Массив из {@code count} целых ({@code [x, y, z]}, {@code [x, z]}). */
    public int[] ints(String name, int count) throws RpcException {
        if (!has(name)) throw RpcException.badRequest("Нет параметра " + name);
        return ints(name, json.get(name), count);
    }

    public static int[] ints(String name, JsonElement e, int count) throws RpcException {
        if (!e.isJsonArray() || e.getAsJsonArray().size() != count) {
            throw RpcException.badRequest(name + ": ожидается массив из " + count + " целых");
        }
        int[] out = new int[count];
        for (int i = 0; i < count; i++) {
            JsonElement v = e.getAsJsonArray().get(i);
            if (!v.isJsonPrimitive() || !v.getAsJsonPrimitive().isNumber()) {
                throw RpcException.badRequest(name + ": ожидается массив из " + count + " целых");
            }
            double d = v.getAsDouble();
            if (d != Math.rint(d) || Math.abs(d) > 1e9) throw RpcException.badRequest(name + ": координаты — целые числа");
            out[i] = (int) d;
        }
        return out;
    }

    public JsonArray array(String name) throws RpcException {
        if (!has(name) || !json.get(name).isJsonArray()) throw RpcException.badRequest(name + ": ожидается массив");
        return json.getAsJsonArray(name);
    }

    public JsonObject object(String name) throws RpcException {
        if (!has(name) || !json.get(name).isJsonObject()) throw RpcException.badRequest(name + ": ожидается объект");
        return json.getAsJsonObject(name);
    }

    /** Список строк; одна строка — список из неё. */
    public List<String> strings(String name) throws RpcException {
        if (!has(name)) throw RpcException.badRequest("Нет параметра " + name);
        JsonElement e = json.get(name);
        List<String> out = new ArrayList<>();
        if (e.isJsonPrimitive() && e.getAsJsonPrimitive().isString()) {
            out.add(e.getAsString());
            return out;
        }
        if (!e.isJsonArray()) throw RpcException.badRequest(name + ": ожидается строка или массив строк");
        for (JsonElement v : e.getAsJsonArray()) {
            if (!v.isJsonPrimitive() || !v.getAsJsonPrimitive().isString()) {
                throw RpcException.badRequest(name + ": ожидается массив строк");
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
        if (!e.isJsonPrimitive()) throw RpcException.badRequest(name + ": ожидается " + expected);
        return e.getAsJsonPrimitive();
    }

    private static int range(String name, JsonPrimitive p, int min, int max) throws RpcException {
        if (!p.isNumber()) throw RpcException.badRequest(name + ": ожидается число");
        double d = p.getAsDouble();
        if (d != Math.rint(d)) throw RpcException.badRequest(name + ": ожидается целое");
        if (d < min || d > max) throw RpcException.badRequest(name + ": от " + min + " до " + max);
        return (int) d;
    }
}
