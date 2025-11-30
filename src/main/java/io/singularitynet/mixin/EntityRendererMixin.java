package io.singularitynet.mixin;

import io.singularitynet.utils.TextureHelper;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.entity.EntityRenderer;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Ensures we always mark the current entity during rendering so the
 * segmentation pipeline can assign a single, stable colour per entity type.
 * This complements the dispatcher hook and covers render paths where the
 * dispatcher injection might not fire for every draw call.
 */
@Mixin(EntityRenderer.class)
public abstract class EntityRendererMixin<T extends Entity> {

    @Inject(method = "render(Lnet/minecraft/entity/Entity;FFLnet/minecraft/client/util/math/MatrixStack;Lnet/minecraft/client/render/VertexConsumerProvider;I)V",
            at = @At("HEAD"))
    private void vereya$markCurrentEntity(T entity,
                                          float yaw,
                                          float tickDelta,
                                          MatrixStack matrices,
                                          VertexConsumerProvider vertexConsumers,
                                          int light,
                                          CallbackInfo ci) {
        if (TextureHelper.isProducingColourMap() && TextureHelper.colourmapFrame) {
            TextureHelper.setCurrentEntity(entity);
            TextureHelper.setPendingColourForEntity(entity);
            TextureHelper.setStrictEntityDraw(true);
        }
    }

    @Inject(method = "render(Lnet/minecraft/entity/Entity;FFLnet/minecraft/client/util/math/MatrixStack;Lnet/minecraft/client/render/VertexConsumerProvider;I)V",
            at = @At("TAIL"))
    private void vereya$clearCurrentEntity(T entity,
                                           float yaw,
                                           float tickDelta,
                                           MatrixStack matrices,
                                           VertexConsumerProvider vertexConsumers,
                                           int light,
                                           CallbackInfo ci) {
        if (TextureHelper.isProducingColourMap() && TextureHelper.colourmapFrame) {
            TextureHelper.setCurrentEntity(null);
            TextureHelper.setStrictEntityDraw(false);
        }
    }
}
