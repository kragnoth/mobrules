package com.kragnoth.mobrules.editor;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.kragnoth.mobrules.MobRules;
import com.kragnoth.mobrules.RuleEngine;
import com.kragnoth.mobrules.RuleManager;
import com.kragnoth.mobrules.rule.MobContext;
import com.kragnoth.mobrules.rule.Rule;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import net.minecraft.client.Minecraft;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.packs.repository.Pack;
import net.minecraft.server.packs.repository.PackRepository;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.event.EventHooks;

/**
 * The local API behind the browser editor. Runs in the game client; rule files, reloads and test
 * spawns go to the integrated (singleplayer or LAN host) server.
 */
public final class EditorBridge {
    static final String PACK = "mobrules_editor";
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    private static MiniHttp http;
    private static String token;
    private static JsonObject registryCache;
    private static MinecraftServer registryCacheServer;
    private static int registryCacheGen = -1;
    private static int packGeneration;
    private static final Map<String, byte[]> ICONS = new ConcurrentHashMap<>();

    private EditorBridge() {}

    /** Starts the server if needed and returns the editor URL. */
    public static synchronized String start() throws IOException {
        if (http == null) {
            byte[] b = new byte[16];
            new SecureRandom().nextBytes(b);
            token = HexFormat.of().formatHex(b);
            http = new MiniHttp(MobRulesClientConfig.PORT.get(), EditorBridge::handle);
            MobRules.LOGGER.info("[Mob Rules] Editor listening on 127.0.0.1:{}", http.port());
        }
        return "http://127.0.0.1:" + http.port() + "/?t=" + token;
    }

    public static synchronized void stop() {
        if (http != null) {
            http.stop();
            http = null;
        }
        registryCache = null;
        ICONS.clear();
    }

    // ------------------------------------------------------------------ routing

    private static MiniHttp.Response handle(MiniHttp.Request r) {
        String host = r.headers().getOrDefault("host", "");
        if (!(host.startsWith("127.0.0.1:") || host.startsWith("localhost:"))) return MiniHttp.Response.error(403, "bad host");
        String t = r.headers().getOrDefault("x-token", r.q("t", ""));
        if (!t.equals(token)) return MiniHttp.Response.error(403, "missing or wrong token; open the link from /mobrules_editor again");

        if (r.path().equals("/") || r.path().equals("/index.html")) return page();
        Minecraft mc = Minecraft.getInstance();
        MinecraftServer server = mc.getSingleplayerServer();
        if (r.path().equals("/api/status")) return status(mc, server);
        if (server == null || mc.player == null || mc.level == null) {
            return MiniHttp.Response.error(409, "Open a singleplayer world (or host a LAN world) in the game first.");
        }
        try {
            return switch (r.method() + " " + r.path()) {
                case "GET /api/registries" -> MiniHttp.Response.json(GSON.toJson(registries(server)));
                case "GET /api/rules" -> listRules(server);
                case "GET /api/rule" -> getRule(server, r.q("id", ""));
                case "PUT /api/rule" -> putRule(server, r.q("id", ""), r.bodyText());
                case "DELETE /api/rule" -> deleteRule(server, r.q("id", ""));
                case "POST /api/validate" -> validate(server, r.bodyText());
                case "POST /api/reload" -> reload(server);
                case "POST /api/preview" -> preview(mc, server, r);
                case "POST /api/spawn" -> spawn(mc, server, r);
                case "GET /api/icon" -> icon(mc, r.q("item", ""), Integer.parseInt(r.q("size", "32")));
                case "GET /api/resourcepacks" -> resourcePacks(mc);
                case "POST /api/resourcepacks" -> setResourcePacks(mc, r.bodyText());
                case "GET /api/export" -> export(server);
                default -> MiniHttp.Response.error(404, "unknown endpoint " + r.method() + " " + r.path());
            };
        } catch (BadRequest ex) {
            return MiniHttp.Response.error(400, ex.getMessage());
        } catch (Exception ex) {
            Throwable cause = ex instanceof java.util.concurrent.ExecutionException && ex.getCause() != null ? ex.getCause() : ex;
            if (cause instanceof BadRequest) return MiniHttp.Response.error(400, cause.getMessage());
            MobRules.LOGGER.warn("[Mob Rules] editor {} failed", r.path(), ex);
            return MiniHttp.Response.error(500, cause.getMessage() == null ? cause.toString() : cause.getMessage());
        }
    }

