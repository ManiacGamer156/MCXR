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
            // Setup left position
            Pose leftPose = acc.getLeftHandPose();
            this.leftArm.setPos(
                    (float) Math.pow(leftPose.getPos().x - 9f, 2),
                    (float) Math.pow(leftPose.getPos().y - 87.5f, 2),
                    (float) Math.pow(leftPose.getPos().z - 7.5f, 2)
            );

            // Setup left orientation
            Vector3f leftOrientation = new Vector3f();
            leftPose.orientation.getEulerAnglesXYZ(leftOrientation);
            this.leftArm.setRotation(
                    -leftOrientation.z,
                    -leftOrientation.y,
                    -leftOrientation.x
            );

            // Setup right position
            Pose rightPose = acc.getRightHandPose();
            this.rightArm.setPos(
                    (float) Math.pow(rightPose.getPos().x - 20.5f, 2),
                    (float) Math.pow(rightPose.getPos().y - 87.5f, 2),
                    (float) Math.pow(rightPose.getPos().z - 6.5f, 2)
            );

            // Setup right orientation
            Vector3f rightOrientation = new Vector3f();
            rightPose.orientation.getEulerAnglesXYZ(rightOrientation);
            this.rightArm.setRotation(
                    rightOrientation.z,
                    -rightOrientation.y,
                    rightOrientation.x
            );
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
