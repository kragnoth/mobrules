package com.kragnoth.mobrules.editor;

import net.neoforged.neoforge.common.ModConfigSpec;

/** config/mobrules-client.toml */
public final class MobRulesClientConfig {
    public static final ModConfigSpec SPEC;
    public static final ModConfigSpec.BooleanValue ENABLED;
    public static final ModConfigSpec.IntValue PORT;

    static {
        ModConfigSpec.Builder b = new ModConfigSpec.Builder();
        ENABLED = b.comment("Allow /mobrules_editor to start the browser editor (localhost only, needs the link's token).")
                .define("editorEnabled", true);
        PORT = b.comment("Preferred port for the editor (a free one is picked if it's taken).")
                .defineInRange("editorPort", 25599, 1024, 65535);
        SPEC = b.build();
    }

    private MobRulesClientConfig() {}
}
