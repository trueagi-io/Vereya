package io.singularitynet.utils;

import com.mojang.blaze3d.platform.GlConst;
import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gl.Framebuffer;
import net.minecraft.client.gl.ShaderProgram;
import net.minecraft.client.gl.SimpleFramebuffer;
import net.minecraft.client.gl.GlUniform;
import net.minecraft.client.render.VertexFormat;
import net.minecraft.client.render.VertexFormatElement;
import net.minecraft.client.render.VertexFormats;
import net.minecraft.client.texture.SpriteAtlasTexture;
import net.minecraft.entity.Entity;
import net.minecraft.util.Identifier;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;

import java.io.BufferedInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/*
 Short description
 -----------------
 TextureHelper coordinates colour-map (segmentation) rendering:
 - State: tracks if colour-map mode is active, and the entity currently being
   rendered. Holds colour maps for entities (mob types) and miscellaneous
   textures (eg sun/moon) configured by the mission.
 - Mixins:
   * EntityRenderDispatcherMixin sets/clears the current entity around each
     entity render call.
   * TextureManagerMixin hooks after texture binding and calls
     onTextureBound(id), which activates the annotate shader and sets uniforms
     to output a flat colour.
 - Shader: annotate.vsh/annotate.fsh is loaded once. Uniforms entityColourR/G/B
   are set to:
     * a solid RGB for entities (from map or deterministic fallback),
     * a solid RGB for known misc textures,
     * or -1 to signal the block atlas path (shader uses UVs to derive colour).
 - Extensibility: use setMobColours and setMiscTextureColours with keys:
     * mob/entity: by entity type string from mission config,
     * misc textures: by Identifier path, e.g. "textures/environment/sun.png".
 The frame readout happens in ColourMapProducerImplementation; this class ensures
 the scene is rendered in segmentation colours before the pixels are read.

  Debugging note (validated): a diagnostic "red clear" test that clears the
  segmentation FBO to solid red after a draw produced red pixels on readback,
  confirming the FBO write path is correct. Issues with black output were traced
  to shader/attribute binding (e.g., UV0 linkage), not to the FBO state.
*/

// Helper methods, classes etc which allow us to subvert the Minecraft render pipeline to produce
// a colourmap image in addition to the normal Minecraft image.
//
// NOTE: This is a lightweight, Fabric-compatible scaffold that mirrors the public
// API used by ColourMapProducerImplementation. The actual render pipeline
// interception (eg binding custom shaders) is handled elsewhere in Malmo; here we
// just expose state and mappings so other hooks/mixins can query them.
public class TextureHelper {
    private static final Logger LOGGER = LogManager.getLogger(TextureHelper.class);
    // Indicates whether a colour-map render pass is active this frame.
    public static volatile boolean colourmapFrame = false;

    private static volatile boolean isProducingColourMap = false;
    // If true, segmentation respects texture opacity (cutouts like leaves/grass)
    private static volatile boolean respectOpacity = false;

    // Optional mapping from mob/entity identifiers to colours.
    private static Map<String, Integer> idealMobColours = null;
    // Optional mapping from texture identifiers (paths) to colours for misc elements (eg sun/moon).
    private static Map<String, Integer> miscTexturesToColours = null;

    // Placeholder sky renderer reference (no-op in this port).
    private static Object blankSkyRenderer = null;

    // Current entity being rendered (set via mixin).
    private static Entity currentEntity = null;
    // When true, force a solid per-entity colour for the entire entity render
    // (base model + feature layers), regardless of intermediate binds.
    private static volatile boolean strictEntityDraw = false;
    // When true, force a solid per-block-type colour for the entire block draw
    // section (all binds within BlockRenderManager.renderBlock scope).
    private static volatile boolean strictBlockDraw = false;
    // Current block type string being rendered (eg "minecraft:stone"), set via mixin.
    private static String currentBlockType = null;
    // Flag to indicate a block draw call is in progress
    private static volatile boolean drawingBlock = false;

    // Legacy GL shader loading (kept for reference if needed for non-JSON shaders)
    private static int shaderProgram = -1;
    private static boolean initialised = false;
    private static int uniformR = -1;
    private static int uniformG = -1;
    private static int uniformB = -1;

    // Cache of annotate ShaderPrograms keyed by attribute name list.
    private static final Map<String, ShaderProgram> annotatePrograms = new HashMap<>();

    // Off-screen framebuffer used for the segmentation pass.
    private static SimpleFramebuffer segmentationFbo = null;

    // Pending colour to apply to annotate programs when they are bound.
    private static volatile int pendingR = 0;
    private static volatile int pendingG = 0;
    private static volatile int pendingB = 0;
    // Track last texture bound to help choose pending colour when a shader is set
    private static volatile Identifier lastBoundTexture = null;

    // Debug: 0=off, 1=magenta, 2=UV debug, 3=frag debug
    private static volatile int segmentationDebugLevel = 0;

    // Saved GL state toggled during segmentation pass
    private static boolean prevBlend = false;
    private static boolean prevDepth = false;
    private static boolean prevScissor = false;
    private static boolean prevStencil = false;

    // Per-segmentation-frame diagnostics
    private static int segAtlasBinds = 0;
    private static int segEntityBinds = 0;
    private static int segOtherBinds = 0;
    private static int segProgramSwapsUV = 0;
    private static int segProgramSwapsNoUV = 0;

    // Last ChunkOffset captured from vanilla shader uniforms
    private static volatile float lastChunkOffsetX = 0f;
    private static volatile float lastChunkOffsetY = 0f;
    private static volatile float lastChunkOffsetZ = 0f;

    public static void updateChunkOffset(float x, float y, float z) {
        lastChunkOffsetX = x;
        lastChunkOffsetY = y;
        lastChunkOffsetZ = z;
        if (isProducingColourMap && colourmapFrame) {
            LOGGER.debug("TextureHelper: captured ChunkOffset from vanilla -> ({}, {}, {})", x, y, z);
        }
    }

    public static float[] getLastChunkOffset() {
        return new float[]{lastChunkOffsetX, lastChunkOffsetY, lastChunkOffsetZ};
    }

    public static void setSegmentationDebugMode(boolean on) {
        segmentationDebugLevel = on ? 1 : 0;
        LOGGER.debug("TextureHelper: segmentation debug mode set to {} (level={})", on, segmentationDebugLevel);
    }

    public static boolean isSegmentationDebugMode() {
        return segmentationDebugLevel != 0;
    }

    public static void setSegmentationDebugLevel(int level) {
        segmentationDebugLevel = level;
        LOGGER.debug("TextureHelper: segmentation debug level set to {}", segmentationDebugLevel);
    }

    public static int getSegmentationDebugLevel() {
        try {
            String prop = System.getProperty("vereya.seg.debug");
            if (prop != null && !prop.isEmpty()) {
                int level = segmentationDebugLevel;
                if ("uv".equalsIgnoreCase(prop) || "2".equals(prop)) level = 2;
                else if ("frag".equalsIgnoreCase(prop) || "3".equals(prop)) level = 3;
                else if ("magenta".equalsIgnoreCase(prop) || "1".equals(prop)) level = 1;
                else if ("off".equalsIgnoreCase(prop) || "0".equals(prop)) level = 0;
                if (level != segmentationDebugLevel) {
                    segmentationDebugLevel = level;
                }
            }
        } catch (Throwable t) {
            // ignore property lookup issues; leave level as-is
        }
        return segmentationDebugLevel;
    }

