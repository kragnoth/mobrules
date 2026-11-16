package com.kragnoth.mobrules.entity;

import com.kragnoth.mobrules.MobRules;
import java.util.function.Supplier;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.event.entity.EntityAttributeCreationEvent;
import net.neoforged.neoforge.registries.DeferredRegister;

public final class MobRulesEntities {
    private static final DeferredRegister<EntityType<?>> TYPES = DeferredRegister.create(Registries.ENTITY_TYPE, MobRules.MODID);

    public static final Supplier<EntityType<Doppelganger>> DOPPELGANGER = TYPES.register("doppelganger",
            () -> EntityType.Builder.<Doppelganger>of(Doppelganger::new, MobCategory.MONSTER)
                    .sized(0.6F, 1.8F).eyeHeight(1.62F).clientTrackingRange(10).build("doppelganger"));

    public static void register(IEventBus modBus) {
        TYPES.register(modBus);
        modBus.addListener(EntityAttributeCreationEvent.class, e -> e.put(DOPPELGANGER.get(), Doppelganger.createAttributes().build()));
    }

    private MobRulesEntities() {}
}
