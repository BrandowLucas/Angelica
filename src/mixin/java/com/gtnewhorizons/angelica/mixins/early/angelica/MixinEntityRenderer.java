package com.gtnewhorizons.angelica.mixins.early.angelica;

import com.gtnewhorizons.angelica.config.AngelicaConfig;
import com.gtnewhorizons.angelica.compat.mojang.Camera;
import com.gtnewhorizons.angelica.mixins.interfaces.EntityRendererAccessor;
import com.gtnewhorizons.angelica.rendering.RenderingState;
import jss.notfine.core.Settings;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.EntityRenderer;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.player.EntityPlayer;
import org.lwjgl.opengl.Display;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.ModifyConstant;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(EntityRenderer.class)
public abstract class MixinEntityRenderer implements EntityRendererAccessor {

    @Invoker
    public abstract float invokeGetNightVisionBrightness(EntityPlayer entityPlayer, float partialTicks);

    @Accessor("lightmapTexture")
    public abstract DynamicTexture getLightmapTexture();

    @Inject(method = "renderWorld", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/ActiveRenderInfo;updateRenderInfo(Lnet/minecraft/entity/player/EntityPlayer;Z)V", shift = At.Shift.AFTER))
    private void angelica$captureCameraMatrix(float partialTicks, long finishTimeNano, CallbackInfo ci) {
        final Minecraft mc = Minecraft.getMinecraft();
        final EntityLivingBase viewEntity = mc.renderViewEntity;

        Camera.INSTANCE.update(viewEntity, partialTicks);

        // Use entity eye position for cameraPosition uniform, not the third-person camera position.
        RenderingState.INSTANCE.setCameraPosition(
            Camera.INSTANCE.getEntityPos().x,
            Camera.INSTANCE.getEntityPos().y,
            Camera.INSTANCE.getEntityPos().z
        );
    }

    @ModifyArg(method = "setupCameraTransform", at = @At(value = "INVOKE", target = "Lorg/lwjgl/util/glu/Project;gluPerspective(FFFF)V", ordinal = 0, remap = false), index = 0)
    private float captureFov(float fov) {
        RenderingState.INSTANCE.setFov(fov);
        return fov;
    }

    @ModifyConstant(method = "hurtCameraEffect", constant = @Constant(floatValue = 14.0F))
    private float angelica$hurtCameraEffect(float orig) {
        int value = (int) Settings.HURT_SHAKE.option.getStore();
        return orig * (value/100F);
    }

    @Inject(method = "updateCameraAndRender", at = @At(value = "INVOKE", target = "Lnet/minecraft/util/MouseHelper;mouseXYChange()V", shift = At.Shift.AFTER))
    private void angelica$scaleMouseDPI(float partialTicks, CallbackInfo ci) {
        if (AngelicaConfig.enableResolutionScalingMouseFix && Display.isFullscreen()) {
            Minecraft mc = Minecraft.getMinecraft();
            int currentWidth = Display.getDisplayMode().getWidth();
            int desktopWidth = Display.getDesktopDisplayMode().getWidth();
            if (currentWidth > 0 && desktopWidth > 0 && currentWidth != desktopWidth) {
                float scale = (float) desktopWidth / (float) currentWidth;
                mc.mouseHelper.deltaX = Math.round(mc.mouseHelper.deltaX * scale);
                mc.mouseHelper.deltaY = Math.round(mc.mouseHelper.deltaY * scale);

                // Support for lwjgl3ify floating point mouse deltas via reflection (TODO improve this)
                try {
                    java.lang.reflect.Method mDX = mc.mouseHelper.getClass().getMethod("lwjgl3ify$getFloatDX");
                    java.lang.reflect.Method mDY = mc.mouseHelper.getClass().getMethod("lwjgl3ify$getFloatDY");
                    java.lang.reflect.Method mSetDX = mc.mouseHelper.getClass().getMethod("lwjgl3ify$setFloatDX", float.class);
                    java.lang.reflect.Method mSetDY = mc.mouseHelper.getClass().getMethod("lwjgl3ify$setFloatDY", float.class);

                    float curDX = (Float) mDX.invoke(mc.mouseHelper);
                    float curDY = (Float) mDY.invoke(mc.mouseHelper);

                    mSetDX.invoke(mc.mouseHelper, curDX * scale);
                    mSetDY.invoke(mc.mouseHelper, curDY * scale);
                } catch (Exception e) {
                    try {
                        java.lang.reflect.Field fDX = mc.mouseHelper.getClass().getDeclaredField("lwjgl3ify$deltaX");
                        java.lang.reflect.Field fDY = mc.mouseHelper.getClass().getDeclaredField("lwjgl3ify$deltaY");
                        fDX.setAccessible(true);
                        fDY.setAccessible(true);
                        fDX.setFloat(mc.mouseHelper, fDX.getFloat(mc.mouseHelper) * scale);
                        fDY.setFloat(mc.mouseHelper, fDY.getFloat(mc.mouseHelper) * scale);
                    } catch (Exception ignored) {}
                }
            }
        }
    }

    @Inject(method = "updateCameraAndRender", at = @At("TAIL"))
    private void angelica$renderFPS(float partialTicks, CallbackInfo ci) {
        if (AngelicaConfig.showFPS) {
            Minecraft mc = Minecraft.getMinecraft();
            if (mc.fontRenderer != null && mc.gameSettings != null && !mc.gameSettings.showDebugInfo) {
                int fpsVal = 0;
                try {
                    java.lang.reflect.Field f = Minecraft.class.getDeclaredField("field_71470_ab");
                    f.setAccessible(true);
                    fpsVal = f.getInt(null);
                } catch (Exception e) {
                    try {
                        java.lang.reflect.Field f = Minecraft.class.getDeclaredField("debugFPS");
                        f.setAccessible(true);
                        fpsVal = f.getInt(null);
                    } catch (Exception e2) {
                        try {
                            java.lang.reflect.Field f = Minecraft.class.getDeclaredField("ac");
                            f.setAccessible(true);
                            fpsVal = f.getInt(null);
                        } catch (Exception e3) {}
                    }
                }

                String fps = fpsVal + " FPS";
                org.lwjgl.opengl.GL11.glDisable(org.lwjgl.opengl.GL11.GL_LIGHTING);
                org.lwjgl.opengl.GL11.glDisable(org.lwjgl.opengl.GL11.GL_DEPTH_TEST);
                mc.fontRenderer.drawString(fps, 2, 2, 0xFFFFFFFF, false);
            }
        }
    }
}