    public static void setIsProducingColourMap(boolean usemap) {
        isProducingColourMap = usemap;
        LOGGER.debug("TextureHelper: setIsProducingColourMap({})", usemap);
    }

    public static boolean isProducingColourMap() {
        return isProducingColourMap;
    }

    /** Controls whether segmentation respects texture opacity (cutouts). */
    public static void setRespectOpacity(boolean respect) {
        respectOpacity = respect;
        LOGGER.debug("TextureHelper: respectOpacity set to {}", respect);
    }

    public static boolean isRespectOpacity() { return respectOpacity; }

    /**
     * Set preferred mob colours, keyed by entity type string.
     */
    public static void setMobColours(Map<String, Integer> mobColours) {
        if (mobColours == null || mobColours.isEmpty()) {
            idealMobColours = null;
        } else {
            idealMobColours = new HashMap<>(mobColours);
        }
    }

    /**
     * Set colours for miscellaneous textures, keyed by texture path string.
     */
    public static void setMiscTextureColours(Map<String, Integer> miscColours) {
        if (miscColours == null || miscColours.isEmpty()) {
            miscTexturesToColours = null;
        } else {
            miscTexturesToColours = new HashMap<>(miscColours);
        }
    }

    public static void setCurrentEntity(Entity entity) {
        currentEntity = entity;
        if (colourmapFrame && isProducingColourMap) {
            if (entity != null) {
                try {
                    LOGGER.debug("TextureHelper: rendering entity type={} id={}", entity.getType().toString(), entity.getId());
                } catch (Throwable t) {
                    LOGGER.debug("TextureHelper: rendering entity (id unavailable)");
                }
            }
        }
    }

    public static void setStrictEntityDraw(boolean on) { strictEntityDraw = on; }
    public static boolean isStrictEntityDraw() { return strictEntityDraw; }
    public static void setStrictBlockDraw(boolean on) { strictBlockDraw = on; }
    public static boolean isStrictBlockDraw() { return strictBlockDraw; }

    public static boolean hasCurrentEntity() { return currentEntity != null; }

    public static Entity getCurrentEntity() { return currentEntity; }

    /**
     * Force pending uniform colour to the stable per-entity colour for the
     * given entity. Used by entity rendering hooks to avoid relying on
     * texture-bind ordering.
     */
    public static void setPendingColourForEntity(Entity entity) {
        int col = getColourForEntity(entity) & 0x00FFFFFF;
        pendingR = (col >> 16) & 0xFF;
        pendingG = (col >> 8) & 0xFF;
        pendingB = (col) & 0xFF;
        if (colourmapFrame && isProducingColourMap && entity != null) {
            try {
                if (entity.getType().toString().contains("chicken")) {
                    LOGGER.info("SegDebug: setPendingColourForEntity chicken -> pendingR={} pendingG={} pendingB={}",
                            pendingR, pendingG, pendingB);
                }
            } catch (Throwable ignored) {}
        }
    }

    /** Sets pending colour to the current entity's stable colour, if any. */
    public static void setPendingColourForCurrentEntity() {
        if (currentEntity != null) {
            setPendingColourForEntity(currentEntity);
        }
    }

    public static void setCurrentBlockType(String blockType) {
        currentBlockType = blockType;
    }

    public static void setDrawingBlock(boolean on) { drawingBlock = on; }
    public static boolean isDrawingBlock() { return drawingBlock; }

    public static void setPendingColourForCurrentBlock() {
        if (currentBlockType != null && !currentBlockType.isEmpty()) {
            int rgb = getColourForBlockType(currentBlockType) & 0x00FFFFFF;
            pendingR = (rgb >> 16) & 0xFF;
            pendingG = (rgb >> 8) & 0xFF;
            pendingB = (rgb) & 0xFF;
        } else {
            pendingR = pendingG = pendingB = -1;
        }
    }

    private static int getColourForBlockType(String blockType) {
        if (blockType == null || blockType.isEmpty()) return 0xFF444444;
        // Derive 3 decorrelated bytes from the string hash; then clamp to mid-range (32..223)
        int h = blockType.hashCode();
        int h2 = Integer.rotateLeft(h * 0x45D9F3B, 13);
        int h3 = Integer.rotateLeft(h2 * 0x45D9F3B, 17);
        int r0 = (h      ) & 0xFF;
        int g0 = (h2     ) & 0xFF;
        int b0 = (h3     ) & 0xFF;
        int r = 32 + (r0 * 191 / 255);
        int g = 32 + (g0 * 191 / 255);
        int b = 32 + (b0 * 191 / 255);
        return 0xFF000000 | ((r & 0xFF) << 16) | ((g & 0xFF) << 8) | (b & 0xFF);
    }

    public static int getColourForEntity(Entity entity) {
        if (entity == null) return 0x000000;
        // Prefer a stable, namespaced id for the entity type (eg "minecraft:zombie")
        String key;
        try {
            net.minecraft.util.Identifier rid = net.minecraft.registry.Registries.ENTITY_TYPE.getId(entity.getType());
            key = (rid != null) ? rid.toString() : entity.getType().toString();
        } catch (Throwable t) {
            key = entity.getType().toString();
        }
        if (idealMobColours != null) {
            Integer col = idealMobColours.get(key);
            if (col != null) return (0xFF000000 | (col & 0x00FFFFFF));
        }
        int hash = key.hashCode();
        int r = (hash >> 16) & 0xFF;
        int g = (hash >> 8) & 0xFF;
        int b = (hash) & 0xFF;
        // Force entity colours into the high range of each channel to avoid
        // collisions with atlas-derived block colours (which never exceed ~232).
        r = (r & 0x0F) | 0xF0;
        g = (g & 0x0F) | 0xF0;
        b = (b & 0x0F) | 0xF0;
        int rgb = ((r & 0xFF) << 16) | ((g & 0xFF) << 8) | (b & 0xFF);
        return 0xFF000000 | (rgb & 0x00FFFFFF);
    }

    public static int getColourForTexture(Identifier id) {
        if (id == null || miscTexturesToColours == null) return -1;
        Integer col = miscTexturesToColours.get(id.getPath());
        if (col == null) return -1;
        return 0xFF000000 | (col & 0x00FFFFFF);
    }

    /**
     * Best-effort fallback: derive a stable per-entity colour from a bound
     * entity texture path when we don't have the current entity.
     * Tries to parse textures/entity/<type>/... and use either a configured
     * mission colour (minecraft:<type>) or a high-range hash of that key.
     */
    private static int getFallbackEntityColourFromTexture(Identifier id) {
        if (id == null || id.getPath() == null) return -1;
        String p = id.getPath();
        // Expect paths like "textures/entity/chicken/chicken.png"
        String marker = "textures/entity/";
        int idx = p.indexOf(marker);
        if (idx < 0) return -1;
        String rest = p.substring(idx + marker.length());
        int slash = rest.indexOf('/');
        String type;
        if (slash > 0) {
            type = rest.substring(0, slash);
        } else {
            // No subfolder; use filename stem without extension
            int dot = rest.lastIndexOf('.');
            type = (dot > 0) ? rest.substring(0, dot) : rest;
        }
        // Normalise some known special cases (eg, zombie/villager sub-variants)
        if (type == null || type.isEmpty()) return -1;
        String key = "minecraft:" + type;
        // If mission provided a colour for this type, prefer it
        if (idealMobColours != null) {
            Integer c = idealMobColours.get(key);
            if (c != null) return (0xFF000000 | (c & 0x00FFFFFF));
        }
        // Otherwise, derive a bright high-range RGB from the key
        int hash = key.hashCode();
        int r = (hash >> 16) & 0xFF;
        int g = (hash >> 8) & 0xFF;
        int b = (hash) & 0xFF;
        r = (r & 0x0F) | 0xF0;
        g = (g & 0x0F) | 0xF0;
        b = (b & 0x0F) | 0xF0;
        int rgb = ((r & 0xFF) << 16) | ((g & 0xFF) << 8) | (b & 0xFF);
        return 0xFF000000 | (rgb & 0x00FFFFFF);
    }

