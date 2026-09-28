package io.singularitynet.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import io.singularitynet.utils.BlockSegmentation;
import net.minecraft.block.BlockState;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.block.BlockRenderManager;
import net.minecraft.client.render.chunk.SectionBuilder;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.fluid.FluidState;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.random.Random;
import net.minecraft.world.BlockRenderView;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(SectionBuilder.class)
public abstract class SectionBuilderColoursMixin {
    // Capture ownership while each block emits vertices, before batching loses block boundaries.
    // WrapOperation composes with other render wrappers instead of replacing the invocation.
    @WrapOperation(method = "build", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/render/block/BlockRenderManager;renderBlock(Lnet/minecraft/block/BlockState;Lnet/minecraft/util/math/BlockPos;Lnet/minecraft/world/BlockRenderView;Lnet/minecraft/client/util/math/MatrixStack;Lnet/minecraft/client/render/VertexConsumer;ZLnet/minecraft/util/math/random/Random;)V"))
    private void vereya$buildBlock(BlockRenderManager manager, BlockState state, BlockPos pos,
                                   BlockRenderView world, MatrixStack matrices, VertexConsumer consumer,
                                   boolean cull, Random random, Operation<Void> original) {
        BlockSegmentation.begin(state);
        try {
            original.call(manager, state, pos, world, matrices, consumer, cull, random);
        } finally {
            // Worker threads are reused; even a failed build must not leak its label.
            BlockSegmentation.end();
        }
    }

    @WrapOperation(method = "build", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/render/block/BlockRenderManager;renderFluid(Lnet/minecraft/util/math/BlockPos;Lnet/minecraft/world/BlockRenderView;Lnet/minecraft/client/render/VertexConsumer;Lnet/minecraft/block/BlockState;Lnet/minecraft/fluid/FluidState;)V"))
    private void vereya$buildFluid(BlockRenderManager manager, BlockPos pos, BlockRenderView world,
                                   VertexConsumer consumer, BlockState state, FluidState fluid, Operation<Void> original) {
        // Fluid vertices use the owning BlockState too, including waterlogged blocks.
        BlockSegmentation.begin(state);
        try {
            original.call(manager, pos, world, consumer, state, fluid);
        } finally {
            BlockSegmentation.end();
        }
    }
}
