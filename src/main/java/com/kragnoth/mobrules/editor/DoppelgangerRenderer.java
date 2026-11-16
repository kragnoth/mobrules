package com.kragnoth.mobrules.editor;

import com.kragnoth.mobrules.entity.Doppelganger;
import com.mojang.blaze3d.vertex.PoseStack;
import java.util.UUID;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.HumanoidArmorModel;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.PlayerModel;
import net.minecraft.client.model.geom.ModelLayers;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.HumanoidMobRenderer;
import net.minecraft.client.renderer.entity.layers.HumanoidArmorLayer;
import net.minecraft.client.resources.DefaultPlayerSkin;
import net.minecraft.client.resources.PlayerSkin;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.BowItem;

/** Draws a doppelganger with its owner's skin (slim or wide arms) and the player's armor layers. */
public class DoppelgangerRenderer extends HumanoidMobRenderer<Doppelganger, PlayerModel<Doppelganger>> {
    private final PlayerModel<Doppelganger> wide;
    private final PlayerModel<Doppelganger> slim;

    public DoppelgangerRenderer(EntityRendererProvider.Context ctx) {
        super(ctx, new PlayerModel<>(ctx.bakeLayer(ModelLayers.PLAYER), false), 0.5F);
        wide = model;
        slim = new PlayerModel<>(ctx.bakeLayer(ModelLayers.PLAYER_SLIM), true);
        addLayer(new HumanoidArmorLayer<>(this,
                new HumanoidArmorModel<>(ctx.bakeLayer(ModelLayers.PLAYER_INNER_ARMOR)),
                new HumanoidArmorModel<>(ctx.bakeLayer(ModelLayers.PLAYER_OUTER_ARMOR)),
                ctx.getModelManager()));
    }

    static PlayerSkin skin(Doppelganger d) {
        UUID id = d.getOwnerUUID();
        if (id == null) id = d.getUUID();
        ClientPacketListener conn = Minecraft.getInstance().getConnection();
        PlayerInfo info = conn == null ? null : conn.getPlayerInfo(id);
        return info != null ? info.getSkin() : DefaultPlayerSkin.get(id);
    }

    @Override
    public void render(Doppelganger d, float yaw, float partialTick, PoseStack pose, MultiBufferSource buffers, int light) {
        model = skin(d).model() == PlayerSkin.Model.SLIM ? slim : wide;
        HumanoidModel.ArmPose main = d.getMainHandItem().isEmpty() ? HumanoidModel.ArmPose.EMPTY
                : d.isAggressive() && d.getMainHandItem().getItem() instanceof BowItem ? HumanoidModel.ArmPose.BOW_AND_ARROW
                : HumanoidModel.ArmPose.ITEM;
        HumanoidModel.ArmPose off = d.getOffhandItem().isEmpty() ? HumanoidModel.ArmPose.EMPTY : HumanoidModel.ArmPose.ITEM;
        boolean right = d.getMainArm() == net.minecraft.world.entity.HumanoidArm.RIGHT;
        model.rightArmPose = right ? main : off;
        model.leftArmPose = right ? off : main;
        super.render(d, yaw, partialTick, pose, buffers, light);
    }

    @Override
    public ResourceLocation getTextureLocation(Doppelganger d) {
        return skin(d).texture();
    }
}
