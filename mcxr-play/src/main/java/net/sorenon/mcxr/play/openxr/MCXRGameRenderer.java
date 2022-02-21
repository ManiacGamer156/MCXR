package net.sorenon.mcxr.play.openxr;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.platform.GlConst;
import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.shaders.Uniform;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.math.Matrix4f;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.sorenon.mcxr.core.MCXRCore;
import net.sorenon.mcxr.play.MCXRGuiManager;
import net.sorenon.mcxr.play.MCXRPlayClient;
import net.sorenon.mcxr.play.accessor.MinecraftExt;
import net.sorenon.mcxr.play.input.XrInput;
import net.sorenon.mcxr.play.rendering.MCXRMainTarget;
import net.sorenon.mcxr.play.rendering.RenderPass;
import net.sorenon.mcxr.play.rendering.MCXRCamera;
import net.sorenon.mcxr.play.rendering.XrRenderTarget;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.lwjgl.PointerBuffer;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.openxr.*;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.Struct;

import java.nio.IntBuffer;

import static org.lwjgl.system.MemoryStack.stackCallocInt;
import static org.lwjgl.system.MemoryStack.stackPush;

public class MCXRGameRenderer {
    private static final Logger LOGGER = LogManager.getLogger();
    private Minecraft client;
    private MinecraftExt clientExt;
    private MCXRMainTarget mainRenderTarget;
    private MCXRCamera camera;

    private OpenXRInstance instance;
    private OpenXRSession session;

    public RenderPass renderPass = RenderPass.VANILLA;
    public ShaderInstance blitShader;
    public ShaderInstance guiBlitShader;

    public void initialize(Minecraft client) {
        this.client = client;
        this.clientExt = (MinecraftExt) client;
        mainRenderTarget = (MCXRMainTarget) client.getMainRenderTarget();
        camera = (MCXRCamera) client.gameRenderer.getMainCamera();
    }

    public void setSession(OpenXRSession session) {
        this.session = session;
        this.instance = session.instance;
    }

    public boolean isXrMode() {
        return Minecraft.getInstance().level != null && session != null;
    }

    public void renderFrame() {
        try (MemoryStack stack = stackPush()) {
            var frameState = XrFrameState.calloc(stack).type(XR10.XR_TYPE_FRAME_STATE);

            if (isXrMode()) {
                GLFW.glfwSwapBuffers(Minecraft.getInstance().getWindow().getWindow());
            }
            //TODO tick game and poll input during xrWaitFrame (this might not work due to the gl context belonging to the xrWaitFrame thread)
            instance.checkPanic(XR10.xrWaitFrame(
                    session.handle,
                    XrFrameWaitInfo.calloc(stack).type(XR10.XR_TYPE_FRAME_WAIT_INFO),
                    frameState
            ), "xrWaitFrame");

            instance.checkPanic(XR10.xrBeginFrame(
                    session.handle,
                    XrFrameBeginInfo.calloc(stack).type(XR10.XR_TYPE_FRAME_BEGIN_INFO)
            ), "xrBeginFrame");

            PointerBuffer layers = stack.callocPointer(1);

            if (frameState.shouldRender()) {
                if (this.isXrMode()) {
                    var layer = renderXrGame(frameState.predictedDisplayTime(), stack);
                    if (layer != null) {
                        layers.put(layer.address());
                    }
                } else {
                    var layer = renderBlankLayer(frameState.predictedDisplayTime(), stack);
                    layers.put(layer.address());
                }
            }
            layers.flip();

            int result = XR10.xrEndFrame(
                    session.handle,
                    XrFrameEndInfo.calloc(stack)
                            .type(XR10.XR_TYPE_FRAME_END_INFO)
                            .displayTime(frameState.predictedDisplayTime())
                            .environmentBlendMode(XR10.XR_ENVIRONMENT_BLEND_MODE_OPAQUE)
                            .layers(layers)
            );
            if (result != XR10.XR_ERROR_TIME_INVALID) {
                instance.checkPanic(result, "xrEndFrame");
            } else {
                LOGGER.warn("Rendering frame took too long! (probably)");
            }
        }
    }

