package com.kragnoth.mobrules.rule;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageType;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import org.jetbrains.annotations.Nullable;

/**
 * <pre>
 * "combat": {
 *   "damage_taken": [ { "types": ["#minecraft:is_projectile"], "attackers": ["minecraft:player"], "multiply": 0.5, "add": 0 } ],
 *   "damage_dealt": [ { "types": [...], "targets": ["minecraft:player"], "multiply": 1.5 } ],
 *   "on_hit":  { "effects": [ {"effect": "minecraft:poison", "duration": 100, "chance": 0.5} ], "ignite_seconds": 3, "lifesteal": 0.25 },
 *   "on_hurt": { "thorns": 2, "attacker_effects": [ ... ],
 *                "reinforcements": [ {"mob": "minecraft:zombie", "count": [1, 2], "chance": 0.3} ], "reinforce_cooldown": 200,
 *                "enrage": { "below": 0.5, "effects": ["minecraft:strength", "minecraft:speed"] },
 *                "teleport": { "chance": 0.2, "range": 8 } },
 *   "regen": { "amount": 1, "interval": 40 },
 *   "fire_immune": true, "sun_immune": true, "no_knockback": true
 * }
 * </pre>
 * "types" are damage type ids or tags (e.g. #minecraft:is_fire, #minecraft:is_explosion, minecraft:arrow).
 */
public final class Combat {
    private static final Set<String> KEYS = Set.of("damage_taken", "damage_dealt", "on_hit", "on_hurt", "regen",
            "fire_immune", "sun_immune", "no_knockback");

    public record DamageMod(@Nullable Matcher<DamageType> types, @Nullable Matcher<EntityType<?>> others, double multiply, double add) {
        public boolean matches(DamageSource src, @Nullable Entity other) {
            if (types != null && !types.matches(src.typeHolder())) return false;
            if (others != null && (other == null || !others.matches(other.getType().builtInRegistryHolder()))) return false;
            return true;
        }
    }

    public final List<DamageMod> taken = new ArrayList<>(), dealt = new ArrayList<>();
    public List<EffectSpec> hitEffects = List.of(), attackerEffects = List.of();
    public double igniteSeconds, lifesteal, thorns;
    public List<Extras.Summon> reinforcements = List.of();
    public int reinforceCooldown = 200;
    public double enrageBelow = -1, teleportChance, teleportRange = 8;
    public List<EffectSpec> enrageEffects = List.of();
    public double regenAmount;
    public int regenInterval = 40;
    public boolean fireImmune, sunImmune, noKnockback;

    public static Combat parse(JsonElement e, String path) {
        Combat c = new Combat();
        JsonObject o = Json.object(e, path);
        Json.checkKeys(o, path, KEYS);
        readMods(o.get("damage_taken"), "attackers", c.taken, path + ".damage_taken");
        readMods(o.get("damage_dealt"), "targets", c.dealt, path + ".damage_dealt");
        if (o.has("on_hit")) {
            JsonObject h = Json.object(o.get("on_hit"), path + ".on_hit");
            Json.checkKeys(h, path + ".on_hit", Set.of("effects", "ignite_seconds", "lifesteal"));
            c.hitEffects = EffectSpec.parseList(h.get("effects"), path + ".on_hit.effects");
            c.igniteSeconds = Json.number(h, "ignite_seconds", 0, path + ".on_hit");
            c.lifesteal = Json.number(h, "lifesteal", 0, path + ".on_hit");
        }
        if (o.has("on_hurt")) {
            JsonObject h = Json.object(o.get("on_hurt"), path + ".on_hurt");
            Json.checkKeys(h, path + ".on_hurt", Set.of("thorns", "attacker_effects", "reinforcements", "reinforce_cooldown", "enrage", "teleport"));
            c.reinforcements = Extras.Summon.parseList(h.get("reinforcements"), path + ".on_hurt.reinforcements");
            c.reinforceCooldown = (int) Json.number(h, "reinforce_cooldown", 200, path + ".on_hurt");
            if (h.has("enrage")) {
                JsonObject en = Json.object(h.get("enrage"), path + ".on_hurt.enrage");
                Json.checkKeys(en, path + ".on_hurt.enrage", Set.of("below", "effects"));
                c.enrageBelow = Json.number(en, "below", 0.5, path + ".on_hurt.enrage");
                c.enrageEffects = EffectSpec.parseList(en.get("effects"), path + ".on_hurt.enrage.effects");
            }
            if (h.has("teleport")) {
                JsonObject tp = Json.object(h.get("teleport"), path + ".on_hurt.teleport");
                Json.checkKeys(tp, path + ".on_hurt.teleport", Set.of("chance", "range"));
                c.teleportChance = Json.number(tp, "chance", 0.2, path + ".on_hurt.teleport");
                c.teleportRange = Json.number(tp, "range", 8, path + ".on_hurt.teleport");
            }
            c.thorns = Json.number(h, "thorns", 0, path + ".on_hurt");
            c.attackerEffects = EffectSpec.parseList(h.get("attacker_effects"), path + ".on_hurt.attacker_effects");
        }
        if (o.has("regen")) {
            JsonObject r = Json.object(o.get("regen"), path + ".regen");
            Json.checkKeys(r, path + ".regen", Set.of("amount", "interval"));
            c.regenAmount = Json.number(r, "amount", 1, path + ".regen");
            c.regenInterval = Math.max(1, (int) Json.number(r, "interval", 40, path + ".regen"));
        }
        c.fireImmune = Json.bool(o, "fire_immune", false, path);
        c.sunImmune = Json.bool(o, "sun_immune", false, path);
        c.noKnockback = Json.bool(o, "no_knockback", false, path);
        return c;
    }

    private static void readMods(@Nullable JsonElement e, String otherKey, List<DamageMod> into, String path) {
        int i = 0;
        for (JsonElement x : Json.list(e)) {
            String p = path + "[" + i++ + "]";
            JsonObject m = Json.object(x, p);
            Json.checkKeys(m, p, Set.of("types", otherKey, "multiply", "add"));
            Matcher<DamageType> types = m.has("types") ? Matcher.parse(m.get("types"), Registries.DAMAGE_TYPE, p + ".types") : null;
            Matcher<EntityType<?>> others = m.has(otherKey) ? Matcher.parse(m.get(otherKey), Registries.ENTITY_TYPE, p + "." + otherKey) : null;
            into.add(new DamageMod(types, others, Json.number(m, "multiply", 1, p), Json.number(m, "add", 0, p)));
        }
    }

    public boolean needsTick() {
        return regenAmount > 0 || sunImmune || fireImmune;
    }
}
