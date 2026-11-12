package com.kragnoth.mobrules.rule;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import net.minecraft.core.RegistryAccess;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;

/**
 * One rule file: {@code data/<namespace>/mobrules/<path>.json}. Its id is {@code <namespace>:<path>}.
 * <pre>
 * {
 *   "priority": 10,              // higher runs first (default 0)
 *   "group": "tier",             // only the first matching rule of a group applies to a mob
 *   "stop": false,               // true: no lower-priority rules apply after this one
 *   "enabled": true,
 *   "conditions": { ... },
 *   "attributes": { ... }, "heal": true, "equipment": { ... }, "effects": [ ... ],
 *   "glowing": true, "name": ..., "add_tags": [...], ...   (see Identity)
 *   "rider": { ... }, "combat": { ... }, "behavior": { ... }, "loot": { ... }, "boss_bar": { ... },
 *   "spawn": { "block": true } or { "max_nearby": {...} }   (see SpawnControl)
 * }
 * </pre>
 */
public final class Rule {
    private static final Set<String> KEYS;
    static {
        Set<String> k = new HashSet<>(Set.of("priority", "group", "stop", "enabled", "conditions", "attributes",
                "heal", "equipment", "effects", "rider", "combat", "behavior", "loot", "boss_bar", "spawn", "spawner", "on_death", "variant", "particles", "abilities", "affix", "affixes", "nemesis"));
        k.addAll(Identity.KEYS);
        KEYS = Set.copyOf(k);
    }

    public final ResourceLocation id;
    public final int priority;
    @Nullable public final String group;
    public final boolean stop;
    public final Conditions conditions;
    public final List<AttributeAction> attributes;
    public final boolean heal;
    @Nullable public final Equipment equipment;
    public final List<EffectSpec> effects;
    public final Identity identity;
    @Nullable public final Rider rider;
    @Nullable public final Combat combat;
    @Nullable public final Behavior behavior;
    @Nullable public final Loot loot;
    @Nullable public final BossBarSpec bossBar;
    @Nullable public final SpawnControl spawn;
    @Nullable public final Spawner spawner;
    @Nullable public final Extras.OnDeath onDeath;
    @Nullable public final Extras.Variant variant;
    @Nullable public final Extras.Aura particles;
    public final List<Ability> abilities;
    @Nullable public final Affix.Def affix;
    @Nullable public final Affix.Roll affixes;
    @Nullable public final Nemesis nemesis;

    private Rule(ResourceLocation id, JsonObject o, RegistryAccess registries) {
        String p = id.toString();
        this.id = id;
        priority = (int) Json.number(o, "priority", 0, p);
        group = Json.string(o, "group", p);
        stop = Json.bool(o, "stop", false, p);
        conditions = Conditions.parse(o.get("conditions"), p + ".conditions");
        attributes = o.has("attributes") ? AttributeAction.parse(o.get("attributes"), p + ".attributes") : List.of();
        heal = Json.bool(o, "heal", true, p);
        equipment = o.has("equipment") ? Equipment.parse(o.get("equipment"), registries, p + ".equipment") : null;
        effects = EffectSpec.parseList(o.get("effects"), p + ".effects");
        identity = Identity.parse(o, p);
        rider = o.has("rider") ? Rider.parse(o.get("rider"), p + ".rider") : null;
        combat = o.has("combat") ? Combat.parse(o.get("combat"), p + ".combat") : null;
        behavior = o.has("behavior") ? Behavior.parse(o.get("behavior"), p + ".behavior") : null;
        loot = o.has("loot") ? Loot.parse(o.get("loot"), registries, p + ".loot") : null;
        bossBar = o.has("boss_bar") ? BossBarSpec.parse(o.get("boss_bar"), p + ".boss_bar") : null;
        spawn = o.has("spawn") ? SpawnControl.parse(o.get("spawn"), p + ".spawn") : null;
        spawner = o.has("spawner") ? Spawner.parse(o.get("spawner"), p + ".spawner") : null;
        onDeath = o.has("on_death") ? Extras.OnDeath.parse(o.get("on_death"), p + ".on_death") : null;
        variant = o.has("variant") ? Extras.Variant.parse(o.get("variant"), p + ".variant") : null;
        particles = o.has("particles") ? Extras.Aura.parse(o.get("particles"), p + ".particles") : null;
        abilities = Ability.parseList(o.get("abilities"), p + ".abilities");
        affix = o.has("affix") ? Affix.Def.parse(o.get("affix"), p + ".affix") : null;
        affixes = o.has("affixes") ? Affix.Roll.parse(o.get("affixes"), p + ".affixes") : null;
        nemesis = o.has("nemesis") && !(o.get("nemesis").isJsonPrimitive() && !o.get("nemesis").getAsBoolean()) ? Nemesis.parse(o.get("nemesis"), registries, p + ".nemesis") : null;
    }

