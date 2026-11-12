package com.kragnoth.mobrules.rule;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.Set;

/**
 * A number in a rule. Accepted forms:
 * <ul>
 *   <li>{@code 2.5} - constant</li>
 *   <li>{@code [1, 3]} or {@code {"min": 1, "max": 3}} - uniform random in the range</li>
 *   <li>{@code {"base": 1, "per_day": 0.05, "per_1000_blocks": 0.1, "per_local_difficulty": 0.2,
 *       "spread": [-0.1, 0.1], "floor": 1, "cap": 3}} - scales with world day, distance from spawn
 *       and local difficulty, plus an optional random spread, clamped to floor/cap</li>
 * </ul>
 */
public final class NumberSpec {
    private static final Set<String> KEYS = Set.of("min", "max", "base", "per_day", "per_1000_blocks",
            "per_local_difficulty", "spread", "floor", "cap");

    private final double base, perDay, per1000, perDifficulty, spreadMin, spreadMax, floor, cap;

    private NumberSpec(double base, double perDay, double per1000, double perDifficulty,
                       double spreadMin, double spreadMax, double floor, double cap) {
        this.base = base;
        this.perDay = perDay;
        this.per1000 = per1000;
        this.perDifficulty = perDifficulty;
        this.spreadMin = spreadMin;
        this.spreadMax = spreadMax;
        this.floor = floor;
        this.cap = cap;
    }

    public static NumberSpec constant(double v) {
        return new NumberSpec(v, 0, 0, 0, 0, 0, Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY);
    }

    public static NumberSpec parse(JsonElement e, String path) {
        if (Json.isNumber(e)) return constant(e.getAsDouble());
        if (e != null && e.isJsonArray()) {
            double[] r = pair(e.getAsJsonArray(), path);
            return new NumberSpec(0, 0, 0, 0, r[0], r[1], Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY);
        }
        JsonObject o = Json.object(e, path);
        Json.checkKeys(o, path, KEYS);
        boolean formula = o.has("base") || o.has("per_day") || o.has("per_1000_blocks") || o.has("per_local_difficulty");
        if (!formula) {
            if (!o.has("min") || !o.has("max")) throw new RuleException(path, "a range needs both 'min' and 'max'");
            return new NumberSpec(0, 0, 0, 0, Json.number(o, "min", 0, path), Json.number(o, "max", 0, path),
                    Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY);
        }
        double sMin = 0, sMax = 0;
        if (o.has("spread")) {
            if (!o.get("spread").isJsonArray()) throw new RuleException(path + ".spread", "expected [min, max]");
            double[] r = pair(o.getAsJsonArray("spread"), path + ".spread");
            sMin = r[0];
            sMax = r[1];
        }
        return new NumberSpec(Json.number(o, "base", 0, path), Json.number(o, "per_day", 0, path),
                Json.number(o, "per_1000_blocks", 0, path), Json.number(o, "per_local_difficulty", 0, path),
                sMin, sMax, Json.number(o, "floor", Double.NEGATIVE_INFINITY, path),
                Json.number(o, "cap", Double.POSITIVE_INFINITY, path));
    }

    private static double[] pair(JsonArray a, String path) {
        if (a.size() != 2 || !Json.isNumber(a.get(0)) || !Json.isNumber(a.get(1))) throw new RuleException(path, "expected [min, max]");
        double lo = a.get(0).getAsDouble(), hi = a.get(1).getAsDouble();
        return new double[]{Math.min(lo, hi), Math.max(lo, hi)};
    }

    public double eval(MobContext c) {
        double v = base;
        if (perDay != 0) v += perDay * c.day();
        if (per1000 != 0) v += per1000 * c.distanceFromSpawn() / 1000.0;
        if (perDifficulty != 0) v += perDifficulty * c.localDifficulty();
        if (spreadMax > spreadMin) v += spreadMin + c.random.nextDouble() * (spreadMax - spreadMin);
        else v += spreadMin;
        return Math.max(floor, Math.min(cap, v));
    }

    public int evalInt(MobContext c) {
        return (int) Math.round(eval(c));
    }
}
