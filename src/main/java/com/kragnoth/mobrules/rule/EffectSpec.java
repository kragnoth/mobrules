package com.kragnoth.mobrules.rule;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.LivingEntity;

/**
 * {@code {"effect": "minecraft:speed", "amplifier": [0, 1], "duration": -1, "particles": false, "chance": 1}}
 * Duration is in ticks; -1 (the default) is infinite. A bare string is the effect at amplifier 0, infinite.
 */
public final class EffectSpec {
    private static final Set<String> KEYS = Set.of("effect", "amplifier", "duration", "particles", "ambient", "chance");

    private final Holder<MobEffect> effect;
    private NumberSpec amplifier = NumberSpec.constant(0);
    private NumberSpec duration = NumberSpec.constant(MobEffectInstance.INFINITE_DURATION);
    private boolean particles, ambient;
    private double chance = 1;

    private EffectSpec(Holder<MobEffect> effect) {
        this.effect = effect;
    }

    public static List<EffectSpec> parseList(JsonElement e, String path) {
        List<EffectSpec> out = new ArrayList<>();
        int i = 0;
        for (JsonElement x : Json.list(e)) out.add(parse(x, path + "[" + i++ + "]"));
        return out;
    }

    public static EffectSpec parse(JsonElement e, String path) {
        if (e.isJsonPrimitive()) return new EffectSpec(effect(e.getAsString(), path));
        JsonObject o = Json.object(e, path);
        Json.checkKeys(o, path, KEYS);
        String id = Json.string(o, "effect", path);
        if (id == null) throw new RuleException(path, "needs \"effect\"");
        EffectSpec s = new EffectSpec(effect(id, path));
        if (o.has("amplifier")) s.amplifier = NumberSpec.parse(o.get("amplifier"), path + ".amplifier");
        if (o.has("duration")) s.duration = NumberSpec.parse(o.get("duration"), path + ".duration");
        s.particles = Json.bool(o, "particles", false, path);
        s.ambient = Json.bool(o, "ambient", false, path);
        s.chance = Json.number(o, "chance", 1, path);
        return s;
    }

    private static Holder<MobEffect> effect(String id, String path) {
        return BuiltInRegistries.MOB_EFFECT.getHolder(ResourceKey.create(Registries.MOB_EFFECT, ResourceLocation.parse(id)))
                .orElseThrow(() -> new RuleException(path, "unknown effect '" + id + "'"));
    }

    public void apply(LivingEntity target, MobContext c) {
        if (chance < 1 && c.random.nextDouble() >= chance) return;
        int dur = duration.evalInt(c);
        if (dur == 0) return;
        target.addEffect(new MobEffectInstance(effect, dur < 0 ? MobEffectInstance.INFINITE_DURATION : dur,
                Math.max(0, amplifier.evalInt(c)), ambient, particles));
    }
}
