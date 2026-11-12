package com.kragnoth.mobrules.rule;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import net.minecraft.core.particles.ParticleType;
import net.minecraft.core.particles.SimpleParticleType;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LightningBolt;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.entity.animal.Sheep;
import net.minecraft.world.entity.monster.Zombie;
import net.minecraft.world.entity.monster.ZombieVillager;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.entity.npc.VillagerData;
import net.minecraft.world.entity.npc.VillagerDataHolder;
import net.minecraft.world.entity.npc.VillagerProfession;
import net.minecraft.world.entity.npc.VillagerType;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.event.EventHooks;
import org.jetbrains.annotations.Nullable;

/** Server-only extras: death effects, mob variants and particle auras. Clients don't need the mod for any of these. */
public final class Extras {
    private Extras() {}

    static EntityType<?> mobType(String id, String path) {
        Optional<EntityType<?>> t = BuiltInRegistries.ENTITY_TYPE.getOptional(ResourceLocation.parse(id));
        if (t.isEmpty()) throw new RuleException(path, "unknown mob '" + id + "'");
        return t.get();
    }

    /** Spawns helpers around a mob (minions on death, reinforcements when hurt). */
    public record Summon(@Nullable EntityType<?> type, NumberSpec count, double chance, @Nullable String rule) {
        static List<Summon> parseList(@Nullable JsonElement e, String path) {
            List<Summon> out = new ArrayList<>();
            int i = 0;
            for (JsonElement x : Json.list(e)) {
                String p = path + "[" + i++ + "]";
                JsonObject o = x.isJsonPrimitive() ? new JsonObject() : Json.object(x, p);
                if (x.isJsonPrimitive()) o.add("mob", x);
                Json.checkKeys(o, p, Set.of("mob", "count", "chance", "rule"));
                String id = Json.string(o, "mob", p);
                out.add(new Summon(id == null ? null : mobType(id, p + ".mob"),
                        o.has("count") ? NumberSpec.parse(o.get("count"), p + ".count") : NumberSpec.constant(1),
                        Json.number(o, "chance", 1, p), Json.string(o, "rule", p)));
            }
            return out;
        }

        public void spawn(Mob origin, ServerLevel level, MobContext c) {
            if (chance < 1 && c.random.nextDouble() >= chance) return;
            EntityType<?> t = type != null ? type : origin.getType();
            int n = Math.max(0, Math.min(16, count.evalInt(c)));
            for (int i = 0; i < n; i++) {
                Entity e = t.create(level);
                if (!(e instanceof Mob m)) {
                    if (e != null) e.discard();
                    return;
                }
                double x = origin.getX() + (c.random.nextDouble() - 0.5) * 3, z = origin.getZ() + (c.random.nextDouble() - 0.5) * 3;
                m.moveTo(x, origin.getY(), z, c.random.nextFloat() * 360f, 0f);
                EventHooks.finalizeMobSpawn(m, level, c.difficulty, MobSpawnType.MOB_SUMMONED, null);
                if (m.isSpawnCancelled()) continue;
                m.getPersistentData().putBoolean("mobrules_summoned", true);
                level.addFreshEntity(m);
                if (origin.getTarget() != null) m.setTarget(origin.getTarget());
                Rule r = rule != null ? com.kragnoth.mobrules.RuleManager.get(rule) : null;
                if (r != null) com.kragnoth.mobrules.RuleEngine.applyRules(m, null, r);
            }
        }
    }

    /**
     * <pre>
     * "on_death": { "explode": { "power": 2, "fire": false, "break_blocks": false },
     *               "lightning": true, "summon": [ { "mob": "minecraft:silverfish", "count": [2, 4], "rule": "pack:brood" } ] }
     * </pre>
     */
    public static final class OnDeath {
        double power;
        boolean fire, breakBlocks, lightning;
        List<Summon> summons = List.of();

        public static OnDeath parse(JsonElement e, String path) {
            OnDeath d = new OnDeath();
            JsonObject o = Json.object(e, path);
            Json.checkKeys(o, path, Set.of("explode", "lightning", "summon"));
            if (o.has("explode")) {
                JsonObject x = Json.object(o.get("explode"), path + ".explode");
                Json.checkKeys(x, path + ".explode", Set.of("power", "fire", "break_blocks"));
                d.power = Json.number(x, "power", 2, path + ".explode");
                d.fire = Json.bool(x, "fire", false, path);
                d.breakBlocks = Json.bool(x, "break_blocks", false, path);
            }
            d.lightning = Json.bool(o, "lightning", false, path);
            d.summons = Summon.parseList(o.get("summon"), path + ".summon");
            return d;
        }

        public void run(Mob mob, ServerLevel level) {
            MobContext c = new MobContext(mob, level);
            if (lightning) {
                LightningBolt bolt = EntityType.LIGHTNING_BOLT.create(level);
                if (bolt != null) {
                    bolt.moveTo(mob.getX(), mob.getY(), mob.getZ());
                    bolt.setVisualOnly(true);
                    level.addFreshEntity(bolt);
                }
            }
            if (power > 0) {
                level.explode(mob, null, null, mob.getX(), mob.getY() + 0.5, mob.getZ(), (float) power, fire,
                        breakBlocks ? Level.ExplosionInteraction.MOB : Level.ExplosionInteraction.NONE);
            }
            if (!mob.getPersistentData().getBoolean("mobrules_summoned")) for (Summon s : summons) s.spawn(mob, level, c);
        }
    }

