package net.sorenon.mcxr.core.mixin.hands.client;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.model.AgeableListModel;
import net.minecraft.client.model.ArmedModel;
import net.minecraft.client.model.HeadedModel;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.entity.LivingEntity;
import net.sorenon.mcxr.core.Pose;
import net.sorenon.mcxr.core.accessor.PlayerExt;
import org.joml.Vector3f;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(HumanoidModel.class)
public class HumanoidModelMixin<T extends LivingEntity> extends AgeableListModel<T> implements ArmedModel, HeadedModel {
    @Shadow @Final public ModelPart rightArm;
    @Shadow @Final public ModelPart leftArm;
    @Shadow public HumanoidModel.ArmPose leftArmPose;

    @Inject(method = "setupAnim(Lnet/minecraft/world/entity/LivingEntity;FFFFF)V", at = @At(value = "TAIL"))
    private void animateVRArms(T livingEntity, float f, float g, float h, float i, float j, CallbackInfo ci) {
        if(livingEntity instanceof PlayerExt acc) {
            if(acc.isXR()) {
                // Setup left position
                Pose leftPose = acc.getLeftHandPose();
                this.leftArm.setPos(
                        leftPose.getPos().x,
                        leftPose.getPos().y,
                        leftPose.getPos().z
                );

                // Setup left orientation
                Vector3f leftOrientation = new Vector3f();
                leftPose.orientation.getEulerAnglesXYZ(leftOrientation);
                this.leftArm.setRotation(
                        leftOrientation.x,
                        leftOrientation.y,
                        leftOrientation.z
                );

                // Setup right position
                Pose rightPose = acc.getLeftHandPose();
                this.rightArm.setPos(
                        rightPose.getPos().x,
                        rightPose.getPos().y,
                        rightPose.getPos().z
                );

                // Setup right orientation
                Vector3f rightOrientation = new Vector3f();
                rightPose.orientation.getEulerAnglesXYZ(rightOrientation);
                this.leftArm.setRotation(
                        rightOrientation.x,
                        rightOrientation.y,
                        rightOrientation.z
                );
            }
        }
    }

    @Shadow
    protected Iterable<ModelPart> headParts() {
        return null;
    }

    @Shadow
    protected Iterable<ModelPart> bodyParts() {
        return null;
    }

    @Shadow
    public void translateToHand(HumanoidArm arm, PoseStack matrices) {

    }

    @Shadow
    public void setupAnim(T entity, float limbAngle, float limbDistance, float animationProgress, float headYaw, float headPitch) {

    }

    @Shadow
    public ModelPart getHead() {
        return null;
    }
}