    private static final class BadRequest extends RuntimeException {
        BadRequest(String m) {
            super(m);
        }
    }

    private static MiniHttp.Response page() {
        try (InputStream in = EditorBridge.class.getResourceAsStream("/assets/mobrules/editor/index.html")) {
            if (in == null) return MiniHttp.Response.error(500, "editor page missing from the jar");
            return new MiniHttp.Response(200, "text/html; charset=utf-8", in.readAllBytes());
        } catch (IOException ex) {
            return MiniHttp.Response.error(500, ex.toString());
        }
    }

    private static MiniHttp.Response status(Minecraft mc, MinecraftServer server) {
        JsonObject o = new JsonObject();
        o.addProperty("in_world", mc.level != null);
        o.addProperty("singleplayer", server != null);
        o.addProperty("version", MobRules.VERSION);
        o.addProperty("pack_generation", packGeneration);
        if (server != null) {
            o.addProperty("world", server.getWorldData().getLevelName());
            o.addProperty("pack_dir", packDir(server).toString());
            o.addProperty("rules", RuleManager.rules().size());
            JsonArray errs = new JsonArray();
            RuleManager.errors().forEach(errs::add);
            o.add("errors", errs);
        }
        return MiniHttp.Response.json(GSON.toJson(o));
    }

    // ------------------------------------------------------------------ registries

    private static synchronized JsonObject registries(MinecraftServer server) throws Exception {
        if (registryCache == null || registryCacheServer != server || registryCacheGen != RuleManager.generation()) {
            registryCache = server.submit(() -> RegistryExport.build(server)).get(60, TimeUnit.SECONDS);
            registryCacheServer = server;
            registryCacheGen = RuleManager.generation();
        }
        return registryCache;
    }

    // ------------------------------------------------------------------ rule files

    static Path packDir(MinecraftServer server) {
        return server.getWorldPath(LevelResource.DATAPACK_DIR).resolve(PACK);
    }

    private static ResourceLocation ruleId(String id) {
        ResourceLocation rl = ResourceLocation.tryParse(id);
        if (rl == null || rl.getPath().isEmpty() || rl.getPath().contains("..") || rl.getPath().startsWith("/") || rl.getPath().endsWith("/")) {
            throw new BadRequest("bad rule id '" + id + "' (use namespace:path, lowercase, e.g. mypack:elite_zombies)");
        }
        return rl;
    }

    private static Path editorData(MinecraftServer server) {
        return packDir(server).resolve("data");
    }

    private static Path ruleFile(Path dataRoot, ResourceLocation id) {
        return dataRoot.resolve(id.getNamespace()).resolve("mobrules").resolve(id.getPath() + ".json");
    }

    private static Path ruleFile(MinecraftServer server, ResourceLocation id) {
        return ruleFile(editorData(server), id);
    }

    private static Path modifierFile(Path dataRoot, ResourceLocation id, String suffix) {
        return dataRoot.resolve(id.getNamespace()).resolve("neoforge").resolve("biome_modifier")
                .resolve("mobrules_" + id.getPath() + suffix + ".json");
    }