    /**
     * <pre>
     * "variant": { "baby_chance": 0.25, "can_break_doors": true, "villager_profession": "minecraft:farmer",
     *              "villager_type": "minecraft:taiga", "sheep_color": "red", "slime_size": 4 }
     * </pre>
     */
    public static final class Variant {
        double babyChance = -1;
        @Nullable Boolean breakDoors;
        @Nullable VillagerProfession profession;
        @Nullable VillagerType villagerType;
        @Nullable DyeColor sheepColor;
        int slimeSize = -1;

        public static Variant parse(JsonElement e, String path) {
            Variant v = new Variant();
            JsonObject o = Json.object(e, path);
            Json.checkKeys(o, path, Set.of("baby_chance", "can_break_doors", "villager_profession", "villager_type", "sheep_color", "slime_size"));
            v.babyChance = Json.number(o, "baby_chance", -1, path);
            v.breakDoors = Json.bool(o, "can_break_doors", path);
            String prof = Json.string(o, "villager_profession", path);
            if (prof != null) v.profession = BuiltInRegistries.VILLAGER_PROFESSION.getOptional(ResourceLocation.parse(prof))
                    .orElseThrow(() -> new RuleException(path + ".villager_profession", "unknown profession '" + prof + "'"));
            String vt = Json.string(o, "villager_type", path);
            if (vt != null) v.villagerType = BuiltInRegistries.VILLAGER_TYPE.getOptional(ResourceLocation.parse(vt))
                    .orElseThrow(() -> new RuleException(path + ".villager_type", "unknown villager type '" + vt + "'"));
            String col = Json.string(o, "sheep_color", path);
            if (col != null) {
                v.sheepColor = DyeColor.byName(col, null);
                if (v.sheepColor == null) throw new RuleException(path + ".sheep_color", "unknown colour '" + col + "'");
            }
            v.slimeSize = (int) Json.number(o, "slime_size", -1, path);
            return v;
        }

        public void apply(MobContext c) {
            Mob mob = c.mob;
            if (babyChance >= 0) mob.setBaby(c.random.nextDouble() < babyChance);
            if (breakDoors != null && mob instanceof Zombie z) z.setCanBreakDoors(breakDoors);
            if ((profession != null || villagerType != null) && mob instanceof VillagerDataHolder holder) {
                VillagerData d = holder.getVillagerData();
                if (profession != null) d = d.setProfession(profession);
                if (villagerType != null) d = d.setType(villagerType);
                if (mob instanceof Villager vil) vil.setVillagerData(d);
                else if (mob instanceof ZombieVillager zv) zv.setVillagerData(d);
                else holder.setVillagerData(d);
            }
            if (sheepColor != null && mob instanceof Sheep s) s.setColor(sheepColor);
            if (slimeSize > 0) {
                CompoundTag t = new CompoundTag();
                t.putInt("Size", slimeSize - 1);
                mob.readAdditionalSaveData(mergeInto(mob, t));
                mob.setHealth(mob.getMaxHealth());
            }
        }

        private static CompoundTag mergeInto(Mob mob, CompoundTag extra) {
            CompoundTag all = new CompoundTag();
            mob.addAdditionalSaveData(all);
            all.merge(extra);
            return all;
        }
    }

    /** {@code "particles": {"type": "minecraft:soul_fire_flame", "count": 2, "interval": 4, "spread": 0.4}} */
    public static final class Aura {
        SimpleParticleType type;
        int count = 2, interval = 4;
        double spread = 0.4;

        public static Aura parse(JsonElement e, String path) {
            Aura a = new Aura();
            JsonObject o = e.isJsonPrimitive() ? new JsonObject() : Json.object(e, path);
            if (e.isJsonPrimitive()) o.add("type", e);
            Json.checkKeys(o, path, Set.of("type", "count", "interval", "spread"));
            String id = Json.string(o, "type", path);
            if (id == null) throw new RuleException(path, "needs \"type\"");
            ParticleType<?> pt = BuiltInRegistries.PARTICLE_TYPE.get(ResourceKey.create(Registries.PARTICLE_TYPE, ResourceLocation.parse(id)));
            if (!(pt instanceof SimpleParticleType simple)) throw new RuleException(path + ".type", "'" + id + "' is not a simple particle (try flame, soul_fire_flame, smoke, portal, enchant, end_rod...)");
            a.type = simple;
            a.count = Math.max(1, (int) Json.number(o, "count", 2, path));
            a.interval = Math.max(1, (int) Json.number(o, "interval", 4, path));
            a.spread = Json.number(o, "spread", 0.4, path);
            return a;
        }

        public void tick(Mob mob) {
            if (mob.tickCount % interval != 0 || !(mob.level() instanceof ServerLevel level)) return;
            double w = mob.getBbWidth() * spread * 1.5, h = mob.getBbHeight();
            level.sendParticles(type, mob.getX(), mob.getY() + h * 0.5, mob.getZ(), count, w, h * 0.3, w, 0.01);
        }
    }
}
