package com.kragnoth.mobrules.rule;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.RandomSource;
import net.minecraft.world.Difficulty;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.entity.SpawnPlacements;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.neoforged.neoforge.event.EventHooks;

/**
 * A custom spawner: every so often, near each player, tries to spawn the mob at spots where the
 * rule's conditions pass (structures, biomes, light, near blocks...). Searches several floor levels,
 * so it works inside mazes, dungeons and caves.
 * <pre>
 * "spawner": {
 *   "mob": "minecraft:zombie",
 *   "interval": 200,               // ticks between tries per player (20 ticks = 1 second)
 *   "attempts": 4,                 // spots tried each time
 *   "chance": 0.5,                 // per spot
 *   "distance": { "min": 24, "max": 64 },   // from the player, horizontally
 *   "vertical_range": 24,          // floors searched above and below the player
 *   "group": [1, 3],
 *   "max_nearby": { "count": 6, "radius": 64 },
 *   "placement": "ground",         // ground | water | air
 *   "vanilla_checks": true         // also obey the mob's own spawn rules (darkness for monsters...)
 * }
 * </pre>
 * Spawned mobs count as natural spawns and then get the rest of this rule like any other match.
 */
public final class Spawner {
    public final EntityType<?> mob;
    int interval = 600, attempts = 2, verticalRange = 24, maxNearby = 3;
    boolean persistent;
    double chance = 1, minDist = 24, maxDist = 64, nearbyRadius = 64;
    NumberSpec group = NumberSpec.constant(1);
    String placement = "ground";
    boolean vanillaChecks = true;

    private Spawner(EntityType<?> mob) {
        this.mob = mob;
    }

    public static Spawner parse(JsonElement e, String path) {
        JsonObject o = Json.object(e, path);
        Json.checkKeys(o, path, Set.of("mob", "interval", "attempts", "chance", "distance", "vertical_range", "group",
                "max_nearby", "placement", "vanilla_checks", "persistent"));
        String id = Json.string(o, "mob", path);
        if (id == null) throw new RuleException(path, "needs \"mob\"");
        Optional<EntityType<?>> type = BuiltInRegistries.ENTITY_TYPE.getOptional(ResourceLocation.parse(id));
        if (type.isEmpty()) throw new RuleException(path + ".mob", "unknown mob '" + id + "'");
        Spawner s = new Spawner(type.get());
        s.interval = Math.max(20, (int) Json.number(o, "interval", 600, path));
        s.attempts = Math.max(1, Math.min(16, (int) Json.number(o, "attempts", 2, path)));
        s.persistent = Json.bool(o, "persistent", false, path);
        s.chance = Json.number(o, "chance", 1, path);
        if (o.has("distance")) {
            Range r = Range.parse(o.get("distance"), path + ".distance");
            s.minDist = Double.isInfinite(r.min()) ? 24 : r.min();
            s.maxDist = Double.isInfinite(r.max()) ? 64 : r.max();
        }
        s.verticalRange = (int) Json.number(o, "vertical_range", 24, path);
        if (o.has("group")) s.group = NumberSpec.parse(o.get("group"), path + ".group");
        if (o.has("max_nearby")) {
            JsonObject m = Json.object(o.get("max_nearby"), path + ".max_nearby");
            s.maxNearby = (int) Json.number(m, "count", 3, path + ".max_nearby");
            s.nearbyRadius = Json.number(m, "radius", 64, path + ".max_nearby");
        }
        String p = Json.string(o, "placement", path);
        if (p != null) {
            s.placement = p.toLowerCase(Locale.ROOT);
            if (!Set.of("ground", "water", "air").contains(s.placement)) throw new RuleException(path + ".placement", "use ground, water or air");
        }
        s.vanillaChecks = Json.bool(o, "vanilla_checks", true, path);
        return s;
    }

