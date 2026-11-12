package com.kragnoth.mobrules;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.kragnoth.mobrules.rule.Json;
import com.kragnoth.mobrules.rule.RuleException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.ChatFormatting;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.TextColor;
import net.minecraft.network.protocol.game.ClientboundSetSubtitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitleTextPacket;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerBossEvent;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimpleJsonResourceReloadListener;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.profiling.ProfilerFiller;
import net.minecraft.world.BossEvent;
import net.minecraft.world.level.saveddata.SavedData;
import org.jetbrains.annotations.Nullable;

/**
 * World events ({@code data/<ns>/mobrules_events/<name>.json}): a Blood Moon, a Fog Night... Rules use them with
 * the {@code "events"} condition. They start at dusk or dawn when their trigger passes, or with
 * {@code /mobrules event start}, and end when the night (or day, or duration) is over.
 * <pre>
 * { "name": "Blood Moon", "color": "dark_red",
 *   "trigger": { "moon_phases": [0], "chance": 0.5, "every_days": 0, "min_day": 3 },
 *   "dimensions": ["minecraft:overworld"], "starts": "dusk", "lasts": "night",
 *   "start_message": "The Blood Moon rises...", "end_message": "The Blood Moon sets.",
 *   "title": true, "sound": "minecraft:entity.wither.spawn", "boss_bar": "red" }
 * </pre>
 */
public final class WorldEvents extends SimpleJsonResourceReloadListener {
    private static final Set<String> KEYS = Set.of("name", "color", "trigger", "dimensions", "starts", "lasts",
            "start_message", "end_message", "title", "sound", "boss_bar");

    public record Def(String id, String name, Style style, @Nullable Set<Integer> moon, double chance, int everyDays, int minDay,
                      Set<String> dims, int startTime, int lasts, @Nullable String startMsg, @Nullable String endMsg,
                      boolean title, @Nullable SoundEvent sound, @Nullable BossEvent.BossBarColor bar) {}

    private static volatile Map<String, Def> defs = Map.of();
    private static volatile List<String> errors = List.of();
    private static final Map<ServerLevel, Long> LAST_TIME = new HashMap<>();
    private static final Map<String, ServerBossEvent> BARS = new HashMap<>();

    public WorldEvents() {
        super(new Gson(), "mobrules_events");
    }

    @Override
    protected void apply(Map<ResourceLocation, JsonElement> files, ResourceManager rm, ProfilerFiller pf) {
        Map<String, Def> out = new HashMap<>();
        List<String> errs = new ArrayList<>();
        for (var f : files.entrySet()) {
            try {
                out.put(f.getKey().toString(), parse(f.getKey().toString(), f.getValue()));
            } catch (Exception ex) {
                errs.add(f.getKey() + ": " + ex.getMessage());
                MobRules.LOGGER.error("[Mob Rules] Skipping event {}: {}", f.getKey(), ex.getMessage());
            }
        }
        defs = Map.copyOf(out);
        errors = List.copyOf(errs);
        MobRules.LOGGER.info("[Mob Rules] Loaded {} world event(s)", out.size());
    }

