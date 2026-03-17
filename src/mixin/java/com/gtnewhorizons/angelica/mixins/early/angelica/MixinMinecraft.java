package com.gtnewhorizons.angelica.mixins.early.angelica;

import com.gtnewhorizons.angelica.AngelicaMod;
import com.gtnewhorizons.angelica.glsm.ffp.TessellatorStreamingDrawer;
import com.gtnewhorizons.angelica.mixins.interfaces.IGameSettingsExt;
import com.llamalad7.mixinextras.injector.v2.WrapWithCondition;
import net.minecraft.client.Minecraft;
import net.minecraft.client.settings.GameSettings;
import org.embeddedt.embeddium.impl.render.frame.RenderAheadManager;
import org.lwjgl.input.Keyboard;
import org.lwjgl.opengl.Display;
import org.lwjgl.opengl.DisplayMode;
import org.lwjgl.opengl.GL11;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.gtnewhorizons.angelica.config.AngelicaConfig;

@Mixin(Minecraft.class)
public abstract class MixinMinecraft {
    @Shadow
    public GameSettings gameSettings;

    @Shadow
    public abstract boolean isFramerateLimitBelowMax();

    @Shadow
    public abstract int getLimitFramerate();

    @Shadow(remap = false)
    private static int max_texture_size;

    @Unique
    private static long angelica$lastFrameTime = 0;

    @Unique
    private static long angelica$fpsLimitOverhead = 0;

    @Unique
    private final RenderAheadManager celeritas$renderAheadManager = new RenderAheadManager();

    /**
     * @author mitchej123
     * @reason Avoid GL_PROXY_TEXTURE_2D which doesn't work with GLSM's texture binding.
     *         Uses the standard GL_MAX_TEXTURE_SIZE query instead.
     */
    @Overwrite
    public static int getGLMaximumTextureSize() {
        if (max_texture_size == -1) {
            max_texture_size = GL11.glGetInteger(GL11.GL_MAX_TEXTURE_SIZE);
        }
        return max_texture_size;
    }

    @Inject(
        method = "runGameLoop",
        at = @At(value = "INVOKE", target = "Lcpw/mods/fml/common/FMLCommonHandler;onRenderTickEnd(F)V", shift = At.Shift.AFTER, remap = false)
    )
    private void angelica$injectLightingFixPostRenderTick(CallbackInfo ci) {
        GL11.glEnable(GL11.GL_LIGHTING);
    }

    @Inject(
        method = "func_147120_f",
        at = @At(value = "INVOKE", target = "Lorg/lwjgl/opengl/Display;update()V", remap = false)
    )
    private void angelica$limitFPS(CallbackInfo ci) {
        if (AngelicaMod.proxy == null) return;

        if (isFramerateLimitBelowMax() && !gameSettings.enableVsync) {
            final long time = System.nanoTime();
            final long lastWorkTime = time - angelica$lastFrameTime;
            final long targetNanos = (long) (1.0 / getLimitFramerate() * 1_000_000_000L);

            // Account for overhead, so the average FPS remains stable.
            final long sleepNanos = targetNanos - lastWorkTime - angelica$fpsLimitOverhead;
            if (sleepNanos > 0) {
                try {
                    Thread.sleep(sleepNanos / 1_000_000, (int) sleepNanos % 1_000_000);
                } catch (InterruptedException e) {
                    throw new RuntimeException(e);
                }
            }

            // Record overhead, capping it to prevent outsized spikes from affecting framerates for too long.
            // In testing, spikes were followed by 1-frame dips, which I think is acceptable.
            long overhead = System.nanoTime() - time - sleepNanos;
            if (overhead < 0 || overhead > targetNanos / 2) overhead = 0;
            angelica$fpsLimitOverhead = overhead;
        }

        final long time = System.nanoTime();
        AngelicaMod.proxy.putFrametime(time - angelica$lastFrameTime);
        angelica$lastFrameTime = time;
    }

    @WrapWithCondition(method = "runGameLoop", at = @At(value = "INVOKE", target = "Lorg/lwjgl/opengl/Display;sync(I)V", remap = false))
    private boolean angelica$noopFPSLimiter(int fps) {
        return false;
    }

