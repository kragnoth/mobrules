package com.kragnoth.mobrules;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.kragnoth.mobrules.rule.Rule;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.core.RegistryAccess;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimpleJsonResourceReloadListener;
import net.minecraft.util.profiling.ProfilerFiller;
import org.jetbrains.annotations.Nullable;

/** Loads {@code data/<ns>/mobrules/**.json} on world load and /reload. */
public final class RuleManager extends SimpleJsonResourceReloadListener {
    private static final Gson GSON = new GsonBuilder().create();

    private static volatile List<Rule> rules = List.of();
    private static volatile Map<String, Rule> byId = Map.of();
    private static volatile List<String> errors = List.of();
    private static volatile Map<String, JsonElement> sources = Map.of();
    /** Rules applied by the editor's test spawn before they are saved; cleared on reload. */
    private static final Map<String, Rule> OVERLAY = new java.util.concurrent.ConcurrentHashMap<>();
    private static volatile int generation;

    private final RegistryAccess registries;

    public RuleManager(RegistryAccess registries) {
        super(GSON, "mobrules");
        this.registries = registries;
    }

    @Override
    protected void apply(Map<ResourceLocation, JsonElement> files, ResourceManager manager, ProfilerFiller profiler) {
        List<Rule> loaded = new ArrayList<>();
        List<String> errs = new ArrayList<>();
        for (Map.Entry<ResourceLocation, JsonElement> f : files.entrySet()) {
            try {
                loaded.addAll(Rule.parseAll(f.getKey(), f.getValue(), registries));
            } catch (Exception ex) {
                String msg = ex.getMessage() == null ? ex.toString() : ex.getMessage();
                if (!msg.startsWith(f.getKey().toString())) msg = f.getKey() + ": " + msg;
                errs.add(msg);
                MobRules.LOGGER.error("[Mob Rules] Skipping rule {}", msg);
            }
        }
        loaded.sort(Comparator.comparingInt((Rule r) -> -r.priority).thenComparing(r -> r.id.toString()));
        Map<String, Rule> ids = new HashMap<>();
        for (Rule r : loaded) ids.put(r.id.toString(), r);
        Map<String, JsonElement> src = new HashMap<>();
        for (Map.Entry<ResourceLocation, JsonElement> f : files.entrySet()) src.put(f.getKey().toString(), f.getValue());
        rules = List.copyOf(loaded);
        byId = Map.copyOf(ids);
        sources = Map.copyOf(src);
        OVERLAY.clear();
        errors = List.copyOf(errs);
        generation++;
        MobRules.LOGGER.info("[Mob Rules] Loaded {} rule(s){}", loaded.size(), errs.isEmpty() ? "" : ", " + errs.size() + " with errors");
    }

    public static List<Rule> rules() {
        return rules;
    }

    @Nullable
    public static Rule get(String id) {
        Rule r = OVERLAY.get(id);
        return r != null ? r : byId.get(id);
    }

    /** The JSON a loaded rule came from (any datapack). */
    @Nullable
    public static JsonElement source(String id) {
        return sources.get(id);
    }

    public static java.util.Set<String> sourceIds() {
        return sources.keySet();
    }

    public static void putOverlay(Rule rule) {
        OVERLAY.put(rule.id.toString(), rule);
    }

    public static List<String> errors() {
        return errors;
    }

    public static int generation() {
        return generation;
    }
}
