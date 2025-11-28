package io.singularitynet.mixin;

import com.mojang.blaze3d.systems.RenderSystem;
import io.singularitynet.utils.TextureHelper;
import net.minecraft.client.gl.ShaderProgram;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.entity.feature.FeatureRenderer;
import net.minecraft.client.render.entity.model.EntityModel;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.LivingEntity;
import net.minecraft.util.Identifier;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(FeatureRenderer.class)
public abstract class FeatureRendererMixin {

    private static final Logger LOGGER = LogManager.getLogger(FeatureRendererMixin.class);

    @Inject(method = "renderModel(Lnet/minecraft/client/render/entity/model/EntityModel;Lnet/minecraft/util/Identifier;Lnet/minecraft/client/util/math/MatrixStack;Lnet/minecraft/client/render/VertexConsumerProvider;ILnet/minecraft/entity/LivingEntity;FFF)V",
            at = @At("HEAD"))
    private static <T extends LivingEntity> void vereya$forceEntityColour(EntityModel<T> model,
                                                                         Identifier texture,
                                                                         MatrixStack matrices,
                                                                         VertexConsumerProvider vertexConsumers,
                                                                         int light,
                                                                         T entity,
                                                                         float red,
                                                                         float green,
                                                                         float blue,
                                                                         CallbackInfo ci) {
        if (!TextureHelper.isProducingColourMap() || !TextureHelper.colourmapFrame) return;
        if (entity.getType() == EntityType.CHICKEN) {
            LOGGER.info("Segmentation: feature layer render for chicken using texture {}", texture);
        }
        // Ensure per-entity colour is locked before feature layer draws (armor, overlays, etc.).
        TextureHelper.setPendingColourForEntity(entity);
        ShaderProgram program = RenderSystem.getShader();
        if (program != null) {
            TextureHelper.applyPendingColourToProgram(program);
        }
    }
}