    /**
     * One file can hold many affixes:
     * {@code {"affix_set": {"pool": "elite", "conditions": {...}, "affixes": [ {"affix": "Vampiric", "combat": {...}}, ... ]}}}.
     * Each entry becomes its own rule {@code <file id>/<affix name>}; the set's pool and conditions are defaults.
     */
    public static List<Rule> parseAll(ResourceLocation id, JsonElement e, RegistryAccess registries) {
        String ps = id.toString();
        JsonObject o = Json.object(e, ps);
        if (!o.has("affix_set")) {
            Rule r = parse(id, e, registries);
            return r == null ? List.of() : List.of(r);
        }
        Json.checkKeys(o, ps, Set.of("affix_set", "enabled", "_comment"));
        if (!Json.bool(o, "enabled", true, ps)) return List.of();
        String p = ps + ".affix_set";
        JsonObject set = Json.object(o.get("affix_set"), p);
        Json.checkKeys(set, p, Set.of("pool", "conditions", "affixes"));
        java.util.List<Rule> out = new java.util.ArrayList<>();
        Set<String> seen = new HashSet<>();
        int i = 0;
        for (JsonElement x : Json.list(set.get("affixes"))) {
            String ep = p + ".affixes[" + i++ + "]";
            JsonObject c = Json.object(x, ep).deepCopy();
            JsonElement a = c.get("affix");
            if (a == null) throw new RuleException(ep, "needs \"affix\" (its name, or {\"name\": ..., \"weight\": ...})");
            JsonObject ao = a.isJsonPrimitive() ? new JsonObject() : Json.object(a, ep + ".affix").deepCopy();
            if (a.isJsonPrimitive()) ao.add("name", a);
            if (!ao.has("pool") && set.has("pool")) ao.add("pool", set.get("pool"));
            c.add("affix", ao);
            if (!c.has("conditions") && set.has("conditions")) c.add("conditions", set.get("conditions"));
            String name = Json.string(ao, "name", ep + ".affix");
            if (name == null) throw new RuleException(ep + ".affix", "needs \"name\"");
            String slug = name.toLowerCase(java.util.Locale.ROOT).replaceAll("[^a-z0-9]+", "_").replaceAll("^_|_$", "");
            if (!seen.add(slug)) throw new RuleException(ep, "two affixes in this set are named '" + name + "'");
            try {
                Rule r = parse(id.withSuffix("/" + slug), c, registries);
                if (r != null) out.add(r);
            } catch (RuleException ex) {
                throw new RuleException(ep, ex.getMessage());
            }
        }
        return out;
    }

    /** @return null if the rule is disabled */
    @Nullable
    public static Rule parse(ResourceLocation id, JsonElement e, RegistryAccess registries) {
        JsonObject o = Json.object(e, id.toString());
        Json.checkKeys(o, id.toString(), KEYS);
        if (!Json.bool(o, "enabled", true, id.toString())) return null;
        return new Rule(id, o, registries);
    }

    /** Does this rule need anything after the spawn-time changes (events, goals, ticking)? */
    public boolean hasRuntime() {
        return combat != null || behavior != null || loot != null || bossBar != null;
    }

    public boolean needsTick() {
        return bossBar != null || particles != null || !abilities.isEmpty() || (combat != null && combat.needsTick());
    }

    /** Spawn-time changes. */
    public void apply(MobContext c) {
        apply(c, false);
    }

    /** @param preview true for editor previews: no riders, no targeting, nothing added to the world */
    public void apply(MobContext c, boolean preview) {
        identity.apply(c);
        if (variant != null) variant.apply(c);
        boolean health = false;
        for (AttributeAction a : attributes) {
            if (a.apply(c, id) && a.isMaxHealth()) health = true;
        }
        if (health && heal) c.mob.setHealth(c.mob.getMaxHealth());
        if (equipment != null) equipment.apply(c);
        for (EffectSpec e : effects) e.apply(c.mob, c);
        if (preview) return;
        if (rider != null) rider.apply(c);
        if (behavior != null && behavior.angryOnSpawn) {
            var player = c.level.getNearestPlayer(c.mob, 32);
            if (player != null && !player.isCreative() && !player.isSpectator()) c.mob.setTarget(player);
        }
    }
}