    private static Def parse(String id, JsonElement e) {
        JsonObject o = Json.object(e, id);
        Json.checkKeys(o, id, KEYS);
        String name = Json.string(o, "name", id);
        if (name == null) name = id.substring(id.indexOf(':') + 1).replace('_', ' ');
        Style st = Style.EMPTY.withColor(ChatFormatting.DARK_RED);
        String c = Json.string(o, "color", id);
        if (c != null) {
            TextColor tc = TextColor.parseColor(c).result().orElse(null);
            if (tc == null) throw new RuleException(id + ".color", "unknown color '" + c + "'");
            st = Style.EMPTY.withColor(tc);
        }
        Set<Integer> moon = null;
        double chance = 1;
        int every = 0, minDay = 0;
        boolean hasTrigger = o.has("trigger");
        if (hasTrigger) {
            JsonObject t = Json.object(o.get("trigger"), id + ".trigger");
            Json.checkKeys(t, id + ".trigger", Set.of("moon_phases", "chance", "every_days", "min_day"));
            if (t.has("moon_phases")) {
                moon = new HashSet<>();
                for (String s : Json.strings(t.get("moon_phases"), id + ".trigger.moon_phases")) moon.add(Integer.parseInt(s));
            }
            chance = Json.number(t, "chance", 1, id + ".trigger");
            every = (int) Json.number(t, "every_days", 0, id + ".trigger");
            minDay = (int) Json.number(t, "min_day", 0, id + ".trigger");
        } else chance = 0; // command only
        Set<String> dims = o.has("dimensions") ? new HashSet<>(Json.strings(o.get("dimensions"), id + ".dimensions")) : Set.of("minecraft:overworld");
        String starts = Json.string(o, "starts", id);
        int startTime = starts == null || starts.equals("dusk") ? 12500 : starts.equals("dawn") ? 0 : -1;
        if (startTime < 0) throw new RuleException(id + ".starts", "use dusk or dawn");
        int lasts;
        JsonElement l = o.get("lasts");
        if (l == null) lasts = -1;
        else if (Json.isNumber(l)) lasts = Math.max(20, l.getAsInt());
        else if (l.getAsString().equals("night")) lasts = -1;
        else if (l.getAsString().equals("day")) lasts = -2;
        else throw new RuleException(id + ".lasts", "use night, day or a number of ticks");
        String snd = Json.string(o, "sound", id);
        SoundEvent sound = snd == null ? null : BuiltInRegistries.SOUND_EVENT.get(ResourceLocation.parse(snd));
        BossEvent.BossBarColor bar = BossEvent.BossBarColor.RED;
        if (o.has("boss_bar")) {
            JsonElement b = o.get("boss_bar");
            if (b.isJsonPrimitive() && b.getAsJsonPrimitive().isBoolean()) bar = b.getAsBoolean() ? bar : null;
            else {
                bar = BossEvent.BossBarColor.byName(b.getAsString());
            }
        }
        return new Def(id, name, st, moon, chance, every, minDay, dims, startTime, lasts,
                Json.string(o, "start_message", id), Json.string(o, "end_message", id), Json.bool(o, "title", true, id), sound, bar);
    }

    public static Map<String, Def> defs() {
        return defs;
    }

    public static List<String> errors() {
        return errors;
    }

    // ------------------------------------------------------------------ state (saved per dimension)

    static final class State extends SavedData {
        /** event id -> {end day time, total length} */
        final Map<String, long[]> active = new HashMap<>();

        static State load(CompoundTag tag, HolderLookup.Provider p) {
            State s = new State();
            for (String k : tag.getAllKeys()) {
                long[] v = tag.getLongArray(k);
                if (v.length == 2) s.active.put(k, v);
            }
            return s;
        }

        @Override
        public CompoundTag save(CompoundTag tag, HolderLookup.Provider p) {
            active.forEach(tag::putLongArray);
            return tag;
        }
    }

    static State state(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(new SavedData.Factory<>(State::new, State::load, null), "mobrules_events");
    }

    public static boolean isActive(ServerLevel level, String id) {
        return state(level).active.containsKey(id);
    }

    public static Set<String> active(ServerLevel level) {
        return state(level).active.keySet();
    }

    // ------------------------------------------------------------------ ticking

