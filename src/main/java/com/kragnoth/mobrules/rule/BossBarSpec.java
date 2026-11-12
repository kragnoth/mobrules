package com.kragnoth.mobrules.rule;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.Locale;
import java.util.Set;
import net.minecraft.world.BossEvent;

/**
 * {@code "boss_bar": {"color": "red", "style": "notched_10", "range": 40, "darken_sky": false}}
 * or just {@code "boss_bar": true}. Shows the mob's name and health to players within range.
 */
public final class BossBarSpec {
    public BossEvent.BossBarColor color = BossEvent.BossBarColor.RED;
    public BossEvent.BossBarOverlay style = BossEvent.BossBarOverlay.PROGRESS;
    public double range = 40;
    public boolean darkenSky;

    public static BossBarSpec parse(JsonElement e, String path) {
        BossBarSpec b = new BossBarSpec();
        if (e.isJsonPrimitive() && e.getAsJsonPrimitive().isBoolean()) return e.getAsBoolean() ? b : null;
        JsonObject o = Json.object(e, path);
        Json.checkKeys(o, path, Set.of("color", "style", "range", "darken_sky"));
        String c = Json.string(o, "color", path);
        if (c != null) b.color = BossEvent.BossBarColor.byName(c.toLowerCase(Locale.ROOT));
        String s = Json.string(o, "style", path);
        if (s != null) b.style = BossEvent.BossBarOverlay.byName(s.toLowerCase(Locale.ROOT));
        b.range = Json.number(o, "range", 40, path);
        b.darkenSky = Json.bool(o, "darken_sky", false, path);
        return b;
    }
}
