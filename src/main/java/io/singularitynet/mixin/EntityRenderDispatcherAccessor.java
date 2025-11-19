package io.singularitynet.mixin;

import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.entity.EntityRenderDispatcher;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.entity.Entity;
import net.minecraft.world.WorldView;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(EntityRenderDispatcher.class)
public interface EntityRenderDispatcherAccessor {
    @Invoker("renderShadow")
    static void invokeRenderShadow(MatrixStack matrices,
                                   VertexConsumerProvider vertexConsumers,
                                   Entity entity,
                                   float opacity,
                                   float tickDelta,
                                   WorldView world,
                                   float radius) {
        throw new AssertionError();
    }
}

