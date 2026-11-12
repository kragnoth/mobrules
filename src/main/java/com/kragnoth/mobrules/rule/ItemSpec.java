package com.kragnoth.mobrules.rule;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.serialization.DynamicOps;
import com.mojang.serialization.JsonOps;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderSet;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.component.DataComponentPatch;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.TagParser;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.RegistryOps;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.armortrim.ArmorTrim;
import net.minecraft.world.item.armortrim.TrimMaterial;
import net.minecraft.world.item.armortrim.TrimPattern;
import net.minecraft.world.item.component.DyedItemColor;
import net.minecraft.world.item.component.Unbreakable;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.item.enchantment.providers.EnchantmentProvider;
import org.jetbrains.annotations.Nullable;

/**
 * An item to give, drop or equip. Either a string id ({@code "minecraft:iron_sword"}) or an object:
 * <pre>
 * { "item": "minecraft:iron_helmet", "count": 1, "weight": 3,
 *   "enchant": { "levels": [5, 20], "from": "#minecraft:on_mob_spawn_equipment" }   // random enchanting
 *   "enchant": { "provider": "minecraft:mob_spawn_equipment" }                       // vanilla provider
 *   "enchantments": { "minecraft:protection": [1, 3] },                              // exact enchants
 *   "durability": [0.4, 0.9],          // fraction of durability left
 *   "dye": "#7F3FB2", "name": "Rusty Lid", "unbreakable": true,
 *   "trim": { "material": "minecraft:gold", "pattern": "minecraft:coast" },
 *   "components": { ... } or "{...}"   // raw data components (JSON or SNBT)
 * }
 * </pre>
 * {@code {"empty": true}} means "nothing" (useful as a weighted option).
 */
public final class ItemSpec {
    private static final Set<String> KEYS = Set.of("item", "empty", "count", "weight", "chance", "player_kill_only",
            "enchant", "enchantments", "durability", "dye", "name", "unbreakable", "trim", "components", "per_looting", "biomes");

    public final double weight;
    @Nullable private final Item item;
    private NumberSpec count = NumberSpec.constant(1);
    @Nullable private DataComponentPatch components;
    @Nullable private NumberSpec enchantLevels;
    @Nullable private TagKey<Enchantment> enchantFrom;
    @Nullable private ResourceKey<EnchantmentProvider> enchantProvider;
    private final List<Map.Entry<ResourceKey<Enchantment>, NumberSpec>> enchantments = new ArrayList<>();
    @Nullable private NumberSpec durability;
    @Nullable private Integer dye;
    @Nullable private String name;
    private boolean unbreakable;
    @Nullable private ResourceKey<TrimMaterial> trimMaterial;
    @Nullable private ResourceKey<TrimPattern> trimPattern;
    /** Present for drop entries only. */
    public double chance = 1.0;
    public boolean playerKillOnly;
    /** Drops only: extra items per Looting level of the killer's weapon, and biomes it can drop in. */
    public double perLooting;
    @Nullable public Matcher<net.minecraft.world.level.biome.Biome> biomes;

    private ItemSpec(@Nullable Item item, double weight) {
        this.item = item;
        this.weight = weight;
    }

