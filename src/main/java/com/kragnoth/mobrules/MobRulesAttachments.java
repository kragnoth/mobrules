package com.kragnoth.mobrules;

import com.mojang.serialization.Codec;
import java.util.List;
import java.util.function.Supplier;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.attachment.AttachmentType;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.neoforged.neoforge.registries.NeoForgeRegistries;

public final class MobRulesAttachments {
    private static final DeferredRegister<AttachmentType<?>> TYPES = DeferredRegister.create(NeoForgeRegistries.ATTACHMENT_TYPES, MobRules.MODID);

    /** Ids of the rules applied to a mob. Present (even if empty) once the mob has been checked. */
    public static final Supplier<AttachmentType<List<String>>> APPLIED = TYPES.register("applied",
            () -> AttachmentType.<List<String>>builder(() -> List.of()).serialize(Codec.STRING.listOf()).build());

    static void register(IEventBus modBus) {
        TYPES.register(modBus);
    }

    private MobRulesAttachments() {}
}
