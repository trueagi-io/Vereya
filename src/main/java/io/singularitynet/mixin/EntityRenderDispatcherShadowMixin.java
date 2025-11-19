package io.singularitynet.mixin;

import io.singularitynet.utils.TextureHelper;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.entity.EntityRenderDispatcher;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.entity.Entity;
import net.minecraft.world.WorldView;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Skips drawing blob shadows during the segmentation pass so ground blocks are
 * not darkened under entities in the colour map.
 */
@Mixin(EntityRenderDispatcher.class)
public abstract class EntityRenderDispatcherShadowMixin {

    @Redirect(
            method = "render(Lnet/minecraft/entity/Entity;DDDFFLnet/minecraft/client/util/math/MatrixStack;Lnet/minecraft/client/render/VertexConsumerProvider;I)V",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/render/entity/EntityRenderDispatcher;renderShadow(Lnet/minecraft/client/util/math/MatrixStack;Lnet/minecraft/client/render/VertexConsumerProvider;Lnet/minecraft/entity/Entity;FFLnet/minecraft/world/WorldView;F)V")
    )
    private void vereya$skipShadowWhenColourmap(MatrixStack matrices,
                                                VertexConsumerProvider vertexConsumers,
                                                Entity entity,
                                                float opacity,
                                                float tickDelta,
                                                WorldView world,
                                                float radius) {
        if (TextureHelper.isProducingColourMap() && TextureHelper.colourmapFrame) {
            // Suppress shadow rendering during segmentation pass
            return;
        }
        // Call original method when not producing colour map
        EntityRenderDispatcherAccessor.invokeRenderShadow(matrices, vertexConsumers, entity, opacity, tickDelta, world, radius);
    }
}