    public static ItemSpec parse(JsonElement e, RegistryAccess registries, String path) {
        if (e.isJsonPrimitive()) {
            String s = e.getAsString();
            int brace = s.indexOf('{');
            ItemSpec spec = new ItemSpec(item(brace < 0 ? s : s.substring(0, brace), path), 1);
            if (brace >= 0) spec.components = snbtComponents(s.substring(brace), registries, path);
            return spec;
        }
        JsonObject o = Json.object(e, path);
        Json.checkKeys(o, path, KEYS);
        double weight = Json.number(o, "weight", 1, path);
        if (Json.bool(o, "empty", false, path)) return new ItemSpec(null, weight);
        String id = Json.string(o, "item", path);
        if (id == null) throw new RuleException(path, "needs \"item\" (or \"empty\": true)");
        ItemSpec spec = new ItemSpec(item(id, path), weight);
        if (o.has("count")) spec.count = NumberSpec.parse(o.get("count"), path + ".count");
        spec.chance = Json.number(o, "chance", 1.0, path);
        spec.playerKillOnly = Json.bool(o, "player_kill_only", false, path);
        spec.perLooting = Json.number(o, "per_looting", 0, path);
        if (o.has("biomes")) spec.biomes = Matcher.parse(o.get("biomes"), Registries.BIOME, path + ".biomes");
        if (o.has("enchant")) {
            JsonObject en = Json.object(o.get("enchant"), path + ".enchant");
            Json.checkKeys(en, path + ".enchant", Set.of("levels", "from", "provider"));
            String provider = Json.string(en, "provider", path + ".enchant");
            if (provider != null) {
                spec.enchantProvider = ResourceKey.create(Registries.ENCHANTMENT_PROVIDER, ResourceLocation.parse(provider));
            } else {
                spec.enchantLevels = en.has("levels") ? NumberSpec.parse(en.get("levels"), path + ".enchant.levels") : NumberSpec.constant(15);
                String from = Json.string(en, "from", path + ".enchant");
                if (from == null) from = "#minecraft:on_mob_spawn_equipment";
                if (!from.startsWith("#")) throw new RuleException(path + ".enchant.from", "expected an enchantment tag like #minecraft:in_enchanting_table");
                spec.enchantFrom = TagKey.create(Registries.ENCHANTMENT, ResourceLocation.parse(from.substring(1)));
            }
        }
        if (o.has("enchantments")) {
            JsonObject en = Json.object(o.get("enchantments"), path + ".enchantments");
            for (Map.Entry<String, JsonElement> x : en.entrySet()) {
                ResourceKey<Enchantment> key = ResourceKey.create(Registries.ENCHANTMENT, ResourceLocation.parse(x.getKey()));
                if (registries.registryOrThrow(Registries.ENCHANTMENT).getHolder(key).isEmpty()) {
                    throw new RuleException(path + ".enchantments", "unknown enchantment '" + x.getKey() + "'");
                }
                spec.enchantments.add(Map.entry(key, NumberSpec.parse(x.getValue(), path + ".enchantments." + x.getKey())));
            }
        }
        if (o.has("durability")) spec.durability = NumberSpec.parse(o.get("durability"), path + ".durability");
        if (o.has("dye")) {
            JsonElement d = o.get("dye");
            spec.dye = Json.isNumber(d) ? d.getAsInt() : Integer.parseInt(d.getAsString().replace("#", ""), 16);
        }
        spec.name = Json.string(o, "name", path);
        spec.unbreakable = Json.bool(o, "unbreakable", false, path);
        if (o.has("trim")) {
            JsonObject t = Json.object(o.get("trim"), path + ".trim");
            Json.checkKeys(t, path + ".trim", Set.of("material", "pattern"));
            String mat = Json.string(t, "material", path + ".trim"), pat = Json.string(t, "pattern", path + ".trim");
            if (mat == null || pat == null) throw new RuleException(path + ".trim", "needs material and pattern");
            spec.trimMaterial = ResourceKey.create(Registries.TRIM_MATERIAL, ResourceLocation.parse(mat));
            spec.trimPattern = ResourceKey.create(Registries.TRIM_PATTERN, ResourceLocation.parse(pat));
            if (registries.registryOrThrow(Registries.TRIM_MATERIAL).getHolder(spec.trimMaterial).isEmpty()) throw new RuleException(path + ".trim", "unknown trim material '" + mat + "'");
            if (registries.registryOrThrow(Registries.TRIM_PATTERN).getHolder(spec.trimPattern).isEmpty()) throw new RuleException(path + ".trim", "unknown trim pattern '" + pat + "'");
        }
        if (o.has("components")) {
            JsonElement c = o.get("components");
            spec.components = c.isJsonPrimitive()
                    ? snbtComponents(c.getAsString(), registries, path + ".components")
                    : decode(RegistryOps.create(JsonOps.INSTANCE, registries), c, path + ".components");
        }
        return spec;
    }

