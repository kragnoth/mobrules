package com.kragnoth.mobrules.rule;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.List;
import java.util.Set;
import net.minecraft.ChatFormatting;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.TagParser;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.TextColor;
import net.minecraft.world.entity.Mob;
import org.jetbrains.annotations.Nullable;

/**
 * Flags and naming. All optional:
 * <pre>
 * "glowing", "silent", "no_gravity", "invulnerable", "persistent", "left_handed", "no_ai", "baby": true/false,
 * "name": "Elite {mob}"  or  {"text": "Elite {mob}", "color": "gold" | "#FFAA00", "bold": true, "visible": true},
 * "add_tags": ["elite"],            // scoreboard tags (other rules, In Control and commands can test these)
 * "nbt": "{CanBreakDoors:1b}"       // advanced: merged into the mob's saved data
 * </pre>
 */
public final class Identity {
    static final Set<String> KEYS = Set.of("glowing", "silent", "no_gravity", "invulnerable", "persistent",
            "left_handed", "no_ai", "baby", "name", "add_tags", "nbt");

    @Nullable private Boolean glowing, silent, noGravity, invulnerable, persistent, leftHanded, noAi, baby;
    @Nullable private String nameText;
    @Nullable private Style nameStyle;
    private boolean nameVisible;
    private List<String> addTags = List.of();
    @Nullable private CompoundTag nbt;

    public static Identity parse(JsonObject o, String path) {
        Identity id = new Identity();
        id.glowing = Json.bool(o, "glowing", path);
        id.silent = Json.bool(o, "silent", path);
        id.noGravity = Json.bool(o, "no_gravity", path);
        id.invulnerable = Json.bool(o, "invulnerable", path);
        id.persistent = Json.bool(o, "persistent", path);
        id.leftHanded = Json.bool(o, "left_handed", path);
        id.noAi = Json.bool(o, "no_ai", path);
        id.baby = Json.bool(o, "baby", path);
        if (o.has("name")) {
            JsonElement n = o.get("name");
            if (n.isJsonPrimitive()) {
                id.nameText = n.getAsString();
                id.nameStyle = Style.EMPTY;
            } else {
                JsonObject no = Json.object(n, path + ".name");
                Json.checkKeys(no, path + ".name", Set.of("text", "color", "bold", "italic", "visible"));
                id.nameText = Json.string(no, "text", path + ".name");
                if (id.nameText == null) throw new RuleException(path + ".name", "needs \"text\"");
                Style st = Style.EMPTY;
                String color = Json.string(no, "color", path + ".name");
                if (color != null) {
                    TextColor tc = TextColor.parseColor(color).result().orElse(null);
                    if (tc == null) {
                        ChatFormatting f = ChatFormatting.getByName(color);
                        if (f == null || !f.isColor()) throw new RuleException(path + ".name.color", "unknown color '" + color + "'");
                        tc = TextColor.fromLegacyFormat(f);
                    }
                    st = st.withColor(tc);
                }
                if (Json.bool(no, "bold", false, path)) st = st.withBold(true);
                if (Json.bool(no, "italic", false, path)) st = st.withItalic(true);
                id.nameStyle = st;
                id.nameVisible = Json.bool(no, "visible", false, path);
            }
        }
        id.addTags = Json.strings(o.get("add_tags"), path + ".add_tags");
        String nbt = Json.string(o, "nbt", path);
        if (nbt != null) {
            try {
                id.nbt = TagParser.parseTag(nbt);
            } catch (Exception ex) {
                throw new RuleException(path + ".nbt", "bad SNBT: " + ex.getMessage());
            }
        }
        return id;
    }

    public void apply(MobContext c) {
        Mob mob = c.mob;
        if (nbt != null) mob.readAdditionalSaveData(nbt.copy());
        if (baby != null) mob.setBaby(baby);
        if (glowing != null) mob.setGlowingTag(glowing);
        if (silent != null) mob.setSilent(silent);
        if (noGravity != null) mob.setNoGravity(noGravity);
        if (invulnerable != null) mob.setInvulnerable(invulnerable);
        if (persistent != null && persistent) mob.setPersistenceRequired();
        if (leftHanded != null) mob.setLeftHanded(leftHanded);
        if (noAi != null) mob.setNoAi(noAi);
        if (nameText != null) {
            mob.setCustomName(name(mob));
            if (nameVisible) mob.setCustomNameVisible(true);
        }
        for (String t : addTags) mob.addTag(t);
    }

    private Component name(Mob mob) {
        MutableComponent out = Component.empty().withStyle(nameStyle);
        String[] parts = nameText.split("\\{mob}", -1);
        for (int i = 0; i < parts.length; i++) {
            if (!parts[i].isEmpty()) out.append(Component.literal(parts[i]));
            if (i < parts.length - 1) out.append(mob.getType().getDescription());
        }
        return out;
    }
}