    static void tick(MinecraftServer server) {
        if (server.getTickCount() % 20 != 0) return;
        for (ServerLevel level : server.getAllLevels()) {
            long now = level.getDayTime();
            Long prevBoxed = LAST_TIME.put(level, now);
            State st = state(level);
            // end finished events
            for (var it = st.active.entrySet().iterator(); it.hasNext(); ) {
                var e = it.next();
                if (now >= e.getValue()[0] || now < e.getValue()[0] - e.getValue()[1] - 24000) {
                    it.remove();
                    st.setDirty();
                    ended(level, e.getKey());
                }
            }
            if (prevBoxed != null) {
                long prev = prevBoxed;
                for (Def d : defs.values()) {
                    if (d.chance <= 0 || !d.dims.contains(level.dimension().location().toString()) || st.active.containsKey(d.id)) continue;
                    if (!crossed(prev, now, d.startTime)) continue;
                    long day = now / 24000L;
                    if (day < d.minDay) continue;
                    if (d.everyDays > 0 && day % d.everyDays != 0) continue;
                    if (d.moon != null && !d.moon.contains(level.getMoonPhase())) continue;
                    if (d.chance < 1 && level.random.nextDouble() >= d.chance) continue;
                    start(level, d);
                }
            }
            for (var e : st.active.entrySet()) updateBar(level, e.getKey(), e.getValue(), now);
        }
    }

    private static boolean crossed(long prev, long now, int at) {
        if (now <= prev || now - prev > 12000) return false;
        long a = prev % 24000, b = now % 24000;
        return a <= b ? a < at && at <= b : at > a || at <= b;
    }

    public static boolean start(ServerLevel level, Def d) {
        State st = state(level);
        if (st.active.containsKey(d.id)) return false;
        long now = level.getDayTime(), t = now % 24000;
        long len = d.lasts == -1 ? (t < 23500 && t >= 12000 ? 23500 - t : (23500 - t + 24000) % 24000)
                : d.lasts == -2 ? (12500 - t + 24000) % 24000 : d.lasts;
        if (len < 200) len = d.lasts > 0 ? d.lasts : 12000;
        st.active.put(d.id, new long[]{now + len, len});
        st.setDirty();
        for (ServerPlayer p : level.players()) {
            if (d.startMsg != null) p.sendSystemMessage(Component.literal(d.startMsg).withStyle(d.style));
            if (d.title) {
                p.connection.send(new ClientboundSetTitleTextPacket(Component.literal(d.name).withStyle(d.style)));
                if (d.startMsg != null) p.connection.send(new ClientboundSetSubtitleTextPacket(Component.literal(d.startMsg).withStyle(ChatFormatting.GRAY)));
            }
            if (d.sound != null) p.playNotifySound(d.sound, SoundSource.AMBIENT, 1f, 0.8f);
        }
        MobRules.LOGGER.info("[Mob Rules] Event {} started in {}", d.id, level.dimension().location());
        return true;
    }

    public static boolean stop(ServerLevel level, String id) {
        State st = state(level);
        if (st.active.remove(id) == null) return false;
        st.setDirty();
        ended(level, id);
        return true;
    }

    private static void ended(ServerLevel level, String id) {
        ServerBossEvent bar = BARS.remove(level.dimension().location() + "|" + id);
        if (bar != null) bar.removeAllPlayers();
        Def d = defs.get(id);
        if (d != null && d.endMsg != null) for (ServerPlayer p : level.players()) p.sendSystemMessage(Component.literal(d.endMsg).withStyle(d.style));
    }

    private static void updateBar(ServerLevel level, String id, long[] v, long now) {
        Def d = defs.get(id);
        if (d == null || d.bar == null) return;
        String key = level.dimension().location() + "|" + id;
        ServerBossEvent bar = BARS.computeIfAbsent(key, k -> {
            ServerBossEvent b = new ServerBossEvent(Component.literal(d.name).withStyle(d.style), d.bar, BossEvent.BossBarOverlay.PROGRESS);
            b.setDarkenScreen(false);
            return b;
        });
        bar.setProgress(Math.max(0f, Math.min(1f, (v[0] - now) / (float) v[1])));
        for (ServerPlayer p : new ArrayList<>(bar.getPlayers())) if (p.level() != level || p.isRemoved()) bar.removePlayer(p);
        for (ServerPlayer p : level.players()) if (!bar.getPlayers().contains(p)) bar.addPlayer(p);
    }

    static void clear() {
        LAST_TIME.clear();
        BARS.values().forEach(ServerBossEvent::removeAllPlayers);
        BARS.clear();
    }
}
