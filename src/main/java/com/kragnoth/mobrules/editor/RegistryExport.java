package com.kragnoth.mobrules.editor;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.DefaultAttributes;
import net.minecraft.world.entity.ai.attributes.RangedAttribute;
import net.minecraft.world.item.Equipable;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.Enchantment;

/** Everything the editor needs for its pickers, read from the running game (so modded content is included). */
final class RegistryExport {
    private RegistryExport() {}

    @SuppressWarnings("unchecked")
    static JsonObject build(MinecraftServer server) {
        RegistryAccess ra = server.registryAccess();
        JsonObject out = new JsonObject();

        // attributes first, so mobs can list their base values
        JsonArray attrs = new JsonArray();
        List<Holder.Reference<Attribute>> attrHolders = BuiltInRegistries.ATTRIBUTE.holders().toList();
        for (Holder.Reference<Attribute> h : attrHolders) {
            Attribute a = h.value();
            JsonObject o = new JsonObject();
            o.addProperty("id", h.key().location().toString());
            o.addProperty("name", Component.translatable(a.getDescriptionId()).getString());
            o.addProperty("default", a.getDefaultValue());
            if (a instanceof RangedAttribute r) {
                o.addProperty("min", r.getMinValue());
                o.addProperty("max", r.getMaxValue());
            }
            attrs.add(o);
        }
        out.add("attributes", attrs);

        JsonArray mobs = new JsonArray();
        for (EntityType<?> type : BuiltInRegistries.ENTITY_TYPE) {
            if (!DefaultAttributes.hasSupplier(type)) continue;
            ResourceLocation id = BuiltInRegistries.ENTITY_TYPE.getKey(type);
            JsonObject o = new JsonObject();
            o.addProperty("id", id.toString());
            o.addProperty("name", type.getDescription().getString());
            o.addProperty("category", type.getCategory().getName());
            o.addProperty("width", type.getWidth());
            o.addProperty("height", type.getHeight());
            AttributeSupplier sup = DefaultAttributes.getSupplier((EntityType<? extends LivingEntity>) type);
            JsonObject base = new JsonObject();
            for (Holder.Reference<Attribute> h : attrHolders) {
                if (sup.hasAttribute(h)) base.addProperty(h.key().location().toString(), sup.getBaseValue(h));
            }
            o.add("attributes", base);
            mobs.add(o);
        }
        out.add("mobs", mobs);

        JsonArray items = new JsonArray();
        for (Item item : BuiltInRegistries.ITEM) {
            ResourceLocation id = BuiltInRegistries.ITEM.getKey(item);
            if (id.getPath().equals("air")) continue;
            ItemStack stack = new ItemStack(item);
            JsonObject o = new JsonObject();
            o.addProperty("id", id.toString());
            o.addProperty("name", stack.getHoverName().getString());
            Equipable eq = Equipable.get(stack);
            if (eq != null) o.addProperty("slot", slotName(eq.getEquipmentSlot()));
            if (stack.isDamageableItem()) o.addProperty("durability", stack.getMaxDamage());
            items.add(o);
        }
        out.add("items", items);

        JsonArray ench = new JsonArray();
        Registry<Enchantment> enchReg = ra.registryOrThrow(Registries.ENCHANTMENT);
        enchReg.holders().forEach(h -> {
            JsonObject o = new JsonObject();
            o.addProperty("id", h.key().location().toString());
            o.addProperty("name", h.value().description().getString());
            o.addProperty("max", h.value().getMaxLevel());
            ench.add(o);
        });
        out.add("enchantments", ench);

        JsonArray effects = new JsonArray();
        BuiltInRegistries.MOB_EFFECT.holders().forEach(h -> {
            JsonObject o = new JsonObject();
            o.addProperty("id", h.key().location().toString());
            o.addProperty("name", h.value().getDisplayName().getString());
            o.addProperty("beneficial", h.value().isBeneficial());
            effects.add(o);
        });
        out.add("effects", effects);

        out.add("biomes", ids(ra.registryOrThrow(Registries.BIOME)));
        // vanilla (and modded) natural spawns per mob, so the editor can show what a mob does by default
        JsonObject spawns = new JsonObject();
        ra.registryOrThrow(Registries.BIOME).holders().forEach(bh -> {
            var settings = bh.value().getMobSettings();
            for (net.minecraft.world.entity.MobCategory cat : net.minecraft.world.entity.MobCategory.values()) {
                for (var sd : settings.getMobs(cat).unwrap()) {
                    String mid = BuiltInRegistries.ENTITY_TYPE.getKey(sd.type).toString();
                    JsonArray arr = spawns.has(mid) ? spawns.getAsJsonArray(mid) : new JsonArray();
                    JsonObject e = new JsonObject();
                    e.addProperty("biome", bh.key().location().toString());
                    e.addProperty("weight", sd.getWeight().asInt());
                    e.addProperty("min", sd.minCount);
                    e.addProperty("max", sd.maxCount);
                    arr.add(e);
                    spawns.add(mid, arr);
                }
            }
        });
        out.add("natural_spawns", spawns);
        out.add("biome_tags", tags(ra.registryOrThrow(Registries.BIOME)));
        out.add("structures", ids(ra.registryOrThrow(Registries.STRUCTURE)));
        out.add("structure_tags", tags(ra.registryOrThrow(Registries.STRUCTURE)));
        out.add("damage_types", ids(ra.registryOrThrow(Registries.DAMAGE_TYPE)));
        out.add("damage_type_tags", tags(ra.registryOrThrow(Registries.DAMAGE_TYPE)));
        out.add("enchantment_tags", tags(enchReg));
        out.add("enchantment_providers", ids(ra.registryOrThrow(Registries.ENCHANTMENT_PROVIDER)));
        out.add("trim_materials", ids(ra.registryOrThrow(Registries.TRIM_MATERIAL)));
        out.add("trim_patterns", ids(ra.registryOrThrow(Registries.TRIM_PATTERN)));
        out.add("entity_tags", tags(BuiltInRegistries.ENTITY_TYPE));
        out.add("item_tags", tags(BuiltInRegistries.ITEM));
        out.add("block_tags", tags(BuiltInRegistries.BLOCK));
        out.add("professions", ids(BuiltInRegistries.VILLAGER_PROFESSION));
        out.add("villager_types", ids(BuiltInRegistries.VILLAGER_TYPE));
        JsonArray parts = new JsonArray();
        for (var e : BuiltInRegistries.PARTICLE_TYPE.entrySet()) {
            if (e.getValue() instanceof net.minecraft.core.particles.SimpleParticleType) parts.add(e.getKey().location().toString());
        }
        out.add("particles", parts);
        JsonArray blocks = new JsonArray();
        for (net.minecraft.world.level.block.Block b : BuiltInRegistries.BLOCK) {
            JsonObject o = new JsonObject();
            o.addProperty("id", BuiltInRegistries.BLOCK.getKey(b).toString());
            o.addProperty("name", b.getName().getString());
            blocks.add(o);
        }
        out.add("blocks", blocks);

        JsonArray dims = new JsonArray();
        for (ResourceKey<net.minecraft.world.level.Level> k : server.levelKeys()) dims.add(k.location().toString());
        out.add("dimensions", dims);

        JsonArray loot = new JsonArray();
        Collection<ResourceLocation> tables = server.reloadableRegistries().getKeys(Registries.LOOT_TABLE);
        tables.stream().map(ResourceLocation::toString).sorted().forEach(loot::add);
        out.add("loot_tables", loot);

        JsonArray spawnTypes = new JsonArray();
        for (MobSpawnType t : MobSpawnType.values()) spawnTypes.add(t.name().toLowerCase(Locale.ROOT));
        out.add("spawn_types", spawnTypes);
        JsonArray cats = new JsonArray();
        for (net.minecraft.world.entity.MobCategory c : net.minecraft.world.entity.MobCategory.values()) cats.add(c.getName());
        out.add("categories", cats);
        JsonArray events = new JsonArray();
        com.kragnoth.mobrules.WorldEvents.defs().keySet().stream().sorted().forEach(events::add);
        out.add("events", events);
        return out;
    }

    private static String slotName(EquipmentSlot s) {
        return s.getName();
    }

    private static <T> JsonArray ids(Registry<T> reg) {
        List<String> list = new ArrayList<>();
        for (ResourceLocation rl : reg.keySet()) list.add(rl.toString());
        list.sort(null);
        JsonArray a = new JsonArray();
        list.forEach(a::add);
        return a;
    }

    private static <T> JsonArray tags(Registry<T> reg) {
        List<String> list = new ArrayList<>();
        reg.getTagNames().forEach(t -> list.add("#" + t.location()));
        list.sort(null);
        JsonArray a = new JsonArray();
        list.forEach(a::add);
        return a;
    }
}