    /**
     * Fallback per-block colour derived from a block texture path when we
     * don't have a currentBlockType. Produces a stable bright RGB from
     * something like textures/block/<name>.png.
     */
    private static int getFallbackBlockColourFromTexture(Identifier id) {
        if (id == null || id.getPath() == null) return -1;
        String p = id.getPath();
        String marker = "textures/block/";
        int idx = p.indexOf(marker);
        if (idx < 0) return -1;
        String rest = p.substring(idx + marker.length());
        // Use stem before '/' or '.'
        int slash = rest.indexOf('/');
        if (slash > 0) rest = rest.substring(0, slash);
        int dot = rest.lastIndexOf('.');
        String name = (dot > 0) ? rest.substring(0, dot) : rest;
        if (name.isEmpty()) return -1;
        String key = "minecraft:block/" + name;
        int hash = key.hashCode();
        int r = (hash >> 16) & 0xFF;
        int g = (hash >> 8) & 0xFF;
        int b = (hash) & 0xFF;
        r = (r & 0x0F) | 0xF0;
        g = (g & 0x0F) | 0xF0;
        b = (b & 0x0F) | 0xF0;
        int rgb = ((r & 0xFF) << 16) | ((g & 0xFF) << 8) | (b & 0xFF);
        return 0xFF000000 | (rgb & 0x00FFFFFF);
    }

    public static void setSkyRenderer(Object skyRenderer) {
        blankSkyRenderer = skyRenderer;
    }

    public static Object getSkyRenderer() {
        return blankSkyRenderer;
    }

    public static class BlankSkyRenderer {
        public final int r;
        public final int g;
        public final int b;

        public BlankSkyRenderer(byte[] rgb) {
            int R = rgb.length > 0 ? (rgb[0] & 0xFF) : 0;
            int G = rgb.length > 1 ? (rgb[1] & 0xFF) : 0;
            int B = rgb.length > 2 ? (rgb[2] & 0xFF) : 0;
            this.r = R;
            this.g = G;
            this.b = B;
        }
    }

    public static void onTextureBound(int unit, Identifier id) {
        if (!isProducingColourMap || !colourmapFrame) {
            lastBoundTexture = id;
            return;
        }
        lastBoundTexture = id;
        if (id != null && "minecraft".equals(id.getNamespace()) && id.getPath() != null && id.getPath().startsWith("dynamic/light_map")) {
            return;
        }
        if (id != null && "minecraft".equals(id.getNamespace()) && id.getPath() != null) {
            if (colourmapFrame) {
                String p = id.getPath();
                if ("textures/environment/clouds.png".equals(p) ||
                    "textures/environment/sun.png".equals(p) ||
                    "textures/environment/moon_phases.png".equals(p)) {
                    LOGGER.debug("TextureHelper: ignoring env texture {} bind during segmentation pass", p);
                    return;
                }
            }
        }
        int col = 0;
        boolean isAtlas = (SpriteAtlasTexture.BLOCK_ATLAS_TEXTURE.equals(id) ||
                (id != null && id.getPath() != null && id.getPath().contains("textures/atlas/")));
        int misc = getColourForTexture(id);

        // Diagnostic: track chicken texture binds that occur without an active entity.
        if (id != null
                && "minecraft".equals(id.getNamespace())
                && id.getPath() != null
                && id.getPath().startsWith("textures/entity/chicken/")
                && !hasCurrentEntity()
                && !isStrictEntityDraw()) {
            LOGGER.info("Segmentation: chicken texture bound with NO current entity; isAtlas={} miscColour={} pendingBefore=({}, {}, {})",
                    isAtlas, misc, pendingR, pendingG, pendingB);
        }

        if (hasCurrentEntity() || strictEntityDraw) {
            // While rendering an entity, keep entity colour fully stable regardless of binds
            col = getColourForEntity(currentEntity) & 0x00FFFFFF;
            segEntityBinds++;
        } else if (misc != -1) {
            col = misc & 0x00FFFFFF;
            segOtherBinds++;
        } else if (id != null && id.getPath() != null && id.getPath().startsWith("textures/entity/")) {
            // Fallback: if an entity texture is bound but currentEntity isn't set yet,
            // derive a stable high-range colour from the entity type encoded in the path.
            int fallback = getFallbackEntityColourFromTexture(id);
            if (fallback != -1) {
                col = fallback & 0x00FFFFFF;
                segEntityBinds++;
            } else {
                col = -1;
            }
        } else {
            // For block/world draws: prefer stable per-type colour whenever we know the current block type.
            if (isDrawingBlock() || strictBlockDraw || (currentBlockType != null && !currentBlockType.isEmpty())) {
                setPendingColourForCurrentBlock();
                col = (pendingR < 0 || pendingG < 0 || pendingB < 0) ? -1 : ((pendingR << 16) | (pendingG << 8) | pendingB);
            } else {
                // No current block type: try to derive a stable per-sprite block colour from the texture path.
                int fb = getFallbackBlockColourFromTexture(id);
                if (fb != -1) {
                    col = fb & 0x00FFFFFF;
                } else {
                    col = -1;
                }
            }
            if (isAtlas) segAtlasBinds++; else segOtherBinds++;
        }
        if (col == -1) {
            pendingR = -1;
            pendingG = -1;
            pendingB = -1;
        } else {
            pendingR = (col >> 16) & 0xFF;
            pendingG = (col >> 8) & 0xFF;
            pendingB = (col) & 0xFF;
        }
        if (LOGGER.isDebugEnabled()) {
            LOGGER.debug("Texture bound {} -> pending colour R:{} G:{} B:{}", id != null ? id.toString() : "<null>", pendingR, pendingG, pendingB);
        }
        ShaderProgram active = RenderSystem.getShader();
        if (active != null) {
            try {
                active.bind();
            } catch (Throwable t) {
                LOGGER.warn("onTextureBound: active.bind failed: {}", t.toString());
            }
            GlUniform r = active.getUniform("entityColourR");
            GlUniform g = active.getUniform("entityColourG");
            GlUniform b = active.getUniform("entityColourB");
            GlUniform dbg = active.getUniform("debugMode");
            GlUniform alpha = active.getUniform("respectAlpha");
            if (r != null && g != null && b != null) {
                r.set(pendingR);
                g.set(pendingG);
                b.set(pendingB);
                r.upload();
                g.upload();
                b.upload();
                LOGGER.debug("Applied pending colour to ACTIVE program {} -> R:{} G:{} B:{}", active.getName(), pendingR, pendingG, pendingB);
            } else {
                LOGGER.debug("Active program {} lacks annotate uniforms; will defer to swap handler", active.getName());
            }
        if (dbg != null) {
            dbg.set(segmentationDebugLevel);
            dbg.upload();
            LOGGER.debug("Applied debugMode={} to ACTIVE program {}", segmentationDebugLevel, active.getName());
        }
        if (alpha != null) {
            alpha.set(respectOpacity ? 1 : 0);
            alpha.upload();
            LOGGER.debug("Applied respectAlpha={} to ACTIVE program {}", (respectOpacity ? 1 : 0), active.getName());
        }
        GlUniform grid = active.getUniform("atlasGrid");
        if (grid != null) {
            int atlasGrid = 32; // classic coarse grid (used only by debug or fallback paths)
            grid.set(atlasGrid);
            grid.upload();
            LOGGER.debug("Applied atlasGrid={} to ACTIVE program {}", atlasGrid, active.getName());
        }
        GlUniform lod = active.getUniform("atlasLod");
        if (lod != null) {
            lod.set(8);
            lod.upload();
            LOGGER.debug("Applied atlasLod={} to ACTIVE program {}", 8, active.getName());
        }
        }
    }

