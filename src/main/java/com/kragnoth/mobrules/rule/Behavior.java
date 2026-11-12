package com.kragnoth.mobrules.rule;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.Set;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.Item;
import org.jetbrains.annotations.Nullable;

/**
 * <pre>
 * "behavior": {
 *   "griefing": false,                              // per-mob mobGriefing (creeper holes, enderman blocks, ...)
 *   "hostile_to": ["minecraft:player", "#c:villagers"],  // hunts these (adds a melee attack to passive mobs)
 *   "never_target": ["minecraft:piglin"],           // will not attack these
 *   "ignore_players_wearing": ["minecraft:golden_helmet", "#c:armors/gold"],  // any armor slot
 *   "angry_on_spawn": true,                         // goes for the nearest player straight away
 *   "attack_damage": 3                              // hit damage for mobs that normally can't attack (cows...)
 * }
 * </pre>
 */
public final class Behavior {
    @Nullable public Boolean griefing;
    @Nullable public Matcher<EntityType<?>> hostileTo, neverTarget;
    @Nullable public Matcher<Item> ignoreWearing;
    public boolean angryOnSpawn;
    /** Damage per hit for mobs with no attack_damage attribute of their own (used with hostile_to). */
    public double attackDamage = 2;

    public static Behavior parse(JsonElement e, String path) {
        Behavior b = new Behavior();
        JsonObject o = Json.object(e, path);
        Json.checkKeys(o, path, Set.of("griefing", "hostile_to", "never_target", "ignore_players_wearing", "angry_on_spawn", "attack_damage"));
        b.griefing = Json.bool(o, "griefing", path);
        if (o.has("hostile_to")) b.hostileTo = Matcher.parse(o.get("hostile_to"), Registries.ENTITY_TYPE, path + ".hostile_to");
        if (o.has("never_target")) b.neverTarget = Matcher.parse(o.get("never_target"), Registries.ENTITY_TYPE, path + ".never_target");
        if (o.has("ignore_players_wearing")) b.ignoreWearing = Matcher.parse(o.get("ignore_players_wearing"), Registries.ITEM, path + ".ignore_players_wearing");
        b.angryOnSpawn = Json.bool(o, "angry_on_spawn", false, path);
        b.attackDamage = Json.number(o, "attack_damage", 2, path);
        return b;
    }
}
