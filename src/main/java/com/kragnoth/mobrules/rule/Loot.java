package com.kragnoth.mobrules.rule;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.storage.loot.LootTable;

/**
 * <pre>
 * "loot": {
 *   "replace": false,                                   // true clears the normal drops first
 *   "drops": [ { "item": "minecraft:emerald", "count": [1, 3], "chance": 0.5, "player_kill_only": true } ],
 *   "tables": ["minecraft:entities/wither_skeleton"],    // extra loot table rolls
 *   "xp": { "multiply": 2, "add": 5 }
 * }
 * </pre>
 * Drop entries accept everything an equipment item does (enchants, names, components...).
 */
public final class Loot {
    public boolean replace;
    public final List<ItemSpec> drops = new ArrayList<>();
    public final List<ResourceKey<LootTable>> tables = new ArrayList<>();
    public double xpMultiply = 1, xpAdd = 0;

    public static Loot parse(JsonElement e, RegistryAccess registries, String path) {
        Loot l = new Loot();
        JsonObject o = Json.object(e, path);
        Json.checkKeys(o, path, Set.of("replace", "drops", "tables", "xp"));
        l.replace = Json.bool(o, "replace", false, path);
        int i = 0;
        for (JsonElement x : Json.list(o.get("drops"))) l.drops.add(ItemSpec.parse(x, registries, path + ".drops[" + i++ + "]"));
        for (String t : Json.strings(o.get("tables"), path + ".tables")) {
            l.tables.add(ResourceKey.create(Registries.LOOT_TABLE, ResourceLocation.parse(t)));
        }
        if (o.has("xp")) {
            JsonObject x = Json.object(o.get("xp"), path + ".xp");
            Json.checkKeys(x, path + ".xp", Set.of("multiply", "add"));
            l.xpMultiply = Json.number(x, "multiply", 1, path + ".xp");
            l.xpAdd = Json.number(x, "add", 0, path + ".xp");
        }
        return l;
    }
}
