package com.kragnoth.mobrules.rule;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.Difficulty;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.levelgen.structure.Structure;
import org.jetbrains.annotations.Nullable;

/**
 * Which mobs a rule applies to. Every condition present must pass; absent conditions are ignored.
 * {@code any_of} needs at least one nested condition block to pass, {@code none_of} needs none to.
 */
public final class Conditions {
    private static final Set<String> KEYS = Set.of("mobs", "categories", "hostile", "baby", "dimensions", "biomes",
            "structures", "height", "light", "sees_sky", "weather", "time", "moon_phases", "day", "difficulty",
            "local_difficulty", "distance_from_spawn", "spawn_types", "chance", "has_tags", "lacks_tags",
            "any_of", "none_of", "near_blocks", "events");

    @Nullable private Matcher<EntityType<?>> mobs;
    @Nullable private Set<String> categories;
    @Nullable private Boolean hostile, baby, seesSky;
    @Nullable private Matcher<Level> dimensions;
    @Nullable private Matcher<Biome> biomes;
    @Nullable private Matcher<Structure> structures;
    @Nullable private Range height, light, time, day, localDifficulty, distance;
    @Nullable private Set<String> weather;
    @Nullable private Set<Integer> moonPhases;
    @Nullable private Set<Difficulty> difficulty;
    @Nullable private Set<String> spawnTypes;
    private double chance = 1.0;
    private List<String> hasTags = List.of(), lacksTags = List.of();
    private final List<Conditions> anyOf = new ArrayList<>(), noneOf = new ArrayList<>();
    @Nullable private Matcher<net.minecraft.world.level.block.Block> nearBlocks;
    private int nearRadius = 4, nearCount = 1;
    private List<String> events = List.of();

    public static Conditions parse(@Nullable JsonElement e, String path) {
        Conditions c = new Conditions();
        if (e == null || e.isJsonNull()) return c;
        JsonObject o = Json.object(e, path);
        Json.checkKeys(o, path, KEYS);
        if (o.has("mobs")) c.mobs = Matcher.parse(o.get("mobs"), Registries.ENTITY_TYPE, path + ".mobs");
        if (o.has("categories")) c.categories = lower(Json.strings(o.get("categories"), path + ".categories"));
        c.hostile = Json.bool(o, "hostile", path);
        c.baby = Json.bool(o, "baby", path);
        c.seesSky = Json.bool(o, "sees_sky", path);
        if (o.has("dimensions")) c.dimensions = Matcher.parse(o.get("dimensions"), Registries.DIMENSION, path + ".dimensions");
        if (o.has("biomes")) c.biomes = Matcher.parse(o.get("biomes"), Registries.BIOME, path + ".biomes");
        if (o.has("structures")) c.structures = Matcher.parse(o.get("structures"), Registries.STRUCTURE, path + ".structures");
        if (o.has("height")) c.height = Range.parse(o.get("height"), path + ".height");
        if (o.has("light")) c.light = Range.parse(o.get("light"), path + ".light");
        if (o.has("time")) c.time = Range.parse(o.get("time"), path + ".time");
        if (o.has("day")) c.day = Range.parse(o.get("day"), path + ".day");
        if (o.has("local_difficulty")) c.localDifficulty = Range.parse(o.get("local_difficulty"), path + ".local_difficulty");
        if (o.has("distance_from_spawn")) c.distance = Range.parse(o.get("distance_from_spawn"), path + ".distance_from_spawn");
        if (o.has("weather")) {
            c.weather = lower(Json.strings(o.get("weather"), path + ".weather"));
            for (String w : c.weather) {
                if (!Set.of("clear", "rain", "thunder").contains(w)) throw new RuleException(path + ".weather", "use clear, rain or thunder");
            }
        }
        if (o.has("moon_phases")) {
            c.moonPhases = new HashSet<>();
            for (String s : Json.strings(o.get("moon_phases"), path + ".moon_phases")) c.moonPhases.add(Integer.parseInt(s));
        }
        if (o.has("difficulty")) {
            c.difficulty = new HashSet<>();
            for (String s : Json.strings(o.get("difficulty"), path + ".difficulty")) {
                Difficulty d = Difficulty.byName(s.toLowerCase(Locale.ROOT));
                if (d == null) throw new RuleException(path + ".difficulty", "unknown difficulty '" + s + "'");
                c.difficulty.add(d);
            }
        }
        if (o.has("spawn_types")) {
            c.spawnTypes = lower(Json.strings(o.get("spawn_types"), path + ".spawn_types"));
            for (String s : c.spawnTypes) {
                if (!s.equals("unknown") && !spawnTypeNames().contains(s)) {
                    throw new RuleException(path + ".spawn_types", "unknown spawn type '" + s + "' (use " + String.join(", ", spawnTypeNames()) + ", unknown)");
                }
            }
        }
        c.chance = Json.number(o, "chance", 1.0, path);
        c.hasTags = Json.strings(o.get("has_tags"), path + ".has_tags");
        c.lacksTags = Json.strings(o.get("lacks_tags"), path + ".lacks_tags");
        if (o.has("near_blocks")) {
            JsonObject nb = Json.object(o.get("near_blocks"), path + ".near_blocks");
            Json.checkKeys(nb, path + ".near_blocks", Set.of("blocks", "radius", "count"));
            if (!nb.has("blocks")) throw new RuleException(path + ".near_blocks", "needs \"blocks\"");
            c.nearBlocks = Matcher.parse(nb.get("blocks"), Registries.BLOCK, path + ".near_blocks.blocks");
            c.nearRadius = Math.max(1, Math.min(12, (int) Json.number(nb, "radius", 4, path + ".near_blocks")));
            c.nearCount = Math.max(1, (int) Json.number(nb, "count", 1, path + ".near_blocks"));
        }
        c.events = Json.strings(o.get("events"), path + ".events");
        int i = 0;
        for (JsonElement x : Json.list(o.get("any_of"))) c.anyOf.add(parse(x, path + ".any_of[" + i++ + "]"));
        i = 0;
        for (JsonElement x : Json.list(o.get("none_of"))) c.noneOf.add(parse(x, path + ".none_of[" + i++ + "]"));
        return c;
    }