    /**
     * Folders on disk that rules can be edited in place: the editor pack, unzipped folders in the
     * world's datapacks, and kubejs/data. Zips and mod jars are read-only.
     */
    private static List<Path> dataRoots(MinecraftServer server) {
        List<Path> roots = new ArrayList<>();
        roots.add(editorData(server));
        Path packs = server.getWorldPath(LevelResource.DATAPACK_DIR);
        if (Files.isDirectory(packs)) {
            try (Stream<Path> s = Files.list(packs)) {
                s.filter(Files::isDirectory).filter(d -> !d.getFileName().toString().equals(PACK))
                        .map(d -> d.resolve("data")).filter(Files::isDirectory).sorted().forEach(roots::add);
            } catch (IOException ignored) {
            }
        }
        Path kube = net.neoforged.fml.loading.FMLPaths.GAMEDIR.get().resolve("kubejs").resolve("data");
        if (Files.isDirectory(kube)) roots.add(kube);
        return roots;
    }

    /** The folder a rule's file lives in, if it is editable on disk. */
    @org.jetbrains.annotations.Nullable
    private static Path rootOf(MinecraftServer server, ResourceLocation id) {
        for (Path root : dataRoots(server)) if (Files.exists(ruleFile(root, id))) return root;
        return null;
    }

    private static String label(MinecraftServer server, Path root) {
        if (root.equals(editorData(server))) return "editor pack";
        if (root.getParent() != null && root.getParent().getParent() != null
                && root.getParent().getParent().toAbsolutePath().normalize().equals(server.getWorldPath(LevelResource.DATAPACK_DIR).toAbsolutePath().normalize())) {
            return "Datapack: " + root.getParent().getFileName();
        }
        Path gameDir = net.neoforged.fml.loading.FMLPaths.GAMEDIR.get();
        return root.startsWith(gameDir) ? gameDir.relativize(root).toString().replace('\\', '/') : root.toString();
    }

    private static void ensurePack(MinecraftServer server) throws IOException {
        Path dir = packDir(server);
        Files.createDirectories(dir);
        Path meta = dir.resolve("pack.mcmeta");
        if (!Files.exists(meta)) {
            Files.writeString(meta, "{\n  \"pack\": { \"pack_format\": 48, \"description\": \"Rules made with the Mob Rules editor\" }\n}\n");
        }
    }

    private static MiniHttp.Response listRules(MinecraftServer server) throws IOException {
        JsonObject o = new JsonObject();
        JsonArray editor = new JsonArray();
        Path data = packDir(server).resolve("data");
        List<String> editorIds = new ArrayList<>();
        if (Files.isDirectory(data)) {
            try (Stream<Path> nss = Files.list(data)) {
                for (Path ns : nss.toList()) {
                    Path rules = ns.resolve("mobrules");
                    if (!Files.isDirectory(rules)) continue;
                    try (Stream<Path> files = Files.walk(rules)) {
                        for (Path f : files.filter(p -> p.toString().endsWith(".json")).toList()) {
                            String rel = rules.relativize(f).toString().replace('\\', '/');
                            editorIds.add(ns.getFileName() + ":" + rel.substring(0, rel.length() - 5));
                        }
                    }
                }
            }
        }
        editorIds.sort(null);
        editorIds.forEach(editor::add);
        o.add("editor", editor);
        JsonArray onDisk = new JsonArray(), other = new JsonArray();
        for (String sid : RuleManager.sourceIds().stream().filter(id -> !editorIds.contains(id)).sorted().toList()) {
            ResourceLocation rl = ResourceLocation.tryParse(sid);
            Path root = rl == null ? null : rootOf(server, rl);
            if (root != null) {
                JsonObject e = new JsonObject();
                e.addProperty("id", sid);
                e.addProperty("where", label(server, root));
                onDisk.add(e);
            } else other.add(sid);
        }
        // also list rule files sitting in those folders that are not loaded (yet, or because of an error)
        java.util.Set<String> listed = new java.util.HashSet<>(editorIds);
        onDisk.forEach(e -> listed.add(e.getAsJsonObject().get("id").getAsString()));
        for (Path root : dataRoots(server)) {
            if (root.equals(editorData(server)) || !Files.isDirectory(root)) continue;
            try (Stream<Path> nss = Files.list(root)) {
                for (Path ns : nss.toList()) {
                    Path rules = ns.resolve("mobrules");
                    if (!Files.isDirectory(rules)) continue;
                    try (Stream<Path> files = Files.walk(rules)) {
                        for (Path f : files.filter(x -> x.toString().endsWith(".json")).toList()) {
                            String rel = rules.relativize(f).toString().replace('\\', '/');
                            String fid = ns.getFileName() + ":" + rel.substring(0, rel.length() - 5);
                            if (listed.add(fid)) {
                                JsonObject e = new JsonObject();
                                e.addProperty("id", fid);
                                e.addProperty("where", label(server, root) + " (not loaded)");
                                onDisk.add(e);
                            }
                        }
                    }
                }
            }
        }
        o.add("on_disk", onDisk);
        o.add("other", other);
        JsonArray errs = new JsonArray();
        RuleManager.errors().forEach(errs::add);
        o.add("errors", errs);
        JsonArray loaded = new JsonArray();
        RuleManager.rules().forEach(rule -> loaded.add(rule.id.toString()));
        o.add("loaded", loaded);
        return MiniHttp.Response.json(GSON.toJson(o));
    }