    public void tick(ServerLevel level, ServerPlayer player, Rule rule) {
        if (player.isSpectator()) return;
        if ((level.getGameTime() + player.getId() * 7L) % interval != 0) return;
        if (!level.getGameRules().getBoolean(GameRules.RULE_DOMOBSPAWNING)) return;
        if (mob.getCategory() == MobCategory.MONSTER && level.getDifficulty() == Difficulty.PEACEFUL) return;
        RandomSource r = level.getRandom();
        int cap = com.kragnoth.mobrules.MobRulesConfig.SPAWNER_CAP.get();
        if (level.getEntitiesOfClass(Mob.class, player.getBoundingBox().inflate(128),
                m -> m.getPersistentData().getBoolean("mobrules_spawner")).size() >= cap) return;
        for (int a = 0; a < attempts; a++) {
            if (chance < 1 && r.nextDouble() >= chance) continue;
            double ang = r.nextDouble() * Math.PI * 2, dist = minDist + r.nextDouble() * Math.max(0, maxDist - minDist);
            int x = (int) Math.floor(player.getX() + Math.cos(ang) * dist), z = (int) Math.floor(player.getZ() + Math.sin(ang) * dist);
            if (!level.isLoaded(new BlockPos(x, (int) player.getY(), z))) continue;
            BlockPos at = pickSpot(level, x, z, (int) Math.floor(player.getY()), r);
            if (at == null) continue;
            if (level.getNearestPlayer(at.getX() + 0.5, at.getY(), at.getZ() + 0.5, minDist, false) != null) continue;
            if (maxNearby >= 0 && level.getEntitiesOfClass(Mob.class, new AABB(at).inflate(nearbyRadius), m -> m.getType() == mob).size() >= maxNearby) return;
            if (vanillaChecks && !SpawnPlacements.checkSpawnRules(mob, level, MobSpawnType.NATURAL, at, r)) continue;
            spawnGroup(level, at, rule, r);
        }
    }

    private void spawnGroup(ServerLevel level, BlockPos first, Rule rule, RandomSource r) {
        BlockPos at = first;
        int n = 1;
        for (int i = 0; i < n; i++) {
            if (i > 0) {
                at = first.offset(r.nextInt(5) - 2, 0, r.nextInt(5) - 2);
                if (!fits(level, at)) continue;
            }
            Entity e = mob.create(level);
            if (!(e instanceof Mob m)) {
                if (e != null) e.discard();
                return;
            }
            m.moveTo(at.getX() + 0.5, at.getY(), at.getZ() + 0.5, r.nextFloat() * 360f, 0f);
            if (vanillaChecks && !m.checkSpawnRules(level, MobSpawnType.NATURAL)) {
                m.discard();
                continue;
            }
            if (!m.checkSpawnObstruction(level)) {
                m.discard();
                continue;
            }
            EventHooks.finalizeMobSpawn(m, level, level.getCurrentDifficultyAt(at), MobSpawnType.NATURAL, null);
            MobContext ctx = new MobContext(m, level);
            if (m.isSpawnCancelled() || !rule.conditions.test(ctx)) {
                m.discard();
                if (i == 0) return; // the spot itself doesn't qualify
                continue;
            }
            if (i == 0) n = Math.max(1, Math.min(16, group.evalInt(ctx)));
            m.getPersistentData().putBoolean("mobrules_spawner", true);
            level.addFreshEntityWithPassengers(m);
            com.kragnoth.mobrules.RuleEngine.applyRules(m, null, rule);
            if (!persistent) {
                // spawner mobs despawn like normal ones, even if a rule made them persistent
                CompoundTag t = new CompoundTag();
                m.addAdditionalSaveData(t);
                t.putBoolean("PersistenceRequired", false);
                m.readAdditionalSaveData(t);
            }
        }
    }

    /** Collects every valid floor in the column (so underground mazes and caves count) and picks one. */
    private BlockPos pickSpot(ServerLevel level, int x, int z, int py, RandomSource r) {
        List<BlockPos> spots = new ArrayList<>();
        int lo = Math.max(level.getMinBuildHeight() + 1, py - verticalRange), hi = Math.min(level.getMaxBuildHeight() - 2, py + verticalRange);
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
        for (int y = lo; y <= hi; y++) {
            p.set(x, y, z);
            if (fits(level, p)) spots.add(p.immutable());
        }
        return spots.isEmpty() ? null : spots.get(r.nextInt(spots.size()));
    }

    private boolean fits(ServerLevel level, BlockPos pos) {
        BlockState here = level.getBlockState(pos);
        boolean water = here.getFluidState().is(FluidTags.WATER);
        switch (placement) {
            case "water" -> {
                if (!water) return false;
            }
            case "air" -> {
                if (!here.getFluidState().isEmpty()) return false;
            }
            default -> {
                if (!here.getFluidState().isEmpty()) return false;
                BlockPos below = pos.below();
                if (!level.getBlockState(below).isFaceSturdy(level, below, Direction.UP)) return false;
            }
        }
        return level.noCollision(mob.getSpawnAABB(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5));
    }
}
