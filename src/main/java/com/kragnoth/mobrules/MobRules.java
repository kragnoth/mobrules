package com.kragnoth.mobrules;

import com.mojang.logging.LogUtils;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.AddReloadListenerEvent;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.entity.EntityLeaveLevelEvent;
import net.neoforged.neoforge.event.entity.EntityMobGriefingEvent;
import net.neoforged.neoforge.event.entity.living.LivingChangeTargetEvent;
import net.neoforged.neoforge.event.entity.living.LivingDamageEvent;
import net.neoforged.neoforge.event.entity.living.LivingDropsEvent;
import net.neoforged.neoforge.event.entity.living.LivingExperienceDropEvent;
import net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent;
import net.neoforged.neoforge.event.entity.living.LivingKnockBackEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.EntityTickEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.slf4j.Logger;

/**
 * Mob Rules: datapack JSON rules that change mobs when they spawn and while they live.
 * Rules live in data/<namespace>/mobrules/*.json and reload with /reload.
 */
@Mod(MobRules.MODID)
public final class MobRules {
    public static final String MODID = "mobrules";
    public static final Logger LOGGER = LogUtils.getLogger();
    public static String VERSION = "?";

    public MobRules(IEventBus modBus, ModContainer container) {
        VERSION = container.getModInfo().getVersion().toString();
        container.registerConfig(ModConfig.Type.COMMON, MobRulesConfig.SPEC);
        if (net.neoforged.fml.loading.FMLEnvironment.dist.isClient()) com.kragnoth.mobrules.editor.MobRulesClient.init(modBus, container);
        MobRulesAttachments.register(modBus);

        var bus = NeoForge.EVENT_BUS;
        bus.addListener(AddReloadListenerEvent.class, e -> {
            e.addListener(new RuleManager(e.getRegistryAccess()));
            e.addListener(new WorldEvents());
        });
        bus.addListener(net.neoforged.neoforge.event.server.ServerStoppingEvent.class, e -> RuleEngine.clearTempBlocks());
        bus.addListener(RegisterCommandsEvent.class, e -> MobRulesCommand.register(e.getDispatcher()));
        bus.addListener(EntityJoinLevelEvent.class, RuleEngine::onJoin);
        bus.addListener(EntityLeaveLevelEvent.class, RuleEngine::onLeave);
        bus.addListener(ServerTickEvent.Post.class, RuleEngine::onServerTick);
        bus.addListener(EntityTickEvent.Post.class, RuleEngine::onEntityTick);
        bus.addListener(ServerStoppedEvent.class, RuleEngine::onServerStopped);
        bus.addListener(LivingIncomingDamageEvent.class, RuleEngine::onIncomingDamage);
        bus.addListener(LivingDamageEvent.Post.class, RuleEngine::onDamageDealt);
        bus.addListener(LivingKnockBackEvent.class, RuleEngine::onKnockback);
        bus.addListener(LivingChangeTargetEvent.class, RuleEngine::onChangeTarget);
        bus.addListener(EntityMobGriefingEvent.class, RuleEngine::onGriefing);
        bus.addListener(LivingDropsEvent.class, RuleEngine::onDrops);
        bus.addListener(LivingExperienceDropEvent.class, RuleEngine::onXp);
        bus.addListener(net.neoforged.neoforge.event.entity.living.LivingDeathEvent.class, RuleEngine::onDeath);
        bus.addListener(net.neoforged.neoforge.event.entity.living.FinalizeSpawnEvent.class, RuleEngine::onFinalizeSpawn);
    }
}
