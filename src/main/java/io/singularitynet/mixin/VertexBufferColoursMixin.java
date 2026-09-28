package io.singularitynet.mixin;

import com.mojang.blaze3d.platform.GlStateManager;
import io.singularitynet.utils.BlockSegmentation;
import io.singularitynet.utils.SegmentationColours;
import io.singularitynet.utils.TextureHelper;
import net.minecraft.client.gl.VertexBuffer;
import net.minecraft.client.render.BuiltBuffer;
import net.minecraft.client.render.VertexFormat;
import net.minecraft.client.render.VertexFormatElement;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL20;
import org.lwjgl.system.MemoryUtil;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.nio.ByteBuffer;

@Mixin(VertexBuffer.class)
public abstract class VertexBufferColoursMixin {
    @Shadow private int vertexBufferId;
    @Shadow private VertexFormat vertexFormat;
    // Keep labels separate from vanilla colours, which contain lighting and biome tints.
    @Unique private int vereya$colourBuffer = -1;
    @Unique private boolean vereya$usingColours;

    @Inject(method = "upload", at = @At("HEAD"))
    private void vereya$uploadColours(BuiltBuffer buffer, CallbackInfo callback) {
        if (((VertexBuffer) (Object) this).isClosed()) return;
        int[] colours = ((SegmentationColours) buffer).vereya$getColours();
        if (colours == null) {
            // A reused VertexBuffer must not retain labels from its previous mesh.
            vereya$deleteColours();
            return;
        }
        if (colours.length != buffer.getDrawParameters().vertexCount()) {
            throw new IllegalStateException("Segmentation vertex count mismatch");
        }
        if (vereya$colourBuffer < 0) vereya$colourBuffer = GlStateManager._glGenBuffers();
        ByteBuffer bytes = MemoryUtil.memAlloc(colours.length * 4);
        int previous = GL11.glGetInteger(GL15.GL_ARRAY_BUFFER_BINDING);
        try {
            // Explicit RGBA byte order; putInt would depend on the buffer's endianness.
            for (int colour : colours) {
                bytes.put((byte) (colour >> 16)).put((byte) (colour >> 8)).put((byte) colour).put((byte) 255);
            }
            bytes.flip();
            GlStateManager._glBindBuffer(GL15.GL_ARRAY_BUFFER, vereya$colourBuffer);
            GL15.glBufferData(GL15.GL_ARRAY_BUFFER, bytes, GL15.GL_STATIC_DRAW);
        } finally {
            GlStateManager._glBindBuffer(GL15.GL_ARRAY_BUFFER, previous);
            MemoryUtil.memFree(bytes);
        }
    }

    @Inject(method = "draw()V", at = @At("HEAD"))
    private void vereya$beginColourDraw(CallbackInfo callback) {
        vereya$usingColours = BlockSegmentation.isEnabled()
                && TextureHelper.isProducingColourMap() && TextureHelper.colourmapFrame
                && vereya$colourBuffer >= 0 && vertexFormat.has(VertexFormatElement.COLOR);
        // Low-level draw hooks read this flag to send the -2 vertex-label shader sentinel.
        BlockSegmentation.setDrawingColours(vereya$usingColours);
        if (!vereya$usingColours) return;
        // Swap only the colour attribute; positions, UVs and indices remain shared.
        int previous = GL11.glGetInteger(GL15.GL_ARRAY_BUFFER_BINDING);
        GlStateManager._glBindBuffer(GL15.GL_ARRAY_BUFFER, vereya$colourBuffer);
        GL20.glVertexAttribPointer(vertexFormat.getAttributeNames().indexOf("Color"),
                4, GL11.GL_UNSIGNED_BYTE, true, 4, 0L);
        GlStateManager._glBindBuffer(GL15.GL_ARRAY_BUFFER, previous);
    }

    @Inject(method = "draw()V", at = @At("RETURN"))
    private void vereya$endColourDraw(CallbackInfo callback) {
        if (vereya$usingColours) {
            // The attribute pointer stores its VBO in the VAO. Restoring ARRAY_BUFFER alone
            // is not enough: restore the pointer/stride too, or later RGB draws use labels.
            int previous = GL11.glGetInteger(GL15.GL_ARRAY_BUFFER_BINDING);
            GlStateManager._glBindBuffer(GL15.GL_ARRAY_BUFFER, vertexBufferId);
            GL20.glVertexAttribPointer(vertexFormat.getAttributeNames().indexOf("Color"),
                    4, GL11.GL_UNSIGNED_BYTE, true, vertexFormat.getVertexSizeByte(),
                    vertexFormat.getOffset(VertexFormatElement.COLOR));
            GlStateManager._glBindBuffer(GL15.GL_ARRAY_BUFFER, previous);
        }
        vereya$usingColours = false;
        BlockSegmentation.setDrawingColours(false);
    }

    @Inject(method = "close", at = @At("HEAD"))
    private void vereya$closeColours(CallbackInfo callback) { vereya$deleteColours(); }

    @Unique
    private void vereya$deleteColours() {
        if (vereya$colourBuffer >= 0) {
            GlStateManager._glDeleteBuffers(vereya$colourBuffer);
            vereya$colourBuffer = -1;
        }
    }
}
