package com.kragnoth.mobrules.rule;

import com.google.gson.JsonElement;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;

/**
 * Matches registry entries by id or tag. Entries: {@code "minecraft:zombie"}, {@code "#minecraft:skeletons"},
 * {@code "alexscaves:*"} (whole namespace), and any of those prefixed with {@code !} to exclude.
 * With only exclusions, everything else matches.
 */
public final class Matcher<T> {
    private final Set<ResourceLocation> ids = new HashSet<>(), notIds = new HashSet<>();
    private final List<TagKey<T>> tags = new ArrayList<>(), notTags = new ArrayList<>();
    private final Set<String> namespaces = new HashSet<>(), notNamespaces = new HashSet<>();

    public static <T> Matcher<T> parse(JsonElement e, ResourceKey<? extends Registry<T>> registry, String path) {
        Matcher<T> m = new Matcher<>();
        for (String raw : Json.strings(e, path)) {
            String s = raw.trim();
            boolean not = s.startsWith("!");
            if (not) s = s.substring(1);
            try {
                if (s.endsWith(":*")) {
                    (not ? m.notNamespaces : m.namespaces).add(s.substring(0, s.length() - 2));
                } else if (s.startsWith("#")) {
                    (not ? m.notTags : m.tags).add(TagKey.create(registry, ResourceLocation.parse(s.substring(1))));
                } else {
                    (not ? m.notIds : m.ids).add(ResourceLocation.parse(s));
                }
            } catch (Exception ex) {
                throw new RuleException(path, "bad id or tag '" + raw + "'");
            }
        }
        return m;
    }

    private boolean hasPositives() {
        return !ids.isEmpty() || !tags.isEmpty() || !namespaces.isEmpty();
    }

    public boolean matches(Holder<T> holder) {
        ResourceLocation id = holder.unwrapKey().map(ResourceKey::location).orElse(null);
        if (id != null && (notIds.contains(id) || notNamespaces.contains(id.getNamespace()))) return false;
        for (TagKey<T> t : notTags) if (holder.is(t)) return false;
        if (!hasPositives()) return true;
        if (id != null && (ids.contains(id) || namespaces.contains(id.getNamespace()))) return true;
        for (TagKey<T> t : tags) if (holder.is(t)) return true;
        return false;
    }

    /** For things without tags (e.g. dimensions): ids and namespaces only. */
    public boolean matchesId(ResourceLocation id) {
        if (notIds.contains(id) || notNamespaces.contains(id.getNamespace())) return false;
        if (!hasPositives()) return true;
        return ids.contains(id) || namespaces.contains(id.getNamespace());
    }
}
