package com.kragnoth.mobrules.rule;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.SimpleParticleType;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

/**
 * A timed move a mob uses on its target. It telegraphs during {@code windup} (stops, faces the target,
 * particles and sound) so players can react, then fires.
 * <pre>
 * "abilities": [ { "type": "leap", "cooldown": 100, "range": [3, 12], "windup": 10 },
 *                { "type": "summon", "mobs": [ { "mob": "minecraft:vex", "count": 2 } ], "health_below": 0.5 } ]
 * </pre>
 */
public final class Ability {
    public enum Type { LEAP, CHARGE, PULL, BLINK, WEB, SUMMON, SLAM, SHIELD, BUFF }

    private static final Set<String> KEYS = Set.of("type", "cooldown", "range", "chance", "windup", "needs_sight",
            "health_below", "strength", "damage", "radius", "duration", "mobs", "block", "self_effects",
            "target_effects", "sound", "particle", "message");

    public final Type type;
    public final int cooldown, windup, duration;
    public final double minRange, maxRange, chance, healthBelow, strength, radius;
    public final double damage; // < 0: use the mob's attack damage
    public final boolean needsSight;
    public final List<Extras.Summon> mobs;
    public final Block block;
    public final List<EffectSpec> selfEffects, targetEffects;
    @Nullable public final SoundEvent sound;
    public final ParticleOptions particle;
    @Nullable public final String message;