    private Struct renderXrGame(long predictedDisplayTime, MemoryStack stack) {
//        try (MemoryStack stack = stackPush()) {

        XrViewState viewState = XrViewState.calloc(stack).type(XR10.XR_TYPE_VIEW_STATE);
        IntBuffer intBuf = stackCallocInt(1);

        XrViewLocateInfo viewLocateInfo = XrViewLocateInfo.calloc(stack);
        viewLocateInfo.set(XR10.XR_TYPE_VIEW_LOCATE_INFO,
                0,
                session.viewConfigurationType,
                predictedDisplayTime,
                session.xrAppSpace
        );

        instance.checkPanic(XR10.xrLocateViews(session.handle, viewLocateInfo, viewState, intBuf, session.viewBuffer), "xrLocateViews");

        if ((viewState.viewStateFlags() & XR10.XR_VIEW_STATE_POSITION_VALID_BIT) == 0 ||
                (viewState.viewStateFlags() & XR10.XR_VIEW_STATE_ORIENTATION_VALID_BIT) == 0) {
            LOGGER.error("Invalid headset position, try restarting your device");
            return null;
        }

        var projectionLayerViews = XrCompositionLayerProjectionView.calloc(2, stack);

        float frameUserScale = MCXRPlayClient.getCameraScale();


        if (session.state == XR10.XR_SESSION_STATE_FOCUSED) {
            for (int i = 0; i < 2; i++) {
                if (!XrInput.handsActionSet.grip.isActive[i]) {
                    continue;
                }
                session.setPosesFromSpace(XrInput.handsActionSet.grip.spaces[i], predictedDisplayTime, XrInput.handsActionSet.gripPoses[i], frameUserScale);
                session.setPosesFromSpace(XrInput.handsActionSet.aim.spaces[i], predictedDisplayTime, XrInput.handsActionSet.aimPoses[i], frameUserScale);
            }
        }
        session.setPosesFromSpace(session.xrViewSpace, predictedDisplayTime, MCXRPlayClient.viewSpacePoses, frameUserScale);

        Entity cameraEntity = this.client.getCameraEntity() == null ? this.client.player : this.client.getCameraEntity();
        updatePoses(cameraEntity);
        camera.updateMCXR(this.client.level, cameraEntity, MCXRPlayClient.viewSpacePoses.getMinecraftPose());

        MCXRGuiManager FGM = MCXRPlayClient.INSTANCE.MCXRGuiManager;

        if (FGM.needsReset) {
            FGM.resetTransform();
        }

        long frameStartTime = Util.getNanos();
        if (Minecraft.getInstance().player != null && MCXRCore.getCoreConfig().supportsMCXR()) {
            MCXRCore.INSTANCE.setPlayerHeadPose(
                    Minecraft.getInstance().player,
                    MCXRPlayClient.viewSpacePoses.getPhysicalPose()
            );
        }

        //Ticks the game
        clientExt.preRender(true);

        updatePoses(camera.getEntity());

        client.getWindow().setErrorSection("Render");
        client.getProfiler().push("sound");
        client.getSoundManager().updateSource(client.gameRenderer.getMainCamera());
        client.getProfiler().pop();


        //Render GUI
        mainRenderTarget.setFramebuffer(FGM.guiRenderTarget);
        //Need to do this once framebuffer is gui
        XrInput.postTick(predictedDisplayTime);

        mainRenderTarget.clear(Minecraft.ON_OSX);
        clientExt.doRender(true, frameStartTime, RenderPass.GUI);
        mainRenderTarget.resetFramebuffer();

        FGM.guiPostProcessRenderTarget.bindWrite(true);
        this.guiBlitShader.setSampler("DiffuseSampler", FGM.guiRenderTarget.getColorTextureId());
        this.guiBlitShader.setSampler("DepthSampler", FGM.guiRenderTarget.getDepthTextureId());
        this.blit(FGM.guiPostProcessRenderTarget, guiBlitShader);
        FGM.guiPostProcessRenderTarget.unbindWrite();

        OpenXRSwapchain swapchain = session.swapchain;
        int swapchainImageIndex = swapchain.acquireImage();

        // Render view to the appropriate part of the swapchain image.
        for (int viewIndex = 0; viewIndex < 2; viewIndex++) {
            // Each view has a separate swapchain which is acquired, rendered to, and released.

            var subImage = projectionLayerViews.get(viewIndex)
                    .type(XR10.XR_TYPE_COMPOSITION_LAYER_PROJECTION_VIEW)
                    .pose(session.viewBuffer.get(viewIndex).pose())
                    .fov(session.viewBuffer.get(viewIndex).fov())
                    .subImage();
            subImage.swapchain(swapchain.handle);
            subImage.imageRect().offset().set(0, 0);
            subImage.imageRect().extent().set(swapchain.width, swapchain.height);
            subImage.imageArrayIndex(viewIndex);

            XrRenderTarget swapchainFramebuffer;
            {
                if (viewIndex == 0) {
                    swapchainFramebuffer = swapchain.leftFramebuffers[swapchainImageIndex];
                } else {
                    swapchainFramebuffer = swapchain.rightFramebuffers[swapchainImageIndex];
                }
                mainRenderTarget.setXrFramebuffer(swapchain.renderTarget);
                RenderPass.XrWorld worldRenderPass = RenderPass.XrWorld.create();
                worldRenderPass.fov = session.viewBuffer.get(viewIndex).fov();
                worldRenderPass.eyePoses.updatePhysicalPose(session.viewBuffer.get(viewIndex).pose(), MCXRPlayClient.stageTurn, frameUserScale);
                worldRenderPass.eyePoses.updateGamePose(MCXRPlayClient.xrOrigin);
                worldRenderPass.viewIndex = viewIndex;
                camera.setPose(worldRenderPass.eyePoses.getMinecraftPose());
                clientExt.doRender(true, frameStartTime, worldRenderPass);
            }

            swapchainFramebuffer.bindWrite(true);
            this.blitShader.setSampler("DiffuseSampler", swapchain.renderTarget.getColorTextureId());
            Uniform inverseScreenSize = this.blitShader.getUniform("InverseScreenSize");
            if (inverseScreenSize != null) {
                inverseScreenSize.set(1f / swapchainFramebuffer.width, 1f / swapchainFramebuffer.height);
            }
            swapchain.renderTarget.setFilterMode(GlConst.GL_LINEAR);
            this.blit(swapchainFramebuffer, blitShader);
            swapchainFramebuffer.unbindWrite();

            if (viewIndex == 0) {
                blitToBackbuffer(swapchain.renderTarget);
            }
        }

        instance.checkPanic(XR10.xrReleaseSwapchainImage(
                swapchain.handle,
                XrSwapchainImageReleaseInfo.calloc(stack)
                        .type(XR10.XR_TYPE_SWAPCHAIN_IMAGE_RELEASE_INFO)
        ), "xrReleaseSwapchainImage");

        mainRenderTarget.resetFramebuffer();
        camera.setPose(MCXRPlayClient.viewSpacePoses.getMinecraftPose());
        clientExt.postRender();

        return XrCompositionLayerProjection.calloc(stack)
                .type(XR10.XR_TYPE_COMPOSITION_LAYER_PROJECTION)
                .space(session.xrAppSpace)
                .views(projectionLayerViews);
//        }
    }

