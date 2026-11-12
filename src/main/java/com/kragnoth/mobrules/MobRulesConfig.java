package com.kragnoth.mobrules;

import net.neoforged.neoforge.common.ModConfigSpec;

/** config/mobrules-common.toml */
public final class MobRulesConfig {
    public static final ModConfigSpec SPEC;
    public static final ModConfigSpec.BooleanValue DEBUG;
    public static final ModConfigSpec.BooleanValue PROCESS_EXISTING;
    public static final ModConfigSpec.IntValue MAX_PER_TICK;
    public static final ModConfigSpec.IntValue SPAWNER_CAP;

    static {
        ModConfigSpec.Builder b = new ModConfigSpec.Builder();
        DEBUG = b.comment("Log every rule applied to a mob.").define("debug", false);
        PROCESS_EXISTING = b.comment("Apply rules to mobs saved before Mob Rules was installed (they have never been checked).",
                "If false, those mobs are left alone and only new spawns are affected.").define("processExisting", true);
        MAX_PER_TICK = b.comment("Most mobs processed per server tick (big chunk loads are spread out).")
                .defineInRange("maxPerTick", 200, 1, 10000);
        SPAWNER_CAP = b.comment("Safety cap: custom spawners stop adding mobs while this many spawner-made mobs are within 128 blocks of a player (all rules together).")
                .defineInRange("spawnerCapPerPlayer", 12, 0, 500);
        SPEC = b.build();
    }

    private MobRulesConfig() {}
}
