package io.singularitynet.mixin;

import io.singularitynet.utils.TextureHelper;
import net.minecraft.block.BlockState;
import net.minecraft.client.render.block.BlockRenderManager;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.random.Random;
import net.minecraft.world.BlockRenderView;
import net.minecraft.registry.Registries;
import net.minecraft.util.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Captures the current block type string when rendering blocks so the
 * segmentation path can assign a stable per-type colour rather than a
 * UV-derived colour that varies across sprites.
 */
@Mixin(BlockRenderManager.class)
public abstract class BlockRenderManagerMixin {

    @Inject(method = "renderBlock", at = @At("HEAD"))
    private void vereya$captureBlockType(BlockState state,
                                         BlockPos pos,
                                         BlockRenderView world,
                                         MatrixStack matrices,
                                         VertexConsumer vertexConsumer,
                                         boolean cull,
                                         Random random,
                                         CallbackInfo ci) {
        try {
            Identifier id = Registries.BLOCK.getId(state.getBlock());
            if (id != null) {
                TextureHelper.setCurrentBlockType(id.toString());
                if (TextureHelper.isProducingColourMap() && TextureHelper.colourmapFrame) {
                    // Default to atlas-derived colouring for blocks to increase
                    // per-frame colour diversity until a more specific bind occurs
                    TextureHelper.setPendingForBlockAtlas();
                }
            }
        } catch (Throwable ignored) {}
    }
}