    private void updatePoses(Entity camEntity) {
        if (camEntity != null) { //TODO seriously need to tidy up poses
            float tickDelta = client.getFrameTime();

            if (client.isPaused()) {
                tickDelta = 0.0f;
            }

            Entity vehicle = camEntity.getVehicle();
            if (MCXRCore.getCoreConfig().roomscaleMovement() && vehicle == null) {
                MCXRPlayClient.xrOrigin.set(Mth.lerp(tickDelta, camEntity.xo, camEntity.getX()) - MCXRPlayClient.playerPhysicalPosition.x,
                        Mth.lerp(tickDelta, camEntity.yo, camEntity.getY()),
                        Mth.lerp(tickDelta, camEntity.zo, camEntity.getZ()) - MCXRPlayClient.playerPhysicalPosition.z);
            } else {
                MCXRPlayClient.xrOrigin.set(Mth.lerp(tickDelta, camEntity.xo, camEntity.getX()),
                        Mth.lerp(tickDelta, camEntity.yo, camEntity.getY()),
                        Mth.lerp(tickDelta, camEntity.zo, camEntity.getZ()));
            }
            if (vehicle != null) {
                if (vehicle instanceof LivingEntity) {
                    MCXRPlayClient.xrOrigin.y += 0.60;
                } else {
                    MCXRPlayClient.xrOrigin.y += 0.54 - vehicle.getPassengersRidingOffset();
                }
            }

            MCXRPlayClient.viewSpacePoses.updateGamePose(MCXRPlayClient.xrOrigin);
            for (var poses : XrInput.handsActionSet.gripPoses) {
                poses.updateGamePose(MCXRPlayClient.xrOrigin);
            }
            for (var poses : XrInput.handsActionSet.aimPoses) {
                poses.updateGamePose(MCXRPlayClient.xrOrigin);
            }
        }
    }