    private static MiniHttp.Response getRule(MinecraftServer server, String idStr) throws IOException {
        ResourceLocation id = ruleId(idStr);
        Path root = rootOf(server, id);
        JsonObject o = new JsonObject();
        if (root != null) {
            o.add("rule", JsonParser.parseString(Files.readString(ruleFile(root, id))));
            o.addProperty("readonly", false);
            o.addProperty("where", label(server, root));
        } else {
            JsonElement src = RuleManager.source(id.toString());
            if (src == null) throw new BadRequest("no rule " + id);
            o.add("rule", src);
            o.addProperty("readonly", true);
        }
        return MiniHttp.Response.json(GSON.toJson(o));
    }

    private static MiniHttp.Response putRule(MinecraftServer server, String idStr, String body) throws IOException {
        ResourceLocation id = ruleId(idStr);
        JsonElement json = JsonParser.parseString(body);
        if (!json.isJsonObject()) throw new BadRequest("rule must be a JSON object");
        ensurePack(server);
        Path root = rootOf(server, id);
        if (root == null) root = editorData(server);
        Path f = ruleFile(root, id);
        Files.createDirectories(f.getParent());
        Files.writeString(f, GSON.toJson(json) + "\n", StandardCharsets.UTF_8);
        writeBiomeModifiers(root, id, json.getAsJsonObject());
        writeStructureSpawns(server, root, json.getAsJsonObject());
        JsonObject o = new JsonObject();
        o.addProperty("saved", f.toString());
        o.addProperty("biome_modifiers_changed", true);
        return MiniHttp.Response.json(GSON.toJson(o));
    }

    /**
     * The editor keeps biome spawning under "_editor.biome_spawns" in the rule (the rule loader ignores
     * "_" keys) and this turns it into NeoForge biome modifier files next to the rule.
     */
    private static void writeBiomeModifiers(Path root, ResourceLocation id, JsonObject rule) throws IOException {
        Path add = modifierFile(root, id, ""), remove = modifierFile(root, id, "_remove");
        Files.deleteIfExists(add);
        Files.deleteIfExists(remove);
        if (!rule.has("_editor") || !rule.getAsJsonObject("_editor").has("biome_spawns")) return;
        JsonObject bs = rule.getAsJsonObject("_editor").getAsJsonObject("biome_spawns");
        String mob = bs.has("mob") ? bs.get("mob").getAsString() : null;
        if (mob == null || mob.isEmpty()) return;
        Files.createDirectories(add.getParent());
        if (bs.has("add_biomes") && bs.getAsJsonArray("add_biomes").size() > 0) {
            JsonObject m = new JsonObject();
            m.addProperty("type", "neoforge:add_spawns");
            m.add("biomes", holderSet(bs.getAsJsonArray("add_biomes")));
            JsonObject sp = new JsonObject();
            sp.addProperty("type", mob);
            sp.addProperty("weight", bs.has("weight") ? bs.get("weight").getAsInt() : 50);
            sp.addProperty("minCount", bs.has("min") ? bs.get("min").getAsInt() : 1);
            sp.addProperty("maxCount", bs.has("max") ? bs.get("max").getAsInt() : 4);
            m.add("spawners", sp);
            Files.writeString(add, GSON.toJson(m) + "\n");
        }
        if (bs.has("remove_biomes") && bs.getAsJsonArray("remove_biomes").size() > 0) {
            JsonObject m = new JsonObject();
            m.addProperty("type", "neoforge:remove_spawns");
            m.add("biomes", holderSet(bs.getAsJsonArray("remove_biomes")));
            m.addProperty("entity_types", mob);
            Files.writeString(remove, GSON.toJson(m) + "\n");
        }
    }

