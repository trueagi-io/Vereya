package io.singularitynet.mixin;

import io.singularitynet.utils.TextureHelper;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.entity.EntityRenderDispatcher;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(EntityRenderDispatcher.class)
public abstract class EntityRenderDispatcherMixin {

    @Inject(method = "render(Lnet/minecraft/entity/Entity;DDDFFLnet/minecraft/client/util/math/MatrixStack;Lnet/minecraft/client/render/VertexConsumerProvider;I)V",
            at = @At("HEAD"))
    private <E extends Entity> void vereya$setCurrentEntity(E entity, double x, double y, double z, float yaw, float tickDelta, MatrixStack matrices, VertexConsumerProvider vertexConsumers, int light, CallbackInfo ci) {
        if (TextureHelper.isProducingColourMap() && TextureHelper.colourmapFrame) {
            TextureHelper.setCurrentEntity(entity);
            // Ensure a stable per-entity colour is pending before any draw calls
            TextureHelper.setPendingColourForEntity(entity);
        }
    }

    @Inject(method = "render(Lnet/minecraft/entity/Entity;DDDFFLnet/minecraft/client/util/math/MatrixStack;Lnet/minecraft/client/render/VertexConsumerProvider;I)V",
            at = @At("TAIL"))
    private <E extends Entity> void vereya$clearCurrentEntity(E entity, double x, double y, double z, float yaw, float tickDelta, MatrixStack matrices, VertexConsumerProvider vertexConsumers, int light, CallbackInfo ci) {
        if (TextureHelper.isProducingColourMap() && TextureHelper.colourmapFrame) {
            TextureHelper.setCurrentEntity(null);
        }
    }
}
