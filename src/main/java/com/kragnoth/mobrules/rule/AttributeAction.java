package com.kragnoth.mobrules.rule;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;

/**
 * <pre>
 * "attributes": {
 *   "max_health": { "multiply": 2 },              // x2 total
 *   "scale": { "multiply": [1.2, 1.6] },          // random size
 *   "attack_damage": { "add": { "base": 1, "per_day": 0.1, "cap": 6 } },
 *   "movement_speed": { "multiply_base": 1.25 },
 *   "minecraft:generic.step_height": { "set": 1.5 },
 *   "somemod:custom_attribute": { "add": 3 }
 * }
 * </pre>
 * Any registered attribute works. Short names try {@code minecraft:generic.<name>} then
 * {@code minecraft:player.<name>}. Changes are proper, named attribute modifiers that save
 * with the mob and stack predictably with other mods.
 */
public final class AttributeAction {
    enum Op { ADD, MULTIPLY, MULTIPLY_BASE, SET }

    private final Holder<Attribute> attribute;
    private final ResourceLocation attributeId;
    private final Op op;
    private final NumberSpec value;

    private AttributeAction(Holder<Attribute> attribute, ResourceLocation attributeId, Op op, NumberSpec value) {
        this.attribute = attribute;
        this.attributeId = attributeId;
        this.op = op;
        this.value = value;
    }

    public static List<AttributeAction> parse(JsonElement e, String path) {
        List<AttributeAction> out = new ArrayList<>();
        JsonObject o = Json.object(e, path);
        for (Map.Entry<String, JsonElement> entry : o.entrySet()) {
            String p = path + "." + entry.getKey();
            ResourceLocation id = resolve(entry.getKey(), p);
            Holder<Attribute> holder = BuiltInRegistries.ATTRIBUTE.getHolderOrThrow(ResourceKey.create(Registries.ATTRIBUTE, id));
            JsonObject ops = Json.object(entry.getValue(), p);
            for (Map.Entry<String, JsonElement> opEntry : ops.entrySet()) {
                Op op = switch (opEntry.getKey()) {
                    case "add" -> Op.ADD;
                    case "multiply" -> Op.MULTIPLY;
                    case "multiply_base" -> Op.MULTIPLY_BASE;
                    case "set" -> Op.SET;
                    default -> throw new RuleException(p, "unknown operation '" + opEntry.getKey() + "' (use add, multiply, multiply_base, set)");
                };
                out.add(new AttributeAction(holder, id, op, NumberSpec.parse(opEntry.getValue(), p + "." + opEntry.getKey())));
            }
        }
        return out;
    }

    static ResourceLocation resolve(String name, String path) {
        String[] candidates = name.contains(":")
                ? new String[]{name}
                : new String[]{"minecraft:generic." + name, "minecraft:player." + name, "minecraft:" + name};
        for (String c : candidates) {
            ResourceLocation rl = ResourceLocation.tryParse(c);
            if (rl != null) {
                Optional<Holder.Reference<Attribute>> h = BuiltInRegistries.ATTRIBUTE.getHolder(ResourceKey.create(Registries.ATTRIBUTE, rl));
                if (h.isPresent()) return rl;
            }
        }
        throw new RuleException(path, "unknown attribute '" + name + "'");
    }

    public boolean isMaxHealth() {
        return attribute.is(Attributes.MAX_HEALTH);
    }

    /** @return false if the mob does not have this attribute */
    public boolean apply(MobContext c, ResourceLocation ruleId) {
        AttributeInstance inst = c.mob.getAttribute(attribute);
        if (inst == null) return false;
        ResourceLocation modId = ResourceLocation.fromNamespaceAndPath(ruleId.getNamespace(),
                ruleId.getPath() + "/" + attributeId.getNamespace() + "." + attributeId.getPath() + "/" + op.name().toLowerCase());
        inst.removeModifier(modId);
        double v = value.eval(c);
        AttributeModifier mod = switch (op) {
            case ADD -> new AttributeModifier(modId, v, AttributeModifier.Operation.ADD_VALUE);
            case MULTIPLY -> new AttributeModifier(modId, v - 1.0, AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL);
            case MULTIPLY_BASE -> new AttributeModifier(modId, v - 1.0, AttributeModifier.Operation.ADD_MULTIPLIED_BASE);
            case SET -> new AttributeModifier(modId, v - inst.getValue(), AttributeModifier.Operation.ADD_VALUE);
        };
        inst.addPermanentModifier(mod);
        return true;
    }
}