    private Struct renderBlankLayer(long predictedDisplayTime, MemoryStack stack) {
        IntBuffer intBuf = stackCallocInt(1);

        instance.checkPanic(XR10.xrLocateViews(
                session.handle,
                XrViewLocateInfo.calloc(stack).set(XR10.XR_TYPE_VIEW_LOCATE_INFO,
                        0,
                        session.viewConfigurationType,
                        predictedDisplayTime,
                        session.xrAppSpace
                ),
                XrViewState.calloc(stack).type(XR10.XR_TYPE_VIEW_STATE),
                intBuf,
                session.viewBuffer
        ), "xrLocateViews");

        int viewCountOutput = intBuf.get(0);

        var projectionLayerViews = XrCompositionLayerProjectionView.calloc(viewCountOutput);

        int swapchainImageIndex = session.swapchain.acquireImage();

        for (int viewIndex = 0; viewIndex < viewCountOutput; viewIndex++) {
            XrCompositionLayerProjectionView projectionLayerView = projectionLayerViews.get(viewIndex);
            projectionLayerView.type(XR10.XR_TYPE_COMPOSITION_LAYER_PROJECTION_VIEW);
            projectionLayerView.pose(session.viewBuffer.get(viewIndex).pose());
            projectionLayerView.fov(session.viewBuffer.get(viewIndex).fov());
            projectionLayerView.subImage().swapchain(session.swapchain.handle);
            projectionLayerView.subImage().imageRect().offset().set(0, 0);
            projectionLayerView.subImage().imageRect().extent().set(session.swapchain.width, session.swapchain.height);
            projectionLayerView.subImage().imageArrayIndex(0);
        }

        session.swapchain.leftFramebuffers[swapchainImageIndex].clear(Minecraft.ON_OSX);

        instance.checkPanic(XR10.xrReleaseSwapchainImage(
                session.swapchain.handle,
                XrSwapchainImageReleaseInfo.calloc(stack).type(XR10.XR_TYPE_SWAPCHAIN_IMAGE_RELEASE_INFO)
        ), "xrReleaseSwapchainImage");

        XrCompositionLayerProjection layer = XrCompositionLayerProjection.calloc(stack).type(XR10.XR_TYPE_COMPOSITION_LAYER_PROJECTION);
        layer.space(session.xrAppSpace);
        layer.views(projectionLayerViews);
        return layer;
    }

