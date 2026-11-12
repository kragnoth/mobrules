package com.kragnoth.mobrules.rule;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.ChatFormatting;
import net.minecraft.core.Holder;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

/**
 * A mob that kills a player gets a name, a title and stronger with every kill, stops despawning,
 * taunts its victims when it sees them again, and pays out when it finally dies.
 * <pre>
 * "nemesis": { "names": ["Grak", "Ushnar"], "title": "{name} the {victim}-Slayer",
 *              "stats": { "max_health": 0.3, "attack_damage": 0.2 }, "max_level": 10,
 *              "announce": "all", "taunts": ["Back for more, {victim}?"],
 *              "reward": [ { "item": "minecraft:diamond", "count": [1, 2] } ], "xp_per_level": 1, "glow_at": 3 }
 * </pre>
 */
public final class Nemesis {
    private static final Set<String> KEYS = Set.of("names", "title", "stats", "max_level", "announce", "taunts", "reward", "xp_per_level", "glow_at");
    private static final List<String> NAMES = List.of("Grak", "Ushnar", "Morg", "Vexa", "Throkk", "Skarn", "Zugra", "Bleakfang",
            "Hollowmaw", "Rattlebone", "Gristle", "Murk", "Sable", "Krell", "Vorn", "Ashjaw", "Nettle", "Gorrow");
    private static final List<String> TAUNTS = List.of("Back for more, {victim}?", "I remember you, {victim}.",
            "Your last death was so easy, {victim}.", "Run, {victim}. It went well last time.", "{victim}! I kept your spot warm.");
    private static final String LVL = "mobrules_nemesis", NAME = "mobrules_nemesis_name", VICTIMS = "mobrules_nemesis_victims", TAUNT_AT = "mobrules_nemesis_taunt";

    public final List<String> names, taunts;
    public final String title, announce;
    public final Map<Holder<Attribute>, Double> stats = new LinkedHashMap<>();
    private final Map<Holder<Attribute>, ResourceLocation> statIds = new LinkedHashMap<>();
    public final int maxLevel, glowAt;
    public final List<ItemSpec> reward;
    public final double xpPerLevel;

    private Nemesis(JsonObject o, RegistryAccess reg, String p) {
        names = o.has("names") ? Json.strings(o.get("names"), p + ".names") : NAMES;
        taunts = o.has("taunts") ? Json.strings(o.get("taunts"), p + ".taunts") : TAUNTS;
        String t = Json.string(o, "title", p);
        title = t != null ? t : "{name} the {victim}-Slayer";
        String a = Json.string(o, "announce", p);
        announce = a != null ? a : "all";
        if (!Set.of("all", "victim", "none").contains(announce)) throw new RuleException(p + ".announce", "use all, victim or none");
        if (o.has("stats")) {
            JsonObject s = Json.object(o.get("stats"), p + ".stats");
            for (var e : s.entrySet()) {
                ResourceLocation id = AttributeAction.resolve(e.getKey(), p + ".stats." + e.getKey());
                var h = BuiltInRegistries.ATTRIBUTE.getHolderOrThrow(ResourceKey.create(Registries.ATTRIBUTE, id));
                stats.put(h, e.getValue().getAsDouble());
                statIds.put(h, ResourceLocation.fromNamespaceAndPath("mobrules", "nemesis/" + id.getPath()));
            }
        } else {
            for (String[] d : new String[][]{{"max_health", "0.3"}, {"attack_damage", "0.2"}}) {
                ResourceLocation id = AttributeAction.resolve(d[0], p);
                var h = BuiltInRegistries.ATTRIBUTE.getHolderOrThrow(ResourceKey.create(Registries.ATTRIBUTE, id));
                stats.put(h, Double.parseDouble(d[1]));
                statIds.put(h, ResourceLocation.fromNamespaceAndPath("mobrules", "nemesis/" + id.getPath()));
            }
        }
        maxLevel = Math.max(1, (int) Json.number(o, "max_level", 10, p));
        glowAt = (int) Json.number(o, "glow_at", 0, p);
        xpPerLevel = Json.number(o, "xp_per_level", 1, p);
        List<ItemSpec> r = new ArrayList<>();
        int i = 0;
        for (JsonElement x : Json.list(o.get("reward"))) r.add(ItemSpec.parse(x, reg, p + ".reward[" + i++ + "]"));
        reward = r;
    }