    /**
     * "_editor.structure_spawns": [{structure, mob, weight, min, max, category}] -> vanilla structure
     * spawn overrides (how fortresses get blazes). Writes an override of the structure's own JSON with the
     * mobs added to its spawn list; applies after the world is reloaded.
     */
    private static void writeStructureSpawns(MinecraftServer server, Path root, JsonObject rule) throws IOException {
        if (!rule.has("_editor") || !rule.getAsJsonObject("_editor").has("structure_spawns")) return;
        var ops = net.minecraft.resources.RegistryOps.create(com.mojang.serialization.JsonOps.INSTANCE, server.registryAccess());
        var reg = server.registryAccess().registryOrThrow(net.minecraft.core.registries.Registries.STRUCTURE);
        for (JsonElement el : rule.getAsJsonObject("_editor").getAsJsonArray("structure_spawns")) {
            JsonObject sp = el.getAsJsonObject();
            if (!sp.has("structure") || !sp.has("mob")) continue;
            ResourceLocation sid = ResourceLocation.parse(sp.get("structure").getAsString());
            var holder = reg.getHolder(sid);
            if (holder.isEmpty()) throw new BadRequest("unknown structure " + sid);
            Path out = root.resolve(sid.getNamespace()).resolve("worldgen").resolve("structure").resolve(sid.getPath() + ".json");
            JsonObject def = Files.exists(out) ? JsonParser.parseString(Files.readString(out)).getAsJsonObject()
                    : net.minecraft.world.level.levelgen.structure.Structure.DIRECT_CODEC.encodeStart(ops, holder.get().value())
                            .getOrThrow(m -> new BadRequest("can't read structure " + sid + ": " + m)).getAsJsonObject();
            JsonObject overrides = def.has("spawn_overrides") ? def.getAsJsonObject("spawn_overrides") : new JsonObject();
            String cat = sp.has("category") ? sp.get("category").getAsString() : "monster";
            JsonObject c = overrides.has(cat) ? overrides.getAsJsonObject(cat) : new JsonObject();
            if (!c.has("bounding_box")) c.addProperty("bounding_box", "piece");
            JsonArray spawns = c.has("spawns") ? c.getAsJsonArray("spawns") : new JsonArray();
            JsonArray kept = new JsonArray();
            for (JsonElement x : spawns) if (!x.getAsJsonObject().get("type").getAsString().equals(sp.get("mob").getAsString())) kept.add(x);
            JsonObject entry = new JsonObject();
            entry.addProperty("type", sp.get("mob").getAsString());
            entry.addProperty("weight", sp.has("weight") ? sp.get("weight").getAsInt() : 20);
            entry.addProperty("minCount", sp.has("min") ? sp.get("min").getAsInt() : 1);
            entry.addProperty("maxCount", sp.has("max") ? sp.get("max").getAsInt() : 2);
            kept.add(entry);
            c.add("spawns", kept);
            overrides.add(cat, c);
            def.add("spawn_overrides", overrides);
            Files.createDirectories(out.getParent());
            Files.writeString(out, GSON.toJson(def) + "\n");
        }
    }