    private static Item item(String id, String path) {
        ResourceLocation rl;
        try {
            rl = ResourceLocation.parse(id.trim());
        } catch (Exception ex) {
            throw new RuleException(path, "bad item id '" + id + "'");
        }
        Optional<Holder.Reference<Item>> h = BuiltInRegistries.ITEM.getHolder(ResourceKey.create(Registries.ITEM, rl));
        if (h.isEmpty() || h.get().value() == Items.AIR) throw new RuleException(path, "unknown item '" + id + "'");
        return h.get().value();
    }

    private static DataComponentPatch snbtComponents(String snbt, RegistryAccess registries, String path) {
        CompoundTag tag;
        try {
            tag = TagParser.parseTag(snbt);
        } catch (Exception ex) {
            throw new RuleException(path, "bad SNBT components: " + ex.getMessage());
        }
        return decode(RegistryOps.create(NbtOps.INSTANCE, registries), tag, path);
    }

    private static <T> DataComponentPatch decode(DynamicOps<T> ops, T value, String path) {
        return DataComponentPatch.CODEC.parse(ops, value)
                .getOrThrow(msg -> new RuleException(path, "bad components: " + msg));
    }

    public boolean isEmpty() {
        return item == null;
    }

    public ItemStack create(MobContext c) {
        if (item == null) return ItemStack.EMPTY;
        ItemStack stack = new ItemStack(item, Math.max(1, Math.min(item.getDefaultMaxStackSize(), count.evalInt(c))));
        if (components != null) stack.applyComponents(components);
        RegistryAccess ra = c.level.registryAccess();
        if (enchantProvider != null) {
            EnchantmentHelper.enchantItemFromProvider(stack, ra, enchantProvider, c.difficulty, c.random);
        } else if (enchantLevels != null && enchantFrom != null) {
            Optional<HolderSet.Named<Enchantment>> set = ra.registryOrThrow(Registries.ENCHANTMENT).getTag(enchantFrom);
            int levels = Math.max(1, enchantLevels.evalInt(c));
            stack = EnchantmentHelper.enchantItem(c.random, stack, levels, ra, set);
        }
        final ItemStack out = stack;
        for (Map.Entry<ResourceKey<Enchantment>, NumberSpec> en : enchantments) {
            int lvl = en.getValue().evalInt(c);
            if (lvl > 0) ra.registryOrThrow(Registries.ENCHANTMENT).getHolder(en.getKey()).ifPresent(h -> out.enchant(h, lvl));
        }
        if (durability != null && stack.isDamageableItem()) {
            double left = Math.max(0.0, Math.min(1.0, durability.eval(c)));
            stack.setDamageValue(Math.min(stack.getMaxDamage() - 1, (int) Math.round(stack.getMaxDamage() * (1.0 - left))));
        }
        if (dye != null) stack.set(DataComponents.DYED_COLOR, new DyedItemColor(dye, true));
        if (name != null) stack.set(DataComponents.CUSTOM_NAME, Component.literal(name));
        if (unbreakable) stack.set(DataComponents.UNBREAKABLE, new Unbreakable(true));
        if (trimMaterial != null && trimPattern != null) {
            var mat = ra.registryOrThrow(Registries.TRIM_MATERIAL).getHolder(trimMaterial);
            var pat = ra.registryOrThrow(Registries.TRIM_PATTERN).getHolder(trimPattern);
            if (mat.isPresent() && pat.isPresent()) stack.set(DataComponents.TRIM, new ArmorTrim(mat.get(), pat.get()));
        }
        return stack;
    }
}
