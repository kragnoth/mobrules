package com.kragnoth.mobrules.rule;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.Optional;
import java.util.Set;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.MobSpawnType;
import net.neoforged.neoforge.event.EventHooks;

/**
 * {@code "rider": {"mob": "minecraft:skeleton", "chance": 0.25}} - spawns a mob riding this one.
 * The rider spawns as a jockey (spawn type "jockey"), so other rules can target it; riders never get riders.
 */
public final class Rider {
    private final EntityType<?> type;
    private final double chance;

    private Rider(EntityType<?> type, double chance) {
        this.type = type;
        this.chance = chance;
    }

    public static Rider parse(JsonElement e, String path) {
        JsonObject o = e.isJsonPrimitive() ? new JsonObject() : Json.object(e, path);
        if (e.isJsonPrimitive()) o.add("mob", e);
        Json.checkKeys(o, path, Set.of("mob", "chance"));
        String id = Json.string(o, "mob", path);
        if (id == null) throw new RuleException(path, "needs \"mob\"");
        Optional<EntityType<?>> type = BuiltInRegistries.ENTITY_TYPE.getOptional(ResourceLocation.parse(id));
        if (type.isEmpty()) throw new RuleException(path, "unknown mob '" + id + "'");
        return new Rider(type.get(), Json.number(o, "chance", 1.0, path));
    }

    public void apply(MobContext c) {
        if (c.mob.getSpawnType() == MobSpawnType.JOCKEY || c.mob.isVehicle()) return;
        if (chance < 1 && c.random.nextDouble() >= chance) return;
        Entity e = type.create(c.level);
        if (!(e instanceof Mob rider)) {
            if (e != null) e.discard();
            return;
        }
        rider.moveTo(c.mob.getX(), c.mob.getY(), c.mob.getZ(), c.mob.getYRot(), 0f);
        EventHooks.finalizeMobSpawn(rider, c.level, c.difficulty, MobSpawnType.JOCKEY, null);
        rider.startRiding(c.mob, true);
        c.level.addFreshEntity(rider);
    }
}