    private Ability(JsonObject o, String p) {
        String t = Json.string(o, "type", p);
        if (t == null) throw new RuleException(p, "needs \"type\" (leap, charge, pull, blink, web, summon, slam, shield, buff)");
        try {
            type = Type.valueOf(t.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            throw new RuleException(p + ".type", "unknown ability '" + t + "' (use leap, charge, pull, blink, web, summon, slam, shield, buff)");
        }
        cooldown = Math.max(10, (int) Json.number(o, "cooldown", 100, p));
        windup = Math.max(0, (int) Json.number(o, "windup", 10, p));
        duration = (int) Json.number(o, "duration", type == Type.SHIELD ? 60 : 100, p);
        Range r = o.has("range") ? Range.parse(o.get("range"), p + ".range") : null;
        double[] def = switch (type) {
            case LEAP -> new double[]{3, 12};
            case CHARGE -> new double[]{4, 14};
            case PULL -> new double[]{5, 16};
            case BLINK -> new double[]{2, 20};
            case SLAM -> new double[]{0, 4};
            default -> new double[]{0, 16};
        };
        minRange = r != null && Double.isFinite(r.min()) ? r.min() : def[0];
        maxRange = r != null && Double.isFinite(r.max()) ? r.max() : def[1];
        chance = Json.number(o, "chance", 1, p);
        healthBelow = Json.number(o, "health_below", 1, p);
        strength = Json.number(o, "strength", 1, p);
        radius = Json.number(o, "radius", 4, p);
        damage = Json.number(o, "damage", -1, p);
        needsSight = Json.bool(o, "needs_sight", true, p);
        mobs = Extras.Summon.parseList(o.get("mobs"), p + ".mobs");
        if (type == Type.SUMMON && mobs.isEmpty()) throw new RuleException(p, "summon needs \"mobs\"");
        String b = Json.string(o, "block", p);
        if (b != null) {
            var bl = BuiltInRegistries.BLOCK.getOptional(ResourceLocation.parse(b));
            if (bl.isEmpty()) throw new RuleException(p + ".block", "unknown block '" + b + "'");
            block = bl.get();
        } else block = Blocks.COBWEB;
        selfEffects = EffectSpec.parseList(o.get("self_effects"), p + ".self_effects");
        targetEffects = EffectSpec.parseList(o.get("target_effects"), p + ".target_effects");
        String s = Json.string(o, "sound", p);
        sound = sound(s != null ? s : switch (type) {
            case LEAP -> "entity.goat.prepare_ram";
            case CHARGE -> "entity.ravager.roar";
            case PULL -> "entity.evoker.prepare_attack";
            case BLINK -> "entity.enderman.stare";
            case WEB -> "entity.spider.ambient";
            case SUMMON -> "entity.evoker.prepare_summon";
            case SLAM -> "entity.warden.sonic_charge";
            case SHIELD -> "block.beacon.activate";
            case BUFF -> "entity.illusioner.cast_spell";
        });
        String pa = Json.string(o, "particle", p);
        String pid = pa != null ? pa : switch (type) {
            case LEAP -> "minecraft:cloud";
            case CHARGE -> "minecraft:angry_villager";
            case PULL -> "minecraft:portal";
            case BLINK -> "minecraft:reverse_portal";
            case WEB -> "minecraft:white_ash";
            case SUMMON -> "minecraft:soul";
            case SLAM -> "minecraft:large_smoke";
            case SHIELD -> "minecraft:enchant";
            case BUFF -> "minecraft:happy_villager";
        };
        var pt = BuiltInRegistries.PARTICLE_TYPE.get(ResourceLocation.parse(pid));
        if (!(pt instanceof SimpleParticleType spt)) throw new RuleException(p + ".particle", "'" + pid + "' isn't a simple particle");
        particle = spt;
        message = Json.string(o, "message", p);
    }

    static List<Ability> parseList(@Nullable JsonElement e, String path) {
        List<Ability> out = new ArrayList<>();
        int i = 0;
        for (JsonElement x : Json.list(e)) {
            String p = path + "[" + i++ + "]";
            JsonObject o;
            if (x.isJsonPrimitive()) {
                o = new JsonObject();
                o.add("type", x);
            } else o = Json.object(x, p);
            Json.checkKeys(o, p, KEYS);
            out.add(new Ability(o, p));
        }
        return out;
    }

    @Nullable
    static SoundEvent sound(String id) {
        return BuiltInRegistries.SOUND_EVENT.get(ResourceLocation.parse(id.contains(":") ? id : "minecraft:" + id));
    }

    public static void play(ServerLevel level, Mob mob, @Nullable SoundEvent s, float volume) {
        if (s != null) level.playSound(null, mob.getX(), mob.getY(), mob.getZ(), s, SoundSource.HOSTILE, volume, 0.9f + mob.getRandom().nextFloat() * 0.2f);
    }

    /** Could this fire now? Range, sight and health checks; the caller handles cooldown and chance. */
    public boolean ready(Mob mob, LivingEntity target) {
        if (mob.getHealth() > mob.getMaxHealth() * healthBelow) return false;
        double d = mob.distanceTo(target);
        if (d < minRange || d > maxRange) return false;
        return !needsSight || mob.getSensing().hasLineOfSight(target);
    }

    /** One telegraph tick. */
    public void telegraph(ServerLevel level, Mob mob, LivingEntity target, int tick) {
        mob.getNavigation().stop();
        mob.getLookControl().setLookAt(target, 30f, 30f);
        if (tick % 2 == 0) level.sendParticles(particle, mob.getX(), mob.getY() + mob.getBbHeight() * 0.6, mob.getZ(), 4,
                mob.getBbWidth() * 0.6, mob.getBbHeight() * 0.4, mob.getBbWidth() * 0.6, 0.02);
    }

    private float hitDamage(Mob mob) {
        if (damage >= 0) return (float) damage;
        var a = mob.getAttribute(Attributes.ATTACK_DAMAGE);
        return a != null ? (float) Math.max(2, a.getValue()) : 4f;
    }

    /** Fires. @return ticks a charge stays "live" for contact damage (0 for other types) */
    public int fire(ServerLevel level, Mob mob, LivingEntity target, MobContext ctx, AbilityHooks hooks) {
        Vec3 d = target.position().subtract(mob.position());
        Vec3 flat = new Vec3(d.x, 0, d.z);
        double dist = flat.length();
        Vec3 dir = dist > 0.01 ? flat.scale(1 / dist) : Vec3.ZERO;
        int live = 0;
        switch (type) {
            case LEAP -> {
                double v = Math.min(1.6, dist / 10) * strength;
                mob.setDeltaMovement(dir.x * v, 0.5 * Math.max(0.6, strength) + Math.max(0, d.y) * 0.08, dir.z * v);
                mob.hurtMarked = true;
                live = 25;
            }
            case CHARGE -> {
                double v = Math.min(1.4, dist / 9) * strength;
                mob.setDeltaMovement(dir.x * v, 0.22, dir.z * v);
                mob.hurtMarked = true;
                live = 20;
            }
            case PULL -> {
                double v = Math.min(1.6, dist / 9) * strength;
                target.setDeltaMovement(-dir.x * v, 0.35, -dir.z * v);
                target.hurtMarked = true;
                level.sendParticles(particle, target.getX(), target.getY() + 1, target.getZ(), 20, 0.3, 0.5, 0.3, 0.3);
            }
            case BLINK -> {
                Vec3 look = target.getLookAngle();
                Vec3 back = new Vec3(look.x, 0, look.z).normalize().scale(-2);
                level.sendParticles(particle, mob.getX(), mob.getY() + 1, mob.getZ(), 30, 0.4, 0.8, 0.4, 0.1);
                if (!mob.randomTeleport(target.getX() + back.x, target.getY(), target.getZ() + back.z, false)) {
                    for (int i = 0; i < 8; i++) {
                        if (mob.randomTeleport(target.getX() + (ctx.random.nextDouble() - 0.5) * 6, target.getY(), target.getZ() + (ctx.random.nextDouble() - 0.5) * 6, false)) break;
                    }
                }
                mob.lookAt(target, 180f, 180f);
                play(level, mob, sound("entity.enderman.teleport"), 1f);
            }
            case WEB -> {
                BlockPos p = target.blockPosition();
                for (BlockPos q : new BlockPos[]{p, p.above()}) {
                    if (level.getBlockState(q).isAir()) {
                        level.setBlockAndUpdate(q, block.defaultBlockState());
                        if (duration > 0) hooks.tempBlock(level, q, block, duration);
                    }
                }
            }
            case SUMMON -> {
                for (Extras.Summon s : mobs) s.spawn(mob, level, ctx);
            }
            case SLAM -> {
                float dmg = hitDamage(mob);
                for (LivingEntity e : level.getEntitiesOfClass(LivingEntity.class, mob.getBoundingBox().inflate(radius))) {
                    if (e == mob) continue;
                    boolean hit = e == target || (e instanceof Player pl && !pl.isCreative() && !pl.isSpectator());
                    if (!hit || e.distanceTo(mob) > radius + 1) continue;
                    e.hurt(mob.damageSources().mobAttack(mob), dmg);
                    Vec3 out = e.position().subtract(mob.position()).multiply(1, 0, 1);
                    out = out.lengthSqr() > 0.01 ? out.normalize() : dir;
                    e.setDeltaMovement(out.x * 0.9 * strength, 0.5 * strength, out.z * 0.9 * strength);
                    e.hurtMarked = true;
                }
                for (int i = 0; i < 16; i++) {
                    double a = i * Math.PI / 8;
                    level.sendParticles(particle, mob.getX() + Math.cos(a) * radius * 0.7, mob.getY() + 0.2, mob.getZ() + Math.sin(a) * radius * 0.7, 3, 0.2, 0.1, 0.2, 0.02);
                }
                play(level, mob, sound("entity.generic.explode"), 0.7f);
            }
            case SHIELD -> hooks.shield(mob, duration);
            case BUFF -> { }
        }
        for (EffectSpec e : selfEffects) e.apply(mob, ctx);
        for (EffectSpec e : targetEffects) e.apply(target, ctx);
        if (message != null) {
            Component line = Component.empty().append(mob.getDisplayName()).append(Component.literal(": " + message.replace("{target}", target.getName().getString())));
            for (var pl : level.players()) if (pl.distanceTo(mob) < 32) pl.sendSystemMessage(line);
        }
        return live;
    }

    /** What the engine provides to abilities. */
    public interface AbilityHooks {
        void tempBlock(ServerLevel level, BlockPos pos, Block block, int ticks);

        void shield(Mob mob, int ticks);
    }

    public float contactDamage(Mob mob) {
        return hitDamage(mob) * (float) Math.max(1, strength);
    }
}