    @Inject(method = "runTick", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/GuiScreen;isShiftKeyDown()Z", shift = At.Shift.AFTER))
    private void angelica$setShowFpsGraph(CallbackInfo ci) {
        ((IGameSettingsExt) gameSettings).angelica$setShowFpsGraph(Keyboard.isKeyDown(Keyboard.KEY_LMENU) || Keyboard.isKeyDown(Keyboard.KEY_RMENU));
    }

    @Inject(method = "runTick", at = @At("HEAD"))
    private void celeritas$renderAheadStartFrame(CallbackInfo ci) {
        final int limit = AngelicaMod.options().performance.cpuRenderAheadLimit;
        if (limit > 0) {
            celeritas$renderAheadManager.startFrame(limit);
        }
    }

    @Inject(method = "runTick", at = @At("RETURN"))
    private void celeritas$renderAheadEndFrame(CallbackInfo ci) {
        if (AngelicaMod.options().performance.cpuRenderAheadLimit > 0) {
            celeritas$renderAheadManager.endFrame();
        }
    }

    @Inject(method = "runTick", at = @At("RETURN"))
    private void angelica$streamingBufferEndFrame(CallbackInfo ci) {
        TessellatorStreamingDrawer.endFrame();
    }

    @WrapOperation(method = "updateDisplayMode", at = @At(value = "INVOKE", target = "Lorg/lwjgl/opengl/Display;setDisplayMode(Lorg/lwjgl/opengl/DisplayMode;)V", remap = false))
    private void angelica$customFullscreenMode(DisplayMode desktopMode, Operation<Void> original) {
        DisplayMode targetMode = desktopMode;
        if (!"Current".equals(AngelicaConfig.fullscreenResolution) && AngelicaConfig.fullscreenResolution != null) {
            String[] parts = AngelicaConfig.fullscreenResolution.split("@");
            if (parts.length == 2) {
                String[] res = parts[0].split("x");
                if (res.length == 2) {
                    try {
                        int width = Integer.parseInt(res[0]);
                        int height = Integer.parseInt(res[1]);
                        int freq = Integer.parseInt(parts[1].replace("Hz", ""));
                        DisplayMode[] modes = Display.getAvailableDisplayModes();
                        DisplayMode bestMode = null;
                        for (DisplayMode mode : modes) {
                            if (mode.getWidth() == width && mode.getHeight() == height && mode.getFrequency() == freq) {
                                if (bestMode == null || mode.getBitsPerPixel() > bestMode.getBitsPerPixel()) {
                                    bestMode = mode;
                                }
                            }
                        }
                        if (bestMode != null) {
                            targetMode = bestMode;
                        }
                    } catch (Exception e) {}
                }
            }
        }

        try {
            // apply lwjgl3ify SDL3 fullscreen mode if present
            Class<?> displayClass = Class.forName("org.lwjglx.opengl.Display");
            long sdlWindow = (Long) displayClass.getMethod("getWindow").invoke(null);

            Class<?> sdlVideoClass = Class.forName("org.lwjgl.sdl.SDLVideo");
            Class<?> sdlDisplayModeClass = Class.forName("org.lwjgl.sdl.SDL_DisplayMode");

            int monitor = (Integer) sdlVideoClass.getMethod("SDL_GetPrimaryDisplay").invoke(null);

            Object modes = null;
            try {
                modes = sdlVideoClass.getMethod("SDL_GetFullscreenDisplayModes", int.class).invoke(null, monitor);
            } catch (Exception e) {
                modes = sdlVideoClass.getMethod("SDL_GetFullscreenDisplayModes", int.class, java.nio.IntBuffer.class).invoke(null, monitor, null);
            }

            if (modes != null) {
                int remaining = (Integer) modes.getClass().getMethod("remaining").invoke(modes);
                Object targetSdlMode = null;
                for (int i = 0; i < remaining; i++) {
                    long ptr = (Long) modes.getClass().getMethod("get", int.class).invoke(modes, i);
                    Object sdlMode = sdlDisplayModeClass.getMethod("create", long.class).invoke(null, ptr);
                    int width = (Integer) sdlDisplayModeClass.getMethod("w").invoke(sdlMode);
                    int height = (Integer) sdlDisplayModeClass.getMethod("h").invoke(sdlMode);
                    float refresh = (Float) sdlDisplayModeClass.getMethod("refresh_rate").invoke(sdlMode);

                    if (width == targetMode.getWidth() && height == targetMode.getHeight() && Math.round(refresh) == targetMode.getFrequency()) {
                        targetSdlMode = sdlMode;
                        break;
                    }
                }
                if (targetSdlMode != null) {
                    sdlVideoClass.getMethod("SDL_SetWindowFullscreenMode", long.class, sdlDisplayModeClass).invoke(null, sdlWindow, targetSdlMode);
                }
            }
        } catch (Throwable t) {}

        original.call(targetMode);
    }
}
