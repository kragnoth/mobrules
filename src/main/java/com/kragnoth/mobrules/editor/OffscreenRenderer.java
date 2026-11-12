package com.kragnoth.mobrules.editor;

import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.platform.Lighting;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.VertexSorting;
import java.io.IOException;
import java.io.UncheckedIOException;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import org.joml.Matrix4f;
import org.joml.Matrix4fStack;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/**
 * Renders entities and items into an off-screen texture using the game's own renderers, so modded
 * models, armor layers and the currently loaded resource packs all show exactly as in game.
 * Must be called on the render thread.
 */
final class OffscreenRenderer {
    private static TextureTarget target;

    private OffscreenRenderer() {}

    private static TextureTarget target(int w, int h) {
        if (target == null || target.width != w || target.height != h) {
            if (target != null) target.destroyBuffers();
            target = new TextureTarget(w, h, true, Minecraft.ON_OSX);
        }
        return target;
    }

    private interface Draw {
        void draw(GuiGraphics g);
    }

    private static byte[] capture(int w, int h, Draw draw) {
        Minecraft mc = Minecraft.getInstance();
        TextureTarget t = target(w, h);
        t.setClearColor(0f, 0f, 0f, 0f);
        t.clear(Minecraft.ON_OSX);
        t.bindWrite(true);

        RenderSystem.backupProjectionMatrix();
        RenderSystem.setProjectionMatrix(new Matrix4f().setOrtho(0f, w, h, 0f, 1000f, 21000f), VertexSorting.ORTHOGRAPHIC_Z);
        Matrix4fStack mv = RenderSystem.getModelViewStack();
        mv.pushMatrix();
        mv.identity();
        mv.translate(0f, 0f, -11000f);
        RenderSystem.applyModelViewMatrix();
        try {
            GuiGraphics g = new GuiGraphics(mc, mc.renderBuffers().bufferSource());
            draw.draw(g);
            g.flush();
        } finally {
            mv.popMatrix();
            RenderSystem.applyModelViewMatrix();
            RenderSystem.restoreProjectionMatrix();
            Lighting.setupFor3DItems();
        }

        NativeImage img = new NativeImage(w, h, false);
        try {
            RenderSystem.bindTexture(t.getColorTextureId());
            img.downloadTexture(0, false);
            img.flipY();
            return img.asByteArray();
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        } finally {
            img.close();
            mc.getMainRenderTarget().bindWrite(true);
        }
    }

    /**
     * @param yaw   degrees, 0 = facing the camera
     * @param pitch degrees, positive looks down on the mob
     */
    static byte[] entity(LivingEntity e, int w, int h, float yaw, float pitch) {
        return capture(w, h, g -> {
            float bbH = e.getBbHeight(), bbW = e.getBbWidth();
            float extent = Math.max(bbH, bbW * 1.3f);
            float scale = Math.min(w, h) * 0.62f / Math.max(0.3f, extent);
            e.yBodyRot = 180f + yaw;
            e.yBodyRotO = e.yBodyRot;
            e.setYRot(180f + yaw);
            e.yRotO = e.getYRot();
            e.setXRot(0f);
            e.xRotO = 0f;
            e.yHeadRot = e.getYRot();
            e.yHeadRotO = e.yHeadRot;
            Quaternionf pose = new Quaternionf().rotateZ((float) Math.PI);
            Quaternionf camera = new Quaternionf().rotateX(pitch * (float) (Math.PI / 180.0));
            pose.mul(camera);
            InventoryScreen.renderEntityInInventory(g, w / 2f, h * 0.56f, scale,
                    new Vector3f(0f, bbH / 2f + 0.0625f, 0f), pose, camera, e);
        });
    }

    static byte[] item(ItemStack stack, int size) {
        return capture(size, size, g -> {
            g.pose().pushPose();
            g.pose().scale(size / 16f, size / 16f, 1f);
            g.renderFakeItem(stack, 0, 0);
            g.pose().popPose();
        });
    }
}
