Segmentation pipeline (Vereya)
=====================================

Overview
--------

Vereya renders a segmentation (colour-map) image by running an extra world
render per frame into an off-screen framebuffer, using custom *annotate*
shaders instead of Mojang's normal terrain/entity shaders. The normal
framebuffer is left untouched; only the additional pass is modified.

During the segmentation pass:

- The same vertex buffers and `VertexFormat`s are used as in the normal
  render.
- The active shader programs are swapped to `annotate_*` variants.
- A small set of uniforms (per-entity/per-block RGB, debug flags, atlas
  parameters) control the colour written for each fragment.
- Texture bindings are observed and used only to select pending colours and
  block/entity fallbacks; the actual shader uniforms are uploaded once per
  draw, not per texture bind.

Entry point and framebuffer
---------------------------

- **`WorldRendererColourmapMixin`**
  - Target: `net.minecraft.client.render.WorldRenderer`
  - Behaviour:
    - Injects at `WorldRenderer.render(...) @TAIL`.
    - If `TextureHelper.isProducingColourMap()` is `true` and a re-entrancy
      guard is not set:
      - Sets `TextureHelper.colourmapFrame = true`.
      - Calls `TextureHelper.beginSegmentationPass()` to:
        - create/resize an off-screen `SimpleFramebuffer` as needed,
        - capture current GL state, and
        - bind the segmentation FBO as draw/read target.
      - Invokes `WorldRenderer.render(...)` again with copies of the position
        and projection matrices; this second render writes into the
        segmentation FBO.
      - Finally calls `TextureHelper.endSegmentationPass()` and restores
        `colourmapFrame` and the re-entrancy flag.

Shader swapping
---------------

- **`WorldRendererShaderMixin`**
  - Target: `net.minecraft.client.render.WorldRenderer`
  - Behaviour:
    - Redirects `RenderSystem.setShader(Supplier<ShaderProgram>)` inside
      `WorldRenderer.render`.
    - When `TextureHelper.isProducingColourMap()` and
      `TextureHelper.colourmapFrame` are true:
      - Obtains the original `ShaderProgram`.
      - Asks `TextureHelper.getAnnotateProgramForFormat(orig.getFormat())`
        for an appropriate `annotate_*` shader program compatible with the
        vertex format.
      - Calls `TextureHelper.applyPendingColourToProgram(annotate)` to push
        any pending per-entity/block colour hints.
      - Sets the active shader to the annotate program via
        `RenderSystem.setShader(() -> annotate)`.
    - Outside the segmentation pass, forwards the original shader supplier
      unchanged.

- **`VertexBufferMixin`**
  - Target: `net.minecraft.client.gl.VertexBuffer`
  - Behaviour:
    - `@ModifyVariable` on
      `draw(Matrix4f, Matrix4f, ShaderProgram)`:
      - When segmentation is active, replaces the supplied `ShaderProgram`
        with an annotate program from
        `TextureHelper.getAnnotateProgramForFormat(...)`, and calls
        `TextureHelper.applyPendingColourToProgram(annotate)` before draw.
    - `@Inject` at `HEAD` of `draw(...)`:
      - When segmentation is active and an entity is being rendered, ensures
        `TextureHelper.setPendingColourForCurrentEntity()` has been called so
        the entity gets a stable colour.
      - Leaves block draw flags to `BlockRenderManagerMixin`.
    - `@Inject` at `TAIL` of `draw(...)`:
      - Clears `TextureHelper.setDrawingBlock(false)` so block draw flags
        do not leak between draws.

Entity context (per-entity colours)
-----------------------------------

- **`EntityRenderDispatcherMixin`**
  - Target: `net.minecraft.client.render.entity.EntityRenderDispatcher`
  - Behaviour:
    - At `render(Entity, ...) @HEAD`:
      - If segmentation is active, calls:
        - `TextureHelper.setCurrentEntity(entity)`,
        - `TextureHelper.setPendingColourForEntity(entity)`,
        - `TextureHelper.setStrictEntityDraw(true)`.
      - This marks the entire dispatcher-render of that entity as a
        single-colour segment.
    - At `render(Entity, ...) @TAIL`:
      - Clears `currentEntity` and `strictEntityDraw`.

- **`EntityRendererMixin`**
  - Target: `net.minecraft.client.render.entity.EntityRenderer`
  - Behaviour:
    - Mirrors the dispatcher mixin but at the per-renderer level:
      - At `render(Entity, ...) @HEAD`: sets `currentEntity` and
        `strictEntityDraw` and seeds the pending colour.
      - At `render(Entity, ...) @TAIL`: clears them.
    - This ensures that all entity render paths (including some where the
      dispatcher hook might not fire as expected) still get a single,
      stable per-entity colour.

Block context (per-block colours)
---------------------------------

- **`BlockRenderManagerMixin`**
  - Target: `net.minecraft.client.render.block.BlockRenderManager`
  - Behaviour:
    - At `renderBlock(...) @HEAD`:
      - Looks up the block's registry id via `Registries.BLOCK.getId`.
      - Calls `TextureHelper.setCurrentBlockType(id.toString())`.
      - When segmentation is active, also:
        - `TextureHelper.setDrawingBlock(true)`,
        - `TextureHelper.setStrictBlockDraw(true)`,
        - `TextureHelper.setPendingColourForCurrentBlock()`.
      - This gives all draws inside the block render scope a stable,
        per-block-type colour.
    - At `renderBlock(...) @TAIL`:
      - Clears `drawingBlock` and `strictBlockDraw`.

Texture binding hooks
---------------------

These mixins ensure that segmentation logic sees all relevant texture binds
and can derive fallback colours where needed. They do **not** push uniforms
directly; they only update pending state and debug counters.