    public void blit(RenderTarget framebuffer, ShaderInstance shader) {
        PoseStack matrixStack = RenderSystem.getModelViewStack();
        matrixStack.pushPose();
        matrixStack.setIdentity();
        RenderSystem.applyModelViewMatrix();

        int width = framebuffer.width;
        int height = framebuffer.height;

        GlStateManager._colorMask(true, true, true, true);
        GlStateManager._disableDepthTest();
        GlStateManager._depthMask(false);
        GlStateManager._viewport(0, 0, width, height);
        GlStateManager._disableBlend();

        Matrix4f matrix4f = Matrix4f.orthographic((float) width, (float) -height, 1000.0F, 3000.0F);
        RenderSystem.setProjectionMatrix(matrix4f);
        if (shader.MODEL_VIEW_MATRIX != null) {
            shader.MODEL_VIEW_MATRIX.set(Matrix4f.createTranslateMatrix(0.0F, 0.0F, -2000.0F));
        }

        if (shader.PROJECTION_MATRIX != null) {
            shader.PROJECTION_MATRIX.set(matrix4f);
        }

        shader.apply();
        float u = (float) framebuffer.viewWidth / (float) framebuffer.width;
        float v = (float) framebuffer.viewHeight / (float) framebuffer.height;
        Tesselator tessellator = RenderSystem.renderThreadTesselator();
        BufferBuilder bufferBuilder = tessellator.getBuilder();
        bufferBuilder.begin(VertexFormat.Mode.TRIANGLES, DefaultVertexFormat.POSITION_TEX_COLOR);
        bufferBuilder.vertex(0.0, height * 2, 0.0).uv(0.0F, 1 - v * 2).color(255, 255, 255, 255).endVertex();
        bufferBuilder.vertex(width * 2, 0.0, 0.0).uv(u * 2, 1).color(255, 255, 255, 255).endVertex();
        bufferBuilder.vertex(0.0, 0.0, 0.0).uv(0.0F, 1).color(255, 255, 255, 255).endVertex();
        bufferBuilder.end();
        BufferUploader._endInternal(bufferBuilder);
        shader.clear();
        GlStateManager._depthMask(true);
        GlStateManager._colorMask(true, true, true, true);

        matrixStack.popPose();
    }

    public void blitToBackbuffer(RenderTarget framebuffer) {
        //TODO render alyx-like gui over this
        ShaderInstance shader = Minecraft.getInstance().gameRenderer.blitShader;
        shader.setSampler("DiffuseSampler", framebuffer.getColorTextureId());

        PoseStack matrixStack = RenderSystem.getModelViewStack();
        matrixStack.pushPose();
        matrixStack.setIdentity();
        RenderSystem.applyModelViewMatrix();

        int width = mainRenderTarget.minecraftMainRenderTarget.width;
        int height = mainRenderTarget.minecraftMainRenderTarget.height;

        GlStateManager._colorMask(true, true, true, true);
        GlStateManager._disableDepthTest();
        GlStateManager._depthMask(false);
        GlStateManager._viewport(0, 0, width, height);
        GlStateManager._disableBlend();

        Matrix4f matrix4f = Matrix4f.orthographic((float) width, (float) (-height), 1000.0F, 3000.0F);
        RenderSystem.setProjectionMatrix(matrix4f);
        if (shader.MODEL_VIEW_MATRIX != null) {
            shader.MODEL_VIEW_MATRIX.set(Matrix4f.createTranslateMatrix(0, 0, -2000.0F));
        }

        if (shader.PROJECTION_MATRIX != null) {
            shader.PROJECTION_MATRIX.set(matrix4f);
        }

        shader.apply();
        float widthNormalized = (float) framebuffer.width / (float) width;
        float heightNormalized = (float) framebuffer.height / (float) height;
        float v = (widthNormalized / heightNormalized) / 2;

        Tesselator tessellator = Tesselator.getInstance();
        BufferBuilder bufferBuilder = tessellator.getBuilder();
        bufferBuilder.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_TEX_COLOR);
        bufferBuilder.vertex(0.0, height, 0.0).uv(0.0F, 0.0f).color(255, 255, 255, 255).endVertex();
        bufferBuilder.vertex(width, height, 0.0).uv(1, 0.0f).color(255, 255, 255, 255).endVertex();
        bufferBuilder.vertex(width, 0.0, 0.0).uv(1, 1.0f).color(255, 255, 255, 255).endVertex();
        bufferBuilder.vertex(0.0, 0.0, 0.0).uv(0.0F, 1.0F).color(255, 255, 255, 255).endVertex();
        bufferBuilder.end();
        BufferUploader._endInternal(bufferBuilder);
        shader.clear();
        GlStateManager._depthMask(true);
        GlStateManager._colorMask(true, true, true, true);

        matrixStack.popPose();
    }
}