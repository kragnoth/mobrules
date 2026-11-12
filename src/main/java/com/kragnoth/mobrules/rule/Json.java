package com.kragnoth.mobrules.rule;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.jetbrains.annotations.Nullable;

/** Small helpers for reading rule JSON with clear error messages. */
public final class Json {
    private Json() {}

    public static JsonObject object(JsonElement e, String path) {
        if (e == null || !e.isJsonObject()) throw new RuleException(path, "expected an object");
        return e.getAsJsonObject();
    }

    /** A string, or an array of strings. */
    public static List<String> strings(JsonElement e, String path) {
        List<String> out = new ArrayList<>();
        if (e == null || e.isJsonNull()) return out;
        if (e.isJsonPrimitive()) {
            out.add(e.getAsString());
            return out;
        }
        if (!e.isJsonArray()) throw new RuleException(path, "expected a string or a list of strings");
        JsonArray arr = e.getAsJsonArray();
        for (int i = 0; i < arr.size(); i++) {
            JsonElement x = arr.get(i);
            if (!x.isJsonPrimitive()) throw new RuleException(path + "[" + i + "]", "expected a string");
            out.add(x.getAsString());
        }
        return out;
    }

    /** An object, or a list of objects. */
    public static List<JsonElement> list(JsonElement e) {
        List<JsonElement> out = new ArrayList<>();
        if (e == null || e.isJsonNull()) return out;
        if (e.isJsonArray()) e.getAsJsonArray().forEach(out::add);
        else out.add(e);
        return out;
    }

    @Nullable
    public static Boolean bool(JsonObject o, String key, String path) {
        JsonElement e = o.get(key);
        if (e == null || e.isJsonNull()) return null;
        if (!e.isJsonPrimitive() || !e.getAsJsonPrimitive().isBoolean()) throw new RuleException(path + "." + key, "expected true or false");
        return e.getAsBoolean();
    }

    public static boolean bool(JsonObject o, String key, boolean def, String path) {
        Boolean b = bool(o, key, path);
        return b == null ? def : b;
    }

    public static double number(JsonObject o, String key, double def, String path) {
        JsonElement e = o.get(key);
        if (e == null || e.isJsonNull()) return def;
        if (!e.isJsonPrimitive() || !e.getAsJsonPrimitive().isNumber()) throw new RuleException(path + "." + key, "expected a number");
        return e.getAsDouble();
    }

    @Nullable
    public static String string(JsonObject o, String key, String path) {
        JsonElement e = o.get(key);
        if (e == null || e.isJsonNull()) return null;
        if (!e.isJsonPrimitive()) throw new RuleException(path + "." + key, "expected a string");
        return e.getAsString();
    }

    public static boolean isNumber(JsonElement e) {
        return e != null && e.isJsonPrimitive() && ((JsonPrimitive) e).isNumber();
    }

    /** Throws if the object contains keys we do not understand (catches typos early). */
    public static void checkKeys(JsonObject o, String path, Set<String> allowed) {
        for (Map.Entry<String, JsonElement> entry : o.entrySet()) {
            if (!allowed.contains(entry.getKey()) && !entry.getKey().startsWith("_")) {
                throw new RuleException(path, "unknown key '" + entry.getKey() + "' (allowed: " + String.join(", ", new java.util.TreeSet<>(allowed)) + ")");
            }
        }
    }
}