    public static Nemesis parse(JsonElement e, RegistryAccess reg, String p) {
        JsonObject o = e.isJsonPrimitive() && e.getAsJsonPrimitive().isBoolean() ? new JsonObject() : Json.object(e, p);
        Json.checkKeys(o, p, KEYS);
        return new Nemesis(o, reg, p);
    }

    public static int level(Mob mob) {
        return mob.getPersistentData().getInt(LVL);
    }

    private static List<String> victims(CompoundTag d) {
        List<String> out = new ArrayList<>();
        for (Tag t : d.getList(VICTIMS, Tag.TAG_STRING)) out.add(t.getAsString());
        return out;
    }

    private void say(ServerLevel level, ServerPlayer victim, Component msg) {
        if (announce.equals("all")) level.getServer().getPlayerList().broadcastSystemMessage(msg, false);
        else if (announce.equals("victim")) victim.sendSystemMessage(msg);
    }

    /** The mob just killed a player. */
    public void onKill(Mob mob, ServerPlayer victim, ServerLevel level) {
        CompoundTag d = mob.getPersistentData();
        String vname = victim.getGameProfile().getName();
        List<String> vs = victims(d);
        if (!vs.contains(vname)) {
            ListTag l = d.getList(VICTIMS, Tag.TAG_STRING);
            l.add(StringTag.valueOf(vname));
            d.put(VICTIMS, l);
            vs.add(vname);
        }
        if (!d.contains(NAME)) d.putString(NAME, names.get(mob.getRandom().nextInt(names.size())));
        int lvl = Math.min(maxLevel, d.getInt(LVL) + 1);
        d.putInt(LVL, lvl);
        for (var e : stats.entrySet()) {
            AttributeInstance inst = mob.getAttribute(e.getKey());
            if (inst == null) continue;
            ResourceLocation id = statIds.get(e.getKey());
            inst.removeModifier(id);
            inst.addPermanentModifier(new AttributeModifier(id, e.getValue() * lvl, AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL));
        }
        mob.setHealth(mob.getMaxHealth());
        mob.setPersistenceRequired();
        String full = title.replace("{name}", d.getString(NAME)).replace("{victim}", vs.get(0)).replace("{level}", String.valueOf(lvl));
        mob.setCustomName(Component.literal(full).withStyle(ChatFormatting.RED));
        mob.setCustomNameVisible(true);
        if (glowAt > 0 && lvl >= glowAt) mob.setGlowingTag(true);
        say(level, victim, Component.literal(full + " has slain " + vname + (lvl > 1 ? " and grows stronger (level " + lvl + ")" : " and will remember this") + ".").withStyle(ChatFormatting.DARK_RED));
    }

    /** The mob is about to target this player. */
    public void onTarget(Mob mob, Player p) {
        CompoundTag d = mob.getPersistentData();
        if (d.getInt(LVL) <= 0 || taunts.isEmpty() || !(p instanceof ServerPlayer sp)) return;
        String name = sp.getGameProfile().getName();
        if (!victims(d).contains(name)) return;
        long now = mob.level().getGameTime();
        if (now < d.getLong(TAUNT_AT)) return;
        d.putLong(TAUNT_AT, now + 600);
        String line = taunts.get(mob.getRandom().nextInt(taunts.size())).replace("{victim}", name).replace("{name}", d.getString(NAME));
        sp.sendSystemMessage(Component.empty().append(mob.getDisplayName()).append(Component.literal(": " + line).withStyle(ChatFormatting.GRAY)));
    }

    /** The nemesis died. Adds rewards, rolled once per level. */
    public void onDeath(Mob mob, ServerLevel level, Player killer, java.util.Collection<ItemEntity> drops, MobContext ctx) {
        int lvl = level(mob);
        if (lvl <= 0) return;
        for (int i = 0; i < lvl; i++) {
            for (ItemSpec s : reward) {
                if (s.chance < 1 && ctx.random.nextDouble() >= s.chance) continue;
                ItemStack st = s.create(ctx);
                if (!st.isEmpty()) drops.add(new ItemEntity(level, mob.getX(), mob.getY(), mob.getZ(), st));
            }
        }
        boolean revenge = victims(mob.getPersistentData()).contains(killer.getGameProfile().getName());
        Component msg = Component.literal(killer.getName().getString() + (revenge ? " has avenged themselves on " : " has slain ") + mob.getDisplayName().getString() + "!").withStyle(ChatFormatting.GOLD);
        if (announce.equals("all")) level.getServer().getPlayerList().broadcastSystemMessage(msg, false);
        else if (announce.equals("victim")) killer.sendSystemMessage(msg);
    }
}