    private static Set<String> lower(List<String> in) {
        Set<String> out = new HashSet<>();
        for (String s : in) out.add(s.toLowerCase(Locale.ROOT));
        return out;
    }

    private static Set<String> spawnTypeNames() {
        Set<String> out = new HashSet<>();
        for (MobSpawnType t : MobSpawnType.values()) out.add(t.name().toLowerCase(Locale.ROOT));
        return out;
    }

    private boolean near(MobContext c) {
        int found = 0;
        for (net.minecraft.core.BlockPos p : net.minecraft.core.BlockPos.betweenClosed(c.pos.offset(-nearRadius, -nearRadius, -nearRadius), c.pos.offset(nearRadius, nearRadius, nearRadius))) {
            if (!c.level.isLoaded(p)) continue;
            if (nearBlocks.matches(c.level.getBlockState(p).getBlockHolder()) && ++found >= nearCount) return true;
        }
        return false;
    }

    /** Cheap identity checks only (used to skip rules quickly). */
    public boolean testMob(MobContext c) {
        if (mobs != null && !mobs.matches(c.mob.getType().builtInRegistryHolder())) return false;
        if (categories != null && !categories.contains(c.mob.getType().getCategory().getName())) return false;
        if (hostile != null && (c.mob instanceof Enemy) != hostile) return false;
        if (baby != null && c.mob.isBaby() != baby) return false;
        for (String t : hasTags) if (!c.mob.getTags().contains(t)) return false;
        for (String t : lacksTags) if (c.mob.getTags().contains(t)) return false;
        return true;
    }

    /** Full test, including world position and a roll for {@code chance}. */
    public boolean test(MobContext c) {
        if (!testMob(c)) return false;
        if (dimensions != null && !dimensions.matchesId(c.level.dimension().location())) return false;
        if (height != null && !height.test(c.pos.getY())) return false;
        if (light != null && !light.test(c.level.getMaxLocalRawBrightness(c.pos))) return false;
        if (seesSky != null && c.level.canSeeSky(c.pos) != seesSky) return false;
        if (weather != null && !weather.contains(c.level.isThundering() ? "thunder" : c.level.isRaining() ? "rain" : "clear")) return false;
        if (time != null && !time.testWrapping(c.level.getDayTime() % 24000L)) return false;
        if (moonPhases != null && !moonPhases.contains(c.level.getMoonPhase())) return false;
        if (day != null && !day.test(c.day())) return false;
        if (difficulty != null && !difficulty.contains(c.level.getDifficulty())) return false;
        if (localDifficulty != null && !localDifficulty.test(c.localDifficulty())) return false;
        if (distance != null && !distance.test(c.distanceFromSpawn())) return false;
        if (spawnTypes != null) {
            MobSpawnType st = c.mob.getSpawnType();
            if (!spawnTypes.contains(st == null ? "unknown" : st.name().toLowerCase(Locale.ROOT))) return false;
        }
        if (!events.isEmpty()) {
            boolean any = false, needAny = false;
            for (String ev : events) {
                if (ev.startsWith("!")) {
                    if (com.kragnoth.mobrules.WorldEvents.isActive(c.level, ev.substring(1))) return false;
                } else {
                    needAny = true;
                    if (com.kragnoth.mobrules.WorldEvents.isActive(c.level, ev)) any = true;
                }
            }
            if (needAny && !any) return false;
        }
        if (biomes != null && !biomes.matches(c.level.getBiome(c.pos))) return false;
        if (nearBlocks != null && !near(c)) return false;
        if (structures != null && !c.level.structureManager().getStructureWithPieceAt(c.pos, structures::matches).isValid()) return false;
        if (!anyOf.isEmpty()) {
            boolean any = false;
            for (Conditions sub : anyOf) if (sub.test(c)) { any = true; break; }
            if (!any) return false;
        }
        for (Conditions sub : noneOf) if (sub.test(c)) return false;
        return chance >= 1.0 || c.random.nextDouble() < chance;
    }
}