    /** ids and #tags -> a biome holder set (string, list, or neoforge:or of both). */
    private static JsonElement holderSet(JsonArray entries) {
        List<String> tags = new ArrayList<>(), ids = new ArrayList<>();
        for (JsonElement e : entries) {
            String s = e.getAsString();
            (s.startsWith("#") ? tags : ids).add(s);
        }
        JsonArray idArr = new JsonArray();
        ids.forEach(idArr::add);
        if (tags.isEmpty()) return ids.size() == 1 ? entries.get(0) : idArr;
        if (ids.isEmpty() && tags.size() == 1) return entries.get(0);
        JsonObject or = new JsonObject();
        or.addProperty("type", "neoforge:or");
        JsonArray values = new JsonArray();
        tags.forEach(values::add);
        if (!ids.isEmpty()) values.add(idArr);
        or.add("values", values);
        return or;
    }

    private static MiniHttp.Response deleteRule(MinecraftServer server, String idStr) throws IOException {
        ResourceLocation id = ruleId(idStr);
        Path root = rootOf(server, id);
        if (root == null) root = editorData(server);
        boolean any = Files.deleteIfExists(ruleFile(root, id));
        any |= Files.deleteIfExists(modifierFile(root, id, ""));
        any |= Files.deleteIfExists(modifierFile(root, id, "_remove"));
        JsonObject o = new JsonObject();
        o.addProperty("deleted", any);
        return MiniHttp.Response.json(GSON.toJson(o));
    }

    private static Rule parse(MinecraftServer server, String id, String body) {
        JsonElement json;
        try {
            json = JsonParser.parseString(body);
        } catch (Exception ex) {
            throw new BadRequest("not valid JSON: " + ex.getMessage());
        }
        try {
            var all = Rule.parseAll(ruleId(id), json, server.registryAccess());
            Rule rule = all.isEmpty() ? null : all.get(0);
            if (rule == null) throw new BadRequest("rule is disabled (\"enabled\": false)");
            return rule;
        } catch (BadRequest ex) {
            throw ex;
        } catch (Exception ex) {
            throw new BadRequest(ex.getMessage() == null ? ex.toString() : ex.getMessage());
        }
    }

    private static MiniHttp.Response validate(MinecraftServer server, String body) {
        parse(server, "mobrules_editor:validate", body);
        return MiniHttp.Response.json("{\"ok\":true}");
    }

    private static MiniHttp.Response reload(MinecraftServer server) throws Exception {
        ensurePack(server);
        server.submit(() -> {
            PackRepository repo = server.getPackRepository();
            repo.reload();
            List<String> selected = new ArrayList<>(repo.getSelectedIds());
            String id = "file/" + PACK;
            if (!selected.contains(id) && repo.getAvailableIds().contains(id)) selected.add(id);
            return server.reloadResources(selected);
        }).thenCompose(f -> f).get(120, TimeUnit.SECONDS);
        JsonObject o = new JsonObject();
        o.addProperty("rules", RuleManager.rules().size());
        JsonArray errs = new JsonArray();
        RuleManager.errors().forEach(errs::add);
        o.add("errors", errs);
        return MiniHttp.Response.json(GSON.toJson(o));
    }

    // ------------------------------------------------------------------ preview and test spawn

    private static EntityType<?> mobType(String id) {
        ResourceLocation rl = ResourceLocation.tryParse(id);
        Optional<EntityType<?>> t = rl == null ? Optional.empty() : BuiltInRegistries.ENTITY_TYPE.getOptional(rl);
        if (t.isEmpty()) throw new BadRequest("unknown mob '" + id + "'");
        return t.get();
    }