    public static void onTextureBound(Identifier id) {
        onTextureBound(-1, id);
    }

    public static void setPendingForBlockAtlas() {
        pendingR = -1;
        pendingG = -1;
        pendingB = -1;
        ShaderProgram active = RenderSystem.getShader();
        if (active != null) {
            try {
                active.bind();
            } catch (Throwable t) {
                LOGGER.warn("setPendingForBlockAtlas: active.bind failed: {}", t.toString());
            }
            GlUniform r = active.getUniform("entityColourR");
            GlUniform g = active.getUniform("entityColourG");
        GlUniform b = active.getUniform("entityColourB");
        if (r != null && g != null && b != null) {
            r.set(-1);
            g.set(-1);
            b.set(-1);
            LOGGER.debug("Applied block-atlas override to ACTIVE program {} -> R:-1 G:-1 B:-1", active.getName());
        }
        }
    }

    public static Identifier getLastBoundTexture() {
        return lastBoundTexture;
    }

    public static int[] getPendingColourRGB() {
        return new int[]{pendingR, pendingG, pendingB};
    }

    /** If the last bound texture is the block atlas and we know the current block type,
     * override the pending RGB to a stable per-type colour. */
    public static void updateAtlasOverrideColourForCurrentBlock() {
        try {
            if (lastBoundTexture == null) return;
            boolean isAtlas = SpriteAtlasTexture.BLOCK_ATLAS_TEXTURE.equals(lastBoundTexture)
                    || (lastBoundTexture.getPath() != null && lastBoundTexture.getPath().contains("textures/atlas/"));
            if (!isAtlas) return;
            if (currentBlockType == null || currentBlockType.isEmpty()) return;
            int rgb = getColourForBlockType(currentBlockType) & 0x00FFFFFF;
            pendingR = (rgb >> 16) & 0xFF;
            pendingG = (rgb >> 8) & 0xFF;
            pendingB = (rgb) & 0xFF;
        } catch (Throwable ignored) {}
    }

    public static void applyPendingColourToProgram(ShaderProgram program) {
        if (program == null) return;
        try {
            program.bind();
        } catch (Throwable t) {
            LOGGER.warn("applyPendingColourToProgram: program.bind failed: {}", t.toString());
        }
        if (isProducingColourMap && colourmapFrame) {
            // Strongly prefer stable colours per entity/block at draw time.
            if (hasCurrentEntity() || strictEntityDraw) {
                setPendingColourForCurrentEntity();
            } else if (isDrawingBlock() || strictBlockDraw) {
                setPendingColourForCurrentBlock();
            } else if (pendingR == 0 && pendingG == 0 && pendingB == 0) {
                // Avoid all-black output before any texture bind has set a colour.
                pendingR = pendingG = pendingB = -1;
            }
        }
        // Additional hardening: if the last bound texture indicates an entity and
        // we don't have currentEntity, avoid atlas fallback by hashing the path.
        try {
            Identifier last = lastBoundTexture;
            if (isProducingColourMap && colourmapFrame && last != null) {
                String p = last.getPath();
                boolean pendIsAtlas = (pendingR < 0 || pendingG < 0 || pendingB < 0);
                if (p != null && p.startsWith("textures/entity/") && !hasCurrentEntity() && pendIsAtlas) {
                    int fb = getFallbackEntityColourFromTexture(last);
                    if (fb != -1) {
                        pendingR = (fb >> 16) & 0xFF;
                        pendingG = (fb >> 8) & 0xFF;
                        pendingB = (fb) & 0xFF;
                    }
                } else if ((SpriteAtlasTexture.BLOCK_ATLAS_TEXTURE.equals(last) || (p != null && p.contains("textures/atlas/"))) && (isDrawingBlock() || currentBlockType != null)) {
                    // Ensure blocks keep their per-type colour even if a late bind overwrote pending to -1
                    setPendingColourForCurrentBlock();
                }

                // Diagnostic: log when we are about to apply a colour for the chicken texture
                // but no current entity is set. This helps track overwrites of the chicken's ID.
                if (p != null
                        && p.startsWith("textures/entity/chicken/")
                        && !hasCurrentEntity()
                        && !isStrictEntityDraw()) {
                    LOGGER.info("Segmentation: applyPendingColourToProgram for CHICKEN with NO current entity; pending=({}, {}, {})",
                            pendingR, pendingG, pendingB);
                }
            }
        } catch (Throwable ignored) {}
        GlUniform r = program.getUniform("entityColourR");
        GlUniform g = program.getUniform("entityColourG");
        GlUniform b = program.getUniform("entityColourB");
        GlUniform dbg = program.getUniform("debugMode");
        GlUniform alpha = program.getUniform("respectAlpha");
        GlUniform grid = program.getUniform("atlasGrid");
        if (r != null && g != null && b != null) {
            r.set(pendingR);
            g.set(pendingG);
            b.set(pendingB);
            r.upload();
            g.upload();
            b.upload();
            LOGGER.debug("Applied pending colour to PROGRAM {} -> R:{} G:{} B:{}", program.getName(), pendingR, pendingG, pendingB);
        } else {
            LOGGER.warn("Program {} missing annotate uniforms; colours not applied", program.getName());
        }
        if (dbg != null) {
            dbg.set(segmentationDebugLevel);
            dbg.upload();
            LOGGER.debug("Applied debugMode={} to PROGRAM {}", segmentationDebugLevel, program.getName());
        }
        if (alpha != null) {
            alpha.set(respectOpacity ? 1 : 0);
            alpha.upload();
            LOGGER.debug("Applied respectAlpha={} to PROGRAM {}", (respectOpacity ? 1 : 0), program.getName());
        }
        if (grid != null) {
            int atlasGrid = 32;
            grid.set(atlasGrid);
            grid.upload();
            LOGGER.debug("Applied atlasGrid={} to PROGRAM {}", atlasGrid, program.getName());
        }
    }