- **`TextureManagerMixin`**
  - Target: `net.minecraft.client.texture.TextureManager`
  - Behaviour:
    - At `bindTextureInner(Identifier) @TAIL`:
      - Calls `TextureHelper.onTextureBound(id)` so segmentation can see the
        texture identifier.
    - At `registerTexture(Identifier, AbstractTexture) @TAIL`:
      - Calls `texture.getGlId()` and
        `TextureHelper.registerTextureGlId(id, glId)` to populate a
        `glId → Identifier` map for later GL-id-only binds.

- **`RenderSystemTextureMixin`**
  - Target: `com.mojang.blaze3d.systems.RenderSystem`
  - Behaviour:
    - At `setShaderTexture(int, Identifier) @HEAD`:
      - Calls `TextureHelper.onTextureBound(id)` for identifier-based binds.

- **`RenderSystemTextureGlMixin`**
  - Target: `com.mojang.blaze3d.systems.RenderSystem`
  - Behaviour:
    - At `setShaderTexture(int, int glId) @HEAD`:
      - Calls `TextureHelper.onTextureBoundGlId(glId)`, which uses the
        stored GL-id mapping to recover the texture `Identifier` (if any)
        before delegating to `onTextureBound(id)`.

- **`GlStateManagerBindTextureMixin`**
  - Target: `com.mojang.blaze3d.platform.GlStateManager`
  - Behaviour:
    - At `_bindTexture(int glId) @HEAD`:
      - Calls `TextureHelper.onTextureBoundGlId(glId)` so binds that go
        through `AbstractTexture.bindTexture()` are also visible.

Draw-time uniform updates
-------------------------

The actual upload of segmentation uniforms happens *once per draw* in these
mixins, not per texture bind.

- **`RenderSystemDrawMixin`**
  - Target: `com.mojang.blaze3d.systems.RenderSystem`
  - Behaviour:
    - At `drawElements(int mode, int count, int type) @HEAD`:
      - If segmentation is active:
        - Calls `TextureHelper.recordSegDraw(true)` for stats.
        - If there is a `currentEntity`, ensures the pending colour is that
          entity's colour.
        - Otherwise, sets atlas/block fallback pending colour via
          `TextureHelper.setPendingForBlockAtlas()` while allowing entity
          fallbacks to be resolved from `lastBoundTexture` in
          `applyPendingColourToProgram`.
        - Gets the current shader (`RenderSystem.getShader()`).
        - Calls `TextureHelper.applyPendingColourToProgram(program)` to
          resolve the final `pendingR/G/B` and any entity/block fallbacks.
        - Writes:
          - `entityColourR/G/B`,
          - `debugMode`,
          - `respectAlpha`,
          - `atlasGrid`,
          - `atlasLod`
          uniforms on the active program.

- **`GlStateManagerDrawMixin`**
  - Target: `com.mojang.blaze3d.platform.GlStateManager`
  - Behaviour:
    - At `_drawElements(...) @HEAD`:
      - If segmentation is active:
        - Calls `TextureHelper.recordSegDraw(false)` for stats.
        - Gets the current shader from `RenderSystem.getShader()`.
        - Reads the current pending colour via
          `TextureHelper.getPendingColourRGB()`.
        - Writes the same set of uniforms as `RenderSystemDrawMixin`
          (`entityColourR/G/B`, `debugMode`, `respectAlpha`, `atlasGrid`,
          `atlasLod`).

Chunk offset tracking
---------------------

- **`GlUniformChunkOffsetMixin`**
  - Target: `net.minecraft.client.gl.GlUniform`
  - Behaviour:
    - Injects into `GlUniform.set(float, float, float)` at `HEAD`.
    - When the uniform name is `"ChunkOffset"`, calls:
      - `TextureHelper.updateChunkOffset(x, y, z)`,
      - which stores the last `ChunkOffset` (chunk/world offset) and, when
        segmentation is active, logs it at trace level.
  - The annotate vertex shaders declare `uniform vec3 ChunkOffset` and add
    it to `Position` to match vanilla's world-space transform.

Sky and debug integration
-------------------------

- **`WorldRendererSkyMixin`**
  - Target: `net.minecraft.client.render.WorldRenderer`
  - Behaviour (not detailed here, but relevant):
    - Provides a simple "blank sky" path so that, during segmentation,
      the sky can be rendered as a solid colour when configured, ensuring
      that sky pixels have a stable, non-textured segmentation value.

Summary of behaviour
--------------------

- A second, segmentation-only render of the world runs each frame into an
  off-screen FBO, coordinated by `WorldRendererColourmapMixin` and
  `TextureHelper`.
- During that pass:
  - World and entity shaders are swapped out for annotate shaders by
    `WorldRendererShaderMixin` and `VertexBufferMixin`.
  - Entity and block context is tracked via
    `EntityRenderDispatcherMixin`, `EntityRendererMixin`, and
    `BlockRenderManagerMixin`.
  - Texture binds are observed by `TextureManagerMixin`,
    `RenderSystemTextureMixin`, `RenderSystemTextureGlMixin`, and
    `GlStateManagerBindTextureMixin` and used to derive fallbacks when no
    explicit entity/block is known.
  - Per-draw uniforms for segmentation (`entityColourR/G/B`, debug flags,
    atlas parameters) are uploaded exactly once per draw in
    `RenderSystemDrawMixin` and `GlStateManagerDrawMixin`.
- The end result is:
  - a segmentation image where blocks and terrain have stable per-type
    colours, and
  - entities (including small mobs like pigs and chickens) render as single,
    stable colours, while the normal Minecraft frame remains visually
    unchanged.

