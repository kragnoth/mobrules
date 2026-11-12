package com.kragnoth.mobrules.rule;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.List;
import java.util.Set;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.TextColor;
import org.jetbrains.annotations.Nullable;

/**
 * Affixes: a rule with {@code "affix"} is never applied on its own; it is a modifier that rules with
 * {@code "affixes"} roll onto mobs. The affix rule's conditions decide which mobs may roll it.
 * <pre>
 * "affix":   { "name": "Vampiric", "weight": 2, "pool": "elite" }
 * "affixes": { "pool": "elite", "count": [1, 3], "rename": true, "color": "yellow" }
 * </pre>
 */
public final class Affix {
    private Affix() {}

    public record Def(String name, double weight, List<String> pools) {
        static Def parse(JsonElement e, String p) {
            if (e.isJsonPrimitive()) return new Def(e.getAsString(), 1, List.of("default"));
            JsonObject o = Json.object(e, p);
            Json.checkKeys(o, p, Set.of("name", "weight", "pool"));
            String n = Json.string(o, "name", p);
            if (n == null) throw new RuleException(p, "needs \"name\" (the word added to the mob's name)");
            List<String> pools = o.has("pool") ? Json.strings(o.get("pool"), p + ".pool") : List.of("default");
            return new Def(n, Math.max(0, Json.number(o, "weight", 1, p)), pools);
        }
    }

    public record Roll(List<String> pools, NumberSpec count, boolean rename, @Nullable TextColor color) {
        static Roll parse(JsonElement e, String p) {
            JsonObject o = Json.object(e, p);
            Json.checkKeys(o, p, Set.of("pool", "count", "rename", "color"));
            List<String> pools = o.has("pool") ? Json.strings(o.get("pool"), p + ".pool") : List.of("default");
            NumberSpec count = o.has("count") ? NumberSpec.parse(o.get("count"), p + ".count") : NumberSpec.constant(1);
            String c = Json.string(o, "color", p);
            TextColor tc = null;
            if (c != null) {
                tc = TextColor.parseColor(c).result().orElse(null);
                if (tc == null) {
                    ChatFormatting f = ChatFormatting.getByName(c);
                    if (f == null || !f.isColor()) throw new RuleException(p + ".color", "unknown color '" + c + "'");
                    tc = TextColor.fromLegacyFormat(f);
                }
            }
            return new Roll(pools, count, Json.bool(o, "rename", true, p), tc);
        }
    }
}