    private static MiniHttp.Response preview(Minecraft mc, MinecraftServer server, MiniHttp.Request r) throws Exception {
        EntityType<?> type = mobType(r.q("mob", ""));
        int w = clamp(Integer.parseInt(r.q("w", "384")), 64, 1024), h = clamp(Integer.parseInt(r.q("h", "384")), 64, 1024);
        float yaw = Float.parseFloat(r.q("yaw", "-30")), pitch = Float.parseFloat(r.q("pitch", "8"));
        boolean vanilla = r.q("vanilla", "0").equals("1");
        String body = r.bodyText();
        Rule rule = body.isBlank() ? null : parse(server, r.q("id", "mobrules_editor:preview"), body);
        JsonObject stats = new JsonObject();
        CompoundTag tag = server.submit(() -> {
            ServerPlayer sp = server.getPlayerList().getPlayer(mc.player.getUUID());
            ServerLevel level = sp != null ? sp.serverLevel() : server.overworld();
            Entity e = type.create(level);
            if (!(e instanceof Mob mob)) throw new BadRequest(type.getDescriptionId() + " is not a mob");
            Vec3 at = sp != null ? sp.position() : Vec3.atCenterOf(level.getSharedSpawnPos());
            mob.moveTo(at.x, at.y, at.z, 0f, 0f);
            if (vanilla) {
                @SuppressWarnings("deprecation")
                var ignored = mob.finalizeSpawn(level, level.getCurrentDifficultyAt(mob.blockPosition()), MobSpawnType.COMMAND, null);
            }
            if (rule != null) rule.apply(new MobContext(mob, level), true);
            JsonObject attrs = new JsonObject();
            BuiltInRegistries.ATTRIBUTE.holders().forEach(hh -> {
                var inst = mob.getAttribute(hh);
                if (inst != null) attrs.addProperty(hh.key().location().toString(), inst.getValue());
            });
            stats.add("attributes", attrs);
            stats.addProperty("health", mob.getHealth());
            stats.addProperty("name", mob.getDisplayName().getString());
            CompoundTag t = new CompoundTag();
            mob.saveWithoutId(t);
            mob.discard();
            return t;
        }).get(30, TimeUnit.SECONDS);
        byte[] png = mc.submit(() -> {
            Entity ce = type.create(mc.level);
            if (!(ce instanceof LivingEntity le)) throw new BadRequest("cannot render " + type.getDescriptionId());
            le.load(tag);
            return OffscreenRenderer.entity(le, w, h, yaw, pitch);
        }).get(30, TimeUnit.SECONDS);
        stats.addProperty("png", java.util.Base64.getEncoder().encodeToString(png));
        return MiniHttp.Response.json(GSON.toJson(stats));
    }

    private static MiniHttp.Response spawn(Minecraft mc, MinecraftServer server, MiniHttp.Request r) throws Exception {
        EntityType<?> type = mobType(r.q("mob", ""));
        boolean vanilla = !r.q("vanilla", "1").equals("0");
        String id = r.q("id", "mobrules_editor:test");
        Rule rule = r.bodyText().isBlank() ? null : parse(server, id, r.bodyText());
        String result = server.submit(() -> {
            ServerPlayer sp = server.getPlayerList().getPlayer(mc.player.getUUID());
            if (sp == null) throw new BadRequest("player not found on the server");
            ServerLevel level = sp.serverLevel();
            Entity e = type.create(level);
            if (!(e instanceof Mob mob)) throw new BadRequest(type.getDescriptionId() + " is not a mob");
            Vec3 look = sp.getLookAngle();
            Vec3 at = sp.position().add(look.x * 3, 0, look.z * 3);
            mob.moveTo(at.x, at.y, at.z, sp.getYRot() + 180f, 0f);
            if (vanilla) EventHooks.finalizeMobSpawn(mob, level, level.getCurrentDifficultyAt(mob.blockPosition()), MobSpawnType.COMMAND, null);
            level.addFreshEntity(mob);
            if (rule != null) {
                RuleManager.putOverlay(rule);
                RuleEngine.applyRules(mob, rule);
            }
            return mob.getDisplayName().getString();
        }).get(30, TimeUnit.SECONDS);
        JsonObject o = new JsonObject();
        o.addProperty("spawned", result);
        return MiniHttp.Response.json(GSON.toJson(o));
    }

