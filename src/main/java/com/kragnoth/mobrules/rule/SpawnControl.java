package com.kragnoth.mobrules.rule;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.phys.AABB;

/**
 * Spawn control, checked when the game is about to spawn a mob (before it enters the world).
 * <pre>
 * "spawn": {
 *   "block": true,                                    // matching mobs never spawn
 *   "max_nearby": { "count": 6, "radius": 48 },       // or only while fewer than 6 of this type are within 48 blocks
 *   "types": ["natural", "spawner", "patrol"]         // which spawns this applies to (default: all world spawns)
 * }
 * </pre>
 * The rule's normal conditions decide which spawns are affected. Commands, spawn eggs and breeding
 * are never blocked unless listed in "types".
 */
public final class SpawnControl {
    private static final Set<MobSpawnType> WORLD_SPAWNS = Set.of(MobSpawnType.NATURAL, MobSpawnType.SPAWNER,
            MobSpawnType.STRUCTURE, MobSpawnType.PATROL, MobSpawnType.REINFORCEMENT, MobSpawnType.EVENT,
            MobSpawnType.TRIAL_SPAWNER, MobSpawnType.JOCKEY);

    public boolean block;
    public int maxCount = -1;
    public double radius = 48;
    public Set<MobSpawnType> types = WORLD_SPAWNS;

    public static SpawnControl parse(JsonElement e, String path) {
        SpawnControl s = new SpawnControl();
        JsonObject o = Json.object(e, path);
        Json.checkKeys(o, path, Set.of("block", "max_nearby", "types"));
        s.block = Json.bool(o, "block", false, path);
        if (o.has("max_nearby")) {
            JsonObject m = Json.object(o.get("max_nearby"), path + ".max_nearby");
            Json.checkKeys(m, path + ".max_nearby", Set.of("count", "radius"));
            s.maxCount = (int) Json.number(m, "count", 8, path + ".max_nearby");
            s.radius = Json.number(m, "radius", 48, path + ".max_nearby");
        }
        if (o.has("types")) {
            List<String> names = Json.strings(o.get("types"), path + ".types");
            Set<MobSpawnType> set = new HashSet<>();
            for (String n : names) {
                try {
                    set.add(MobSpawnType.valueOf(n.toUpperCase(Locale.ROOT)));
                } catch (IllegalArgumentException ex) {
                    throw new RuleException(path + ".types", "unknown spawn type '" + n + "'");
                }
            }
            s.types = set;
        }
        return s;
    }

    /** @return true if the spawn should be cancelled */
    public boolean denies(MobContext c, MobSpawnType type) {
        if (!types.contains(type)) return false;
        if (block) return true;
        if (maxCount >= 0) {
            Mob mob = c.mob;
            int n = c.level.getEntitiesOfClass(Mob.class, new AABB(c.pos).inflate(radius),
                    e -> e.getType() == mob.getType() && e != mob && e.isAlive()).size();
            return n >= maxCount;
        }
        return false;
    }
}