    private static void ensureInitialised() {
        if (initialised) return;
        shaderProgram = createProgram("annotate");
        if (shaderProgram <= 0) {
            initialised = true;
            return;
        }
        uniformR = GL20.glGetUniformLocation(shaderProgram, "entityColourR");
        uniformG = GL20.glGetUniformLocation(shaderProgram, "entityColourG");
        uniformB = GL20.glGetUniformLocation(shaderProgram, "entityColourB");
        initialised = true;
    }

    public static ShaderProgram getAnnotateProgramForFormat(VertexFormat format) {
        java.util.List<String> names = format.getAttributeNames();
        String key = String.join(",", names);
        ShaderProgram prog = annotatePrograms.get(key);
        if (prog != null) return prog;

        boolean hasUv = format.has(VertexFormatElement.UV_0);
        boolean hasColor = format.has(VertexFormatElement.COLOR);

        if (colourmapFrame) {
            if (hasUv) segProgramSwapsUV++;
            else segProgramSwapsNoUV++;
        }

        String vName;
        if (hasUv && hasColor) {
            vName = "annotate_position_tex_color";
        } else if (hasUv) {
            vName = "annotate_position_tex";
        } else if (hasColor) {
            vName = "annotate_position_color";
        } else {
            vName = "annotate_position";
        }

        try {
            prog = new ShaderProgram(MinecraftClient.getInstance().getResourceManager(), vName, format);
            annotatePrograms.put(key, prog);
            return prog;
        } catch (Exception e) {
            LOGGER.warn("Failed to create annotate program for format {} using '{}': {}", key, vName, e.getMessage());
            try {
                String fbName = hasUv ? "annotate_position_tex" : "annotate_position";
                VertexFormat fbFormat = hasUv ? VertexFormats.POSITION_TEXTURE : VertexFormats.POSITION;
                String fbKey = String.join(",", fbFormat.getAttributeNames());
                ShaderProgram fallback = annotatePrograms.get(fbKey);
                if (fallback == null) {
                    fallback = new ShaderProgram(MinecraftClient.getInstance().getResourceManager(), fbName, fbFormat);
                    annotatePrograms.put(fbKey, fallback);
                }
                return fallback;
            } catch (Exception ex) {
                throw new RuntimeException("No annotate shader available for format: " + key, ex);
            }
        }
    }

    private static int loadShader(String filename, int shaderType) {
        try (InputStream stream = TextureHelper.class.getClassLoader().getResourceAsStream(filename)) {
            if (stream == null) return -1;
            String src;
            try (BufferedInputStream bis = new BufferedInputStream(stream)) {
                byte[] bytes = bis.readAllBytes();
                src = new String(bytes, StandardCharsets.UTF_8);
            }
            int shader = GL20.glCreateShader(shaderType);
            GL20.glShaderSource(shader, src);
            GL20.glCompileShader(shader);
            int status = GL20.glGetShaderi(shader, GL20.GL_COMPILE_STATUS);
            if (status == GL11.GL_FALSE) {
                String info = GL20.glGetShaderInfoLog(shader);
                LOGGER.error("Shader compile error ({}): {}", filename, info);
                GL20.glDeleteShader(shader);
                return -1;
            }
            return shader;
        } catch (Exception e) {
            LOGGER.error("Failed to load shader {}: {}", filename, e.getMessage());
            return -1;
        }
    }

    private static int createProgram(String baseName) {
        int prog = GL20.glCreateProgram();
        int v = loadShader(baseName + ".vsh", GL20.GL_VERTEX_SHADER);
        int f = loadShader(baseName + ".fsh", GL20.GL_FRAGMENT_SHADER);
        if (v <= 0 || f <= 0) {
            if (v > 0) GL20.glDeleteShader(v);
            if (f > 0) GL20.glDeleteShader(f);
            GL20.glDeleteProgram(prog);
            return -1;
        }
        GL20.glAttachShader(prog, v);
        GL20.glAttachShader(prog, f);
        GL20.glLinkProgram(prog);
        int link = GL20.glGetProgrami(prog, GL20.GL_LINK_STATUS);
        if (link == GL11.GL_FALSE) {
            String info = GL20.glGetProgramInfoLog(prog);
            LOGGER.error("Shader link error: {}", info);
            GL20.glDeleteShader(v);
            GL20.glDeleteShader(f);
            GL20.glDeleteProgram(prog);
            return -1;
        }
        GL20.glDetachShader(prog, v);
        GL20.glDetachShader(prog, f);
        GL20.glDeleteShader(v);
        GL20.glDeleteShader(f);
        return prog;
    }

    public static synchronized void ensureSegmentationFramebuffer(int width, int height) {
        if (segmentationFbo == null || segmentationFbo.textureWidth != width || segmentationFbo.textureHeight != height) {
            if (segmentationFbo != null) {
                segmentationFbo.delete();
            }
            segmentationFbo = new SimpleFramebuffer(width, height, true, MinecraftClient.IS_SYSTEM_MAC);
            LOGGER.debug("TextureHelper: created/updated segmentation FBO {} size {}x{}", segmentationFbo.fbo, width, height);
        }
    }

    /**
     * Deletes and clears the segmentation framebuffer owned by TextureHelper.
     * Safe to call repeatedly.
     */
    public static synchronized void destroySegmentationFramebuffer() {
        if (segmentationFbo != null) {
            try {
                segmentationFbo.delete();
            } catch (Throwable ignored) {}
            segmentationFbo = null;
            LOGGER.debug("TextureHelper: destroyed segmentation FBO");
        }
    }

    // Saved GL state toggled during segmentation pass (extended)
    private static boolean prevCull = false;
    private static int prevDrawFb = 0;
    private static int prevReadFb = 0;
    private static int prevDrawBuf = 0;
    private static int prevReadBuf = 0;
    private static final int[] PREV_VIEWPORT = new int[4];
    private static int prevProgram = 0;

    // Debug probe for tracking which draw first changes a specific pixel
    private static final int DEBUG_PROBE_X = 770;
    private static final int DEBUG_PROBE_Y = 250; // screen-space, origin at top-left
    private static int debugProbeLastSample = -1;
    private static int debugProbeDrawIndex = 0;
    // Separate toggle for probe so we don't have to enable shader debug visuals.
    private static final boolean SEG_PROBE_FORCED =
            Boolean.parseBoolean(System.getProperty("vereya.seg.probe", "false"));

    // Optional region probe around the chicken pixel to see if an entity render
    // changes any pixels at all within that window.
    private static final int DEBUG_REGION_RADIUS = 30;
    private static int regionProbeX0 = -1;
    private static int regionProbeY0 = -1;
    private static int regionProbeW = 0;
    private static int regionProbeH = 0;
    private static int[] regionProbeBefore = null;

    private static boolean isProbeEnabled() {
        // Either explicitly enabled via -Dvereya.seg.probe=true or via high debug level.
        if (segmentationFbo == null) return false;
        if (SEG_PROBE_FORCED) return true;
        return segmentationDebugLevel >= 3;
    }

    private static boolean isRegionProbeEnabled() {
        return SEG_PROBE_FORCED && segmentationFbo != null;
    }