    private static int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    // ------------------------------------------------------------------ icons and resource packs

    private static MiniHttp.Response icon(Minecraft mc, String itemId, int size) throws Exception {
        int s = clamp(size, 16, 128);
        String key = packGeneration + "|" + itemId + "|" + s;
        byte[] cached = ICONS.get(key);
        if (cached == null) {
            ResourceLocation rl = ResourceLocation.tryParse(itemId);
            Optional<Item> item = rl == null ? Optional.empty() : BuiltInRegistries.ITEM.getOptional(rl);
            if (item.isEmpty()) throw new BadRequest("unknown item " + itemId);
            cached = mc.submit(() -> OffscreenRenderer.item(new ItemStack(item.get()), s)).get(30, TimeUnit.SECONDS);
            ICONS.put(key, cached);
        }
        return new MiniHttp.Response(200, "image/png", cached);
    }

    private static MiniHttp.Response resourcePacks(Minecraft mc) throws Exception {
        JsonArray arr = mc.submit(() -> {
            PackRepository repo = mc.getResourcePackRepository();
            JsonArray a = new JsonArray();
            List<String> selected = new ArrayList<>(repo.getSelectedIds());
            for (Pack p : repo.getAvailablePacks()) {
                if (p.isHidden()) continue;
                JsonObject o = new JsonObject();
                o.addProperty("id", p.getId());
                o.addProperty("title", p.getTitle().getString());
                o.addProperty("description", p.getDescription().getString());
                o.addProperty("selected", selected.contains(p.getId()));
                o.addProperty("order", selected.indexOf(p.getId()));
                o.addProperty("required", p.isRequired());
                a.add(o);
            }
            return a;
        }).get(10, TimeUnit.SECONDS);
        JsonObject o = new JsonObject();
        o.add("packs", arr);
        o.addProperty("generation", packGeneration);
        return MiniHttp.Response.json(GSON.toJson(o));
    }

    /** Body: {"selected": ["vanilla", "file/MyPack.zip", ...]} in load order (last wins). */
    private static MiniHttp.Response setResourcePacks(Minecraft mc, String body) throws Exception {
        JsonObject in = JsonParser.parseString(body).getAsJsonObject();
        List<String> wanted = new ArrayList<>();
        for (JsonElement e : in.getAsJsonArray("selected")) wanted.add(e.getAsString());
        mc.submit(() -> {
            PackRepository repo = mc.getResourcePackRepository();
            List<String> ids = new ArrayList<>();
            for (Pack p : repo.getAvailablePacks()) if (p.isRequired() && !wanted.contains(p.getId())) ids.add(p.getId());
            ids.addAll(wanted);
            repo.setSelected(ids);
            mc.options.updateResourcePacks(repo);
        }).get(10, TimeUnit.SECONDS);
        // wait for the resource reload overlay to finish
        long deadline = System.currentTimeMillis() + 180_000;
        Thread.sleep(300);
        while (mc.getOverlay() != null && System.currentTimeMillis() < deadline) Thread.sleep(200);
        packGeneration++;
        ICONS.clear();
        return resourcePacks(mc);
    }

    // ------------------------------------------------------------------ export

    private static MiniHttp.Response export(MinecraftServer server) throws IOException {
        ensurePack(server);
        Path dir = packDir(server);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes); Stream<Path> walk = Files.walk(dir)) {
            for (Path p : walk.filter(Files::isRegularFile).toList()) {
                zip.putNextEntry(new ZipEntry(dir.relativize(p).toString().replace('\\', '/')));
                Files.copy(p, zip);
                zip.closeEntry();
            }
        }
        return new MiniHttp.Response(200, "application/zip", bytes.toByteArray());
    }
}
