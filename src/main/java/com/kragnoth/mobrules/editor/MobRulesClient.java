package com.kragnoth.mobrules.editor;

import com.kragnoth.mobrules.MobRules;
import com.mojang.brigadier.Command;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.Style;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.RegisterClientCommandsEvent;
import net.neoforged.neoforge.common.NeoForge;

/** Client-only setup: the /mobrules_editor command and the editor's lifetime. */
public final class MobRulesClient {
    private MobRulesClient() {}

    public static void init(IEventBus modBus, ModContainer container) {
        container.registerConfig(ModConfig.Type.CLIENT, MobRulesClientConfig.SPEC);
        NeoForge.EVENT_BUS.addListener(RegisterClientCommandsEvent.class, e -> e.getDispatcher().register(
                Commands.literal("mobrules_editor").executes(c -> {
                    open();
                    return Command.SINGLE_SUCCESS;
                })));
        NeoForge.EVENT_BUS.addListener(ClientPlayerNetworkEvent.LoggingOut.class, e -> EditorBridge.stop());
        if (Boolean.getBoolean("mobrules.editor.autostart")) {
            // development/testing only: start on join and log the full link
            NeoForge.EVENT_BUS.addListener(ClientPlayerNetworkEvent.LoggingIn.class, e -> {
                try {
                    MobRules.LOGGER.info("[Mob Rules] Editor autostart: {}", EditorBridge.start());
                } catch (Exception ex) {
                    MobRules.LOGGER.error("[Mob Rules] editor autostart failed", ex);
                }
            });
        }
    }

    private static void open() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;
        if (!MobRulesClientConfig.ENABLED.get()) {
            mc.player.displayClientMessage(Component.literal("The Mob Rules editor is turned off in mobrules-client.toml.").withStyle(ChatFormatting.RED), false);
            return;
        }
        if (mc.getSingleplayerServer() == null) {
            mc.player.displayClientMessage(Component.literal("The Mob Rules editor works in singleplayer or when hosting a LAN world. "
                    + "Build rules there, then copy the datapack to your server.").withStyle(ChatFormatting.YELLOW), false);
            return;
        }
        try {
            String url = EditorBridge.start();
            mc.player.displayClientMessage(Component.literal("Mob Rules editor: ").withStyle(ChatFormatting.GOLD)
                    .append(Component.literal("[open in browser]").withStyle(Style.EMPTY.withColor(ChatFormatting.AQUA).withUnderlined(true)
                            .withClickEvent(new ClickEvent(ClickEvent.Action.OPEN_URL, url))
                            .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, Component.literal(url))))), false);
            mc.player.displayClientMessage(Component.literal("Only this computer can use it, and the link stops working when you leave the world.")
                    .withStyle(ChatFormatting.GRAY), false);
        } catch (Exception ex) {
            MobRules.LOGGER.error("[Mob Rules] could not start the editor", ex);
            mc.player.displayClientMessage(Component.literal("Could not start the editor: " + ex.getMessage()).withStyle(ChatFormatting.RED), false);
        }
    }
}