    /** Snapshot the entire segmentation FBO before an entity render. */
    public static void beginEntityRegionProbe() {
        if (!isProducingColourMap || !colourmapFrame) return;
        if (!isRegionProbeEnabled()) return;
        try {
            int width = segmentationFbo.textureWidth;
            int height = segmentationFbo.textureHeight;
            if (width <= 0 || height <= 0) return;
            int x0 = 0;
            int y0 = 0;
            int w = width;
            int h = height;
            regionProbeX0 = x0;
            regionProbeY0 = y0;
            regionProbeW = w;
            regionProbeH = h;
            java.nio.ByteBuffer buf = org.lwjgl.BufferUtils.createByteBuffer(w * h * 4);
            GL11.glPixelStorei(GL11.GL_PACK_ALIGNMENT, 1);
            int prevFb = GL11.glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING);
            try {
                GlStateManager._glBindFramebuffer(GlConst.GL_READ_FRAMEBUFFER, segmentationFbo.fbo);
                // As with the single-pixel probe, treat y indices as matching buffer rows.
                GL11.glReadPixels(x0, y0, w, h, GL12.GL_BGRA, GL11.GL_UNSIGNED_BYTE, buf);
            } finally {
                GlStateManager._glBindFramebuffer(GlConst.GL_READ_FRAMEBUFFER, prevFb);
            }
            regionProbeBefore = new int[w * h];
            for (int i = 0; i < w * h; i++) {
                int sample = 0;
                for (int k = 0; k < 4; k++) {
                    sample |= (buf.get(i * 4 + k) & 0xFF) << (k * 8);
                }
                regionProbeBefore[i] = sample;
            }
            LOGGER.info("SegDebug: entity region probe begin at x0={}, y0={}, w={}, h={}", x0, y0, w, h);
        } catch (Throwable t) {
            LOGGER.warn("SegDebug: entity region probe begin failed: {}", t.toString());
            regionProbeBefore = null;
        }
    }

    /** Compare region snapshot after an entity render to see how many pixels changed. */
    public static void endEntityRegionProbe(net.minecraft.entity.Entity entity) {
        if (!isRegionProbeEnabled()) return;
        if (regionProbeBefore == null || regionProbeW <= 0 || regionProbeH <= 0) return;
        try {
            int w = regionProbeW;
            int h = regionProbeH;
            int x0 = regionProbeX0;
            int y0 = regionProbeY0;
            java.nio.ByteBuffer buf = org.lwjgl.BufferUtils.createByteBuffer(w * h * 4);
            GL11.glPixelStorei(GL11.GL_PACK_ALIGNMENT, 1);
            int prevFb = GL11.glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING);
            try {
                GlStateManager._glBindFramebuffer(GlConst.GL_READ_FRAMEBUFFER, segmentationFbo.fbo);
                GL11.glReadPixels(x0, y0, w, h, GL12.GL_BGRA, GL11.GL_UNSIGNED_BYTE, buf);
            } finally {
                GlStateManager._glBindFramebuffer(GlConst.GL_READ_FRAMEBUFFER, prevFb);
            }
            int changed = 0;
            int firstOld = 0;
            int firstNew = 0;
            for (int i = 0; i < w * h; i++) {
                int sample = 0;
                for (int k = 0; k < 4; k++) {
                    sample |= (buf.get(i * 4 + k) & 0xFF) << (k * 8);
                }
                if (sample != regionProbeBefore[i]) {
                    changed++;
                    if (changed == 1) {
                        firstOld = regionProbeBefore[i];
                        firstNew = sample;
                    }
                }
            }
            int oldB = firstOld & 0xFF;
            int oldG = (firstOld >>> 8) & 0xFF;
            int oldR = (firstOld >>> 16) & 0xFF;
            int oldA = (firstOld >>> 24) & 0xFF;
            int newB = firstNew & 0xFF;
            int newG = (firstNew >>> 8) & 0xFF;
            int newR = (firstNew >>> 16) & 0xFF;
            int newA = (firstNew >>> 24) & 0xFF;
            String ent = (entity != null) ? (entity.getType().toString() + "#" + entity.getId()) : "<null>";
            LOGGER.info("SegDebug: entity region probe end for {}: changedPixels={} (of {}), firstDiff 0x{} (R={},G={},B={},A={}) -> 0x{} (R={},G={},B={},A={})",
                    ent, changed, w * h,
                    Integer.toHexString(firstOld), oldR, oldG, oldB, oldA,
                    Integer.toHexString(firstNew), newR, newG, newB, newA);
        } catch (Throwable t) {
            LOGGER.warn("SegDebug: entity region probe end failed: {}", t.toString());
        } finally {
            regionProbeBefore = null;
            regionProbeW = regionProbeH = 0;
        }
    }

    /** Called once per GL drawElements during the segmentation pass. */
    public static void onSegmentationDrawCall() {
        if (!isProducingColourMap || !colourmapFrame) {
            return;
        }
        debugProbeDrawIndex++;
        if (!isProbeEnabled()) {
            return;
        }
        try {
            // Ensure we are sampling from the segmentation FBO.
            int fb = GL11.glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING);
            if (fb != segmentationFbo.fbo) {
                return;
            }
            int width = segmentationFbo.textureWidth;
            int height = segmentationFbo.textureHeight;
            if (width <= 0 || height <= 0) {
                return;
            }
            int px = DEBUG_PROBE_X;
            int py = DEBUG_PROBE_Y;
            if (px < 0 || px >= width || py < 0 || py >= height) {
                return;
            }
            // PNG/Gimp coordinates are top-left; our saved PNG loop writes
            // FBO row 0 (bottom) into image row 0 (top), so numerically the
            // row indices match the buffer order. Therefore we sample using
            // the same Y index without flipping.
            int glY = py;
            java.nio.ByteBuffer buf = org.lwjgl.BufferUtils.createByteBuffer(4);
            GL11.glPixelStorei(GL11.GL_PACK_ALIGNMENT, 1);
            GL11.glReadPixels(px, glY, 1, 1, GL12.GL_BGRA, GL11.GL_UNSIGNED_BYTE, buf);
            int sample = 0;
            for (int k = 0; k < 4; k++) {
                sample |= (buf.get(k) & 0xFF) << (k * 8);
            }
            // BGRA layout from GL12.GL_BGRA
            int b = sample & 0xFF;
            int g = (sample >>> 8) & 0xFF;
            int r = (sample >>> 16) & 0xFF;
            int a = (sample >>> 24) & 0xFF;
            if (debugProbeLastSample == -1) {
                debugProbeLastSample = sample;
                LOGGER.info("SegDebug: initial probe sample at (x={}, y={}) -> 0x{} (R={}, G={}, B={}, A={}) (drawIndex={})",
                        px, py, Integer.toHexString(sample), r, g, b, a, debugProbeDrawIndex);
            } else if (sample != debugProbeLastSample) {
                Identifier tex = lastBoundTexture;
                String texStr = tex != null ? tex.toString() : "<none>";
                int prevB = debugProbeLastSample & 0xFF;
                int prevG = (debugProbeLastSample >>> 8) & 0xFF;
                int prevR = (debugProbeLastSample >>> 16) & 0xFF;
                int prevA = (debugProbeLastSample >>> 24) & 0xFF;
                LOGGER.info("SegDebug: probe change at drawIndex={} from 0x{} (R={}, G={}, B={}, A={}) to 0x{} (R={}, G={}, B={}, A={}), lastTex={}, hasEntity={} strictEntityDraw={} pending=({}, {}, {})",
                        debugProbeDrawIndex,
                        Integer.toHexString(debugProbeLastSample), prevR, prevG, prevB, prevA,
                        Integer.toHexString(sample), r, g, b, a,
                        texStr,
                        hasCurrentEntity(),
                        isStrictEntityDraw(),
                        pendingR, pendingG, pendingB);
                debugProbeLastSample = sample;
            }
        } catch (Throwable t) {
            LOGGER.warn("SegDebug: probe read failed: {}", t.toString());
        }
    }

    public static void beginSegmentationPass() {
        if (segmentationFbo != null) {
            segAtlasBinds = segEntityBinds = segOtherBinds = 0;
            segProgramSwapsUV = segProgramSwapsNoUV = 0;
            try {
                LOGGER.debug("TextureHelper: beginSegPass (debugLevel={}) FBO={} size={}x{}", segmentationDebugLevel,
                        segmentationFbo.fbo,
                        segmentationFbo.textureWidth,
                        segmentationFbo.textureHeight);
            } catch (Throwable t) {
                LOGGER.warn("TextureHelper: logging beginSegPass failed: {}", t.toString());
            }
            // Capture GL state before we mutate it
            try {
                prevProgram = GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM);
                prevBlend = GL11.glIsEnabled(GL11.GL_BLEND);
                prevDepth = GL11.glIsEnabled(GL11.GL_DEPTH_TEST);
                prevScissor = GL11.glIsEnabled(GL11.GL_SCISSOR_TEST);
                prevStencil = GL11.glIsEnabled(GL11.GL_STENCIL_TEST);
                prevCull = GL11.glIsEnabled(GL11.GL_CULL_FACE);
                prevDrawFb = GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING);
                prevReadFb = GL11.glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING);
                prevDrawBuf = GL11.glGetInteger(GL11.GL_DRAW_BUFFER);
                prevReadBuf = GL11.glGetInteger(GL11.GL_READ_BUFFER);
                GL11.glGetIntegerv(GL11.GL_VIEWPORT, PREV_VIEWPORT);
            } catch (Throwable ignored) {}

            segmentationFbo.beginWrite(true);
            try {
                GlStateManager._glBindFramebuffer(GlConst.GL_DRAW_FRAMEBUFFER, segmentationFbo.fbo);
                GlStateManager._glBindFramebuffer(GlConst.GL_READ_FRAMEBUFFER, segmentationFbo.fbo);
            } catch (Throwable t) {
                LOGGER.warn("TextureHelper: explicit FBO bind failed: {}", t.toString());
            }
            try {
                GL30.glDrawBuffer(GL30.GL_COLOR_ATTACHMENT0);
                GL11.glReadBuffer(GL30.GL_COLOR_ATTACHMENT0);
                GL11.glViewport(0, 0, segmentationFbo.textureWidth, segmentationFbo.textureHeight);
                try {
                    GL11.glDisable(GL30.GL_RASTERIZER_DISCARD);
                } catch (Throwable t) {
                    LOGGER.warn("TextureHelper: disable RASTERIZER_DISCARD failed: {}", t.toString());
                }
                try {
                    GL11.glDisable(GL11.GL_COLOR_LOGIC_OP);
                } catch (Throwable t) {
                    LOGGER.warn("TextureHelper: disable COLOR_LOGIC_OP at beginSegPass failed: {}", t.toString());
                }
            } catch (Throwable t) {
                LOGGER.warn("TextureHelper: beginSegPass draw/read buffer or viewport set failed: {}", t.toString());
            }
            try {
                if (segmentationDebugLevel == 1) {
                    GL11.glClearColor(1f, 0f, 1f, 1f);
                } else if (segmentationDebugLevel >= 2) {
                    GL11.glClearColor(0f, 0f, 0f, 1f);
                } else {
                    float cr = 0f;
                    float cg = 0f;
                    float cb = 0f;
                    Object sky = getSkyRenderer();
                    if (sky instanceof TextureHelper.BlankSkyRenderer) {
                        BlankSkyRenderer bs = (BlankSkyRenderer) sky;
                        cr = (bs.r & 0xFF) / 255.0f;
                        cg = (bs.g & 0xFF) / 255.0f;
                        cb = (bs.b & 0xFF) / 255.0f;
                    }
                    GL11.glClearColor(cr, cg, cb, 1f);
                }
                GL11.glClear(GL11.GL_COLOR_BUFFER_BIT | GL11.GL_DEPTH_BUFFER_BIT);
            } catch (Throwable t) {
                LOGGER.warn("TextureHelper: manual clear failed: {}", t.toString());
            }
            try {
                int fb = GL11.glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING);
                if (fb == segmentationFbo.fbo) {
                    java.nio.ByteBuffer px = org.lwjgl.BufferUtils.createByteBuffer(4);
                    GL11.glPixelStorei(GL11.GL_PACK_ALIGNMENT, 1);
                    GL11.glReadPixels(0, 0, 1, 1, GL12.GL_BGRA, GL11.GL_UNSIGNED_BYTE, px);
                    int sample = 0;
                    for (int k = 0; k < 4; k++) sample |= (px.get(k) & 0xFF);
                    LOGGER.debug("TextureHelper: post-clear 1x1 BGRA sample_or={} (0 implies clear not applied)", sample);
                } else {
                    LOGGER.debug("TextureHelper: post-clear read skipped; READ_FB={} not seg FBO {}", fb, segmentationFbo.fbo);
                }
            } catch (Throwable t) {
                LOGGER.warn("TextureHelper: post-clear read failed: {}", t.toString());
            }
            try {
                prevBlend = GL11.glIsEnabled(GL11.GL_BLEND);
                prevDepth = GL11.glIsEnabled(GL11.GL_DEPTH_TEST);
                prevScissor = GL11.glIsEnabled(GL11.GL_SCISSOR_TEST);
                prevStencil = GL11.glIsEnabled(GL11.GL_STENCIL_TEST);
                LOGGER.debug("TextureHelper: beginSegPass before state -> BLEND={} DEPTH={} SCISSOR={} STENCIL={}", prevBlend, prevDepth, prevScissor, prevStencil);
                GL11.glDisable(GL11.GL_BLEND);
                GL11.glDisable(GL11.GL_SCISSOR_TEST);
                GL11.glDisable(GL11.GL_STENCIL_TEST);
                GL11.glDisable(GL11.GL_CULL_FACE);
                GL11.glColorMask(true, true, true, true);
                if (segmentationDebugLevel != 0) {
                    GL11.glDisable(GL11.GL_DEPTH_TEST);
                }
                boolean blendAfter = GL11.glIsEnabled(GL11.GL_BLEND);
                boolean depthAfter = GL11.glIsEnabled(GL11.GL_DEPTH_TEST);
                boolean scissorAfter = GL11.glIsEnabled(GL11.GL_SCISSOR_TEST);
                boolean stencilAfter = GL11.glIsEnabled(GL11.GL_STENCIL_TEST);
                boolean cullAfter = GL11.glIsEnabled(GL11.GL_CULL_FACE);
                LOGGER.debug("TextureHelper: beginSegPass after state -> BLEND={} DEPTH={} SCISSOR={} STENCIL={} CULL={}", blendAfter, depthAfter, scissorAfter, stencilAfter, cullAfter);
            } catch (Throwable t) {
                LOGGER.warn("TextureHelper: beginSegPass state mutate failed: {}", t.toString());
            }
            try {
                int drawFb = GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING);
                int readFb = GL11.glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING);
                int drawBuf = GL11.glGetInteger(GL11.GL_DRAW_BUFFER);
                int readBuf = GL11.glGetInteger(GL11.GL_READ_BUFFER);
                boolean rd = false;
                try {
                    rd = GL11.glIsEnabled(GL30.GL_RASTERIZER_DISCARD);
                } catch (Throwable t) {
                    LOGGER.warn("TextureHelper: query RASTERIZER_DISCARD failed: {}", t.toString());
                }
                LOGGER.debug("TextureHelper: beginSegPass -> DRAW_FB={} READ_FB={} DRAW_BUF={} READ_BUF={} RASTERIZER_DISCARD={}", drawFb, readFb, drawBuf, readBuf, rd);
            } catch (Throwable t) {
                LOGGER.warn("TextureHelper: beginSegPass state query failed: {}", t.toString());
            }

            // Reset debug probe state for this segmentation frame.
            debugProbeLastSample = -1;
            debugProbeDrawIndex = 0;
        }
    }

    public static void endSegmentationPass() {
        if (segmentationFbo != null) {
            try {
                GlStateManager._glBindFramebuffer(GlConst.GL_READ_FRAMEBUFFER, segmentationFbo.fbo);
                GL11.glReadBuffer(GL30.GL_COLOR_ATTACHMENT0);
                GL11.glPixelStorei(GL11.GL_PACK_ALIGNMENT, 1);
                // Legacy sample at (0,0) for sanity
                java.nio.ByteBuffer px0 = org.lwjgl.BufferUtils.createByteBuffer(4);
                GL11.glReadPixels(0, 0, 1, 1, GL12.GL_BGRA, GL11.GL_UNSIGNED_BYTE, px0);
                int sample0 = 0;
                for (int k = 0; k < 4; k++) sample0 |= (px0.get(k) & 0xFF) << (k * 8);
                LOGGER.debug("TextureHelper: endSegPass 1x1 BGRA sample_or={} (0 implies black)", sample0);

                // Debug: also report final colour at the probe pixel (using top-left style coords).
                try {
                    int width = segmentationFbo.textureWidth;
                    int height = segmentationFbo.textureHeight;
                    int px = DEBUG_PROBE_X;
                    int py = DEBUG_PROBE_Y;
                    if (width > 0 && height > 0 && px >= 0 && px < width && py >= 0 && py < height) {
                        int glY = py;
                        java.nio.ByteBuffer buf = org.lwjgl.BufferUtils.createByteBuffer(4);
                        GL11.glReadPixels(px, glY, 1, 1, GL12.GL_BGRA, GL11.GL_UNSIGNED_BYTE, buf);
                        int s = 0;
                        for (int k = 0; k < 4; k++) s |= (buf.get(k) & 0xFF) << (k * 8);
                        int fb = s & 0xFF;
                        int fg = (s >>> 8) & 0xFF;
                        int fr = (s >>> 16) & 0xFF;
                        int fa = (s >>> 24) & 0xFF;
                        LOGGER.info("SegDebug: final probe sample at (x={}, y={}) -> 0x{} (R={}, G={}, B={}, A={})",
                                px, py, Integer.toHexString(s), fr, fg, fb, fa);
                    }
                } catch (Throwable tProbe) {
                    LOGGER.warn("SegDebug: final probe read failed: {}", tProbe.toString());
                }

                LOGGER.debug("TextureHelper: seg frame binds -> atlas={} entity={} other={}, program swaps -> withUV={} withoutUV={} ", segAtlasBinds, segEntityBinds, segOtherBinds, segProgramSwapsUV, segProgramSwapsNoUV);
            } catch (Throwable t) {
                LOGGER.warn("TextureHelper: endSegPass sample failed: {}", t.toString());
            }
            segmentationFbo.endWrite();
            try { GL20.glUseProgram(prevProgram); } catch (Throwable ignored) {}
            try {
                if (prevBlend) GL11.glEnable(GL11.GL_BLEND); else GL11.glDisable(GL11.GL_BLEND);
                if (prevScissor) GL11.glEnable(GL11.GL_SCISSOR_TEST); else GL11.glDisable(GL11.GL_SCISSOR_TEST);
                if (prevStencil) GL11.glEnable(GL11.GL_STENCIL_TEST); else GL11.glDisable(GL11.GL_STENCIL_TEST);
                if (prevDepth) GL11.glEnable(GL11.GL_DEPTH_TEST); else GL11.glDisable(GL11.GL_DEPTH_TEST);
                if (prevCull) GL11.glEnable(GL11.GL_CULL_FACE); else GL11.glDisable(GL11.GL_CULL_FACE);
                // Restore framebuffer bindings, draw/read buffers and viewport
                try {
                    GlStateManager._glBindFramebuffer(GlConst.GL_DRAW_FRAMEBUFFER, prevDrawFb);
                    GlStateManager._glBindFramebuffer(GlConst.GL_READ_FRAMEBUFFER, prevReadFb);
                    GL11.glDrawBuffer(prevDrawBuf);
                    GL11.glReadBuffer(prevReadBuf);
                    GL11.glViewport(PREV_VIEWPORT[0], PREV_VIEWPORT[1], PREV_VIEWPORT[2], PREV_VIEWPORT[3]);
                } catch (Throwable ignored) {}
                try {
                    GL11.glDisable(GL30.GL_RASTERIZER_DISCARD);
                } catch (Throwable t) {
                    LOGGER.warn("TextureHelper: disable RASTERIZER_DISCARD at endSegPass failed: {}", t.toString());
                }
            } catch (Throwable t) {
                LOGGER.warn("TextureHelper: endSegPass restore state failed: {}", t.toString());
            }
            try {
                int drawFb = GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING);
                int readFb = GL11.glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING);
                int drawBuf = GL11.glGetInteger(GL11.GL_DRAW_BUFFER);
                int readBuf = GL11.glGetInteger(GL11.GL_READ_BUFFER);
                boolean rd = false;
                try {
                    rd = GL11.glIsEnabled(GL30.GL_RASTERIZER_DISCARD);
                } catch (Throwable t) {
                    LOGGER.warn("TextureHelper: query RASTERIZER_DISCARD at endSegPass failed: {}", t.toString());
                }
                LOGGER.debug("TextureHelper: endSegPass -> DRAW_FB={} READ_FB={} DRAW_BUF={} READ_BUF={} RASTERIZER_DISCARD={}", drawFb, readFb, drawBuf, readBuf, rd);
            } catch (Throwable t) {
                LOGGER.warn("TextureHelper: endSegPass state query failed: {}", t.toString());
            }
        }
    }

    public static Framebuffer getSegmentationFramebuffer() {
        return segmentationFbo;
    }

    /**
     * Resets transient segmentation state like current entity, block type,
     * pending colours, and last bound texture. Does not toggle the
     * isProducingColourMap flag; callers should set that explicitly.
     */
    public static void resetSegmentationState() {
        currentEntity = null;
        currentBlockType = null;
        drawingBlock = false;
        pendingR = pendingG = pendingB = 0;
        lastBoundTexture = null;
        segAtlasBinds = segEntityBinds = segOtherBinds = 0;
        segProgramSwapsUV = segProgramSwapsNoUV = 0;
    }
}
