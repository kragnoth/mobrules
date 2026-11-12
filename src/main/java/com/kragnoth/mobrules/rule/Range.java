package com.kragnoth.mobrules.rule;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.Set;

/** An inclusive min/max test. {@code {"min": 0, "max": 40}}, {@code [0, 40]} or a bare number (exact). */
public record Range(double min, double max) {
    public static Range parse(JsonElement e, String path) {
        if (Json.isNumber(e)) return new Range(e.getAsDouble(), e.getAsDouble());
        if (e.isJsonArray() && e.getAsJsonArray().size() == 2) {
            return new Range(e.getAsJsonArray().get(0).getAsDouble(), e.getAsJsonArray().get(1).getAsDouble());
        }
        JsonObject o = Json.object(e, path);
        Json.checkKeys(o, path, Set.of("min", "max"));
        return new Range(Json.number(o, "min", Double.NEGATIVE_INFINITY, path), Json.number(o, "max", Double.POSITIVE_INFINITY, path));
    }

    public boolean test(double v) {
        return v >= min && v <= max;
    }

    /** For time of day: a range like 13000..1000 wraps past midnight. */
    public boolean testWrapping(double v) {
        return min <= max ? test(v) : (v >= min || v <= max);
    }
}
