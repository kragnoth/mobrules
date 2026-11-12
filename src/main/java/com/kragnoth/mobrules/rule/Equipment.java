package com.kragnoth.mobrules.rule;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.EnumMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import net.minecraft.core.RegistryAccess;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;

/**
 * <pre>
 * "equipment": {
 *   "mode": "replace" | "fill_empty",     // default replace; fill_empty keeps vanilla gear
 *   "clear": false,                        // strip all gear first
 *   "sets": [ { "weight": 2, "head": ..., "chest": ..., "legs": ..., "feet": ... }, ... ],  // matched loadouts
 *   "mainhand": [ item, item, {"empty": true, "weight": 3} ],   // weighted per-slot picks
 *   "offhand" / "head" / "chest" / "legs" / "feet" / "body": ...,
 *   "drop_chance": { "all": 0.0, "mainhand": 0.25 },            // 0..1, or 2+ for guaranteed + undamaged
 *   "can_pick_up_loot": true
 * }
 * </pre>
 * Slots chosen by a set are not rolled again per slot.
 */
public final class Equipment {
    private static final String[] SLOT_NAMES = {"mainhand", "offhand", "head", "chest", "legs", "feet", "body"};
    private static final Set<String> KEYS = Set.of("mode", "clear", "sets", "mainhand", "offhand", "head", "chest",
            "legs", "feet", "body", "drop_chance", "can_pick_up_loot");

    private boolean fillEmpty, clear;
    private final Weighted<Map<EquipmentSlot, Weighted<ItemSpec>>> sets = new Weighted<>();
    private final Map<EquipmentSlot, Weighted<ItemSpec>> slots = new EnumMap<>(EquipmentSlot.class);
    private final Map<EquipmentSlot, Float> dropChance = new EnumMap<>(EquipmentSlot.class);
    @Nullable private Boolean canPickUpLoot;

    public static Equipment parse(JsonElement e, RegistryAccess registries, String path) {
        Equipment eq = new Equipment();
        JsonObject o = Json.object(e, path);
        Json.checkKeys(o, path, KEYS);
        String mode = Json.string(o, "mode", path);
        if (mode != null) {
            switch (mode.toLowerCase(Locale.ROOT)) {
                case "replace" -> eq.fillEmpty = false;
                case "fill_empty" -> eq.fillEmpty = true;
                default -> throw new RuleException(path + ".mode", "use replace or fill_empty");
            }
        }
        eq.clear = Json.bool(o, "clear", false, path);
        eq.canPickUpLoot = Json.bool(o, "can_pick_up_loot", path);
        readSlots(o, eq.slots, registries, path);
        int i = 0;
        for (JsonElement s : Json.list(o.get("sets"))) {
            String sp = path + ".sets[" + i++ + "]";
            JsonObject so = Json.object(s, sp);
            Map<EquipmentSlot, Weighted<ItemSpec>> set = new EnumMap<>(EquipmentSlot.class);
            for (String key : so.keySet()) {
                if (!key.equals("weight") && !Set.of(SLOT_NAMES).contains(key)) throw new RuleException(sp, "unknown key '" + key + "'");
            }
            readSlots(so, set, registries, sp);
            eq.sets.add(set, Json.number(so, "weight", 1, sp));
        }
        if (o.has("drop_chance")) {
            JsonElement dc = o.get("drop_chance");
            if (Json.isNumber(dc)) {
                for (EquipmentSlot slot : EquipmentSlot.values()) eq.dropChance.put(slot, dc.getAsFloat());
            } else {
                JsonObject d = Json.object(dc, path + ".drop_chance");
                if (d.has("all")) for (EquipmentSlot slot : EquipmentSlot.values()) eq.dropChance.put(slot, d.get("all").getAsFloat());
                for (String key : d.keySet()) {
                    if (key.equals("all")) continue;
                    eq.dropChance.put(slot(key, path + ".drop_chance"), d.get(key).getAsFloat());
                }
            }
        }
        return eq;
    }

    private static void readSlots(JsonObject o, Map<EquipmentSlot, Weighted<ItemSpec>> into, RegistryAccess registries, String path) {
        for (String name : SLOT_NAMES) {
            if (!o.has(name)) continue;
            Weighted<ItemSpec> w = new Weighted<>();
            int i = 0;
            for (JsonElement x : Json.list(o.get(name))) {
                ItemSpec spec = ItemSpec.parse(x, registries, path + "." + name + "[" + i++ + "]");
                w.add(spec, spec.weight);
            }
            into.put(slot(name, path), w);
        }
    }

    private static EquipmentSlot slot(String name, String path) {
        try {
            return EquipmentSlot.byName(name);
        } catch (IllegalArgumentException ex) {
            throw new RuleException(path, "unknown slot '" + name + "'");
        }
    }

    public void apply(MobContext c) {
        Mob mob = c.mob;
        if (clear) for (EquipmentSlot slot : EquipmentSlot.values()) mob.setItemSlot(slot, ItemStack.EMPTY);
        Set<EquipmentSlot> done = java.util.EnumSet.noneOf(EquipmentSlot.class);
        Map<EquipmentSlot, Weighted<ItemSpec>> set = sets.pick(c.random);
        if (set != null) {
            for (Map.Entry<EquipmentSlot, Weighted<ItemSpec>> x : set.entrySet()) {
                equip(c, x.getKey(), x.getValue());
                done.add(x.getKey());
            }
        }
        for (Map.Entry<EquipmentSlot, Weighted<ItemSpec>> x : slots.entrySet()) {
            if (!done.contains(x.getKey())) equip(c, x.getKey(), x.getValue());
        }
        for (Map.Entry<EquipmentSlot, Float> x : dropChance.entrySet()) mob.setDropChance(x.getKey(), x.getValue());
        if (canPickUpLoot != null) mob.setCanPickUpLoot(canPickUpLoot);
    }

    private void equip(MobContext c, EquipmentSlot slot, Weighted<ItemSpec> options) {
        if (fillEmpty && !c.mob.getItemBySlot(slot).isEmpty()) return;
        ItemSpec spec = options.pick(c.random);
        if (spec == null) return;
        if (slot == EquipmentSlot.BODY) c.mob.setBodyArmorItem(spec.create(c));
        else c.mob.setItemSlot(slot, spec.create(c));
    }
}
