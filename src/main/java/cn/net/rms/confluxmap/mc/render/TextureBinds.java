package cn.net.rms.confluxmap.mc.render;

import com.mojang.blaze3d.systems.RenderSystem;
//#if MC>=12111
//$$ import com.mojang.blaze3d.textures.AddressMode;
//$$ import com.mojang.blaze3d.textures.FilterMode;
//$$ import com.mojang.blaze3d.textures.GpuTextureView;
//$$ import net.minecraft.client.gl.GpuSampler;
//#elseif MC>=12108
//$$ import com.mojang.blaze3d.textures.GpuTextureView;
//#elseif MC>=12105
//$$ import com.mojang.blaze3d.textures.GpuTexture;
//#endif
//#if MC>=12108
//$$ import net.minecraft.client.texture.TextureSetup;
//#endif
import net.minecraft.client.MinecraftClient;
import net.minecraft.util.Identifier;

/**
 * The texture binding this mod's GUI-space batches sample. Before 1.21.5 that is the live
 * {@code RenderSystem.setShaderTexture} slot; from 1.21.8 on, recorded GUI elements carry
 * their own TextureSetup, so the bound view also has to survive here until the batch is
 * finished - {@link Mesh} reads it back through {@link #guiTextureSetup}.
 */
public final class TextureBinds {
    //#if MC>=12108
    //$$ private static GpuTextureView boundTexture;
    //#if MC>=12111
    //$$ private static GpuSampler boundSampler;
    //#endif
    //#endif

    private TextureBinds() {
    }

    /** Selects the flat position+texture shader and standard alpha blending, for textured GUI quads. */
    public static void beginTexturedQuads() {
        //#if MC<12105
        GuiShapes.useTextureShader();
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        //#endif
    }

    public static void bindTexture(final int glId) {
        //#if MC<12105
        RenderSystem.setShaderTexture(0, glId);
        //#endif
    }

    //#if MC>=12111
    //$$ public static void bindTexture(final GpuTextureView texture) {
    //$$     bindTexture(
    //$$         texture,
    //$$         RenderSystem.getSamplerCache().get(
    //$$             AddressMode.CLAMP_TO_EDGE,
    //$$             AddressMode.CLAMP_TO_EDGE,
    //$$             FilterMode.NEAREST,
    //$$             FilterMode.NEAREST,
    //$$             false
    //$$         )
    //$$     );
    //$$ }
    //$$
    //$$ public static void bindTexture(final GpuTextureView texture, final GpuSampler sampler) {
    //$$     boundTexture = texture;
    //$$     boundSampler = sampler;
    //$$ }
    //$$
    //$$ static GpuTextureView boundTexture() {
    //$$     return boundTexture;
    //$$ }
    //$$
    //$$ static GpuSampler boundSampler() {
    //$$     return boundSampler;
    //$$ }
    //#elseif MC>=12108
    //$$ public static void bindTexture(final GpuTextureView texture) {
    //$$     // Immediate canvas batches read the bind back off RenderSystem; recorded GUI elements
    //$$     // carry their own TextureSetup, so the view has to survive until the batch is finished.
    //$$     boundTexture = texture;
    //$$     RenderSystem.setShaderTexture(0, texture);
    //$$ }
    //#elseif MC>=12105
    //$$ public static void bindTexture(final GpuTexture texture) {
    //$$     RenderSystem.setShaderTexture(0, texture);
    //$$ }
    //#endif

//#if MC>=12108
    //$$ static TextureSetup guiTextureSetup(final boolean textured) {
    //$$     if (!textured || boundTexture == null) {
    //$$         return TextureSetup.empty();
    //$$     }
    //#if MC>=12111
    //$$     return TextureSetup.of(boundTexture, boundSampler);
    //#else
    //$$     return TextureSetup.of(boundTexture);
    //#endif
    //$$ }
//#endif

    /**
     * Binds an already-vanilla-managed texture (player skin, mob texture, etc.) by identifier.
     *
     * <p>Core shaders sample what {@code RenderSystem.setShaderTexture} points at, not the
     * legacy {@code TextureManager} bind - using the latter leaves unit 0 on whatever was
     * drawn last (map tiles), which is exactly the "icons show dark terrain" bug.
     */
    public static void bindTexture(final MinecraftClient client, final Identifier id) {
        //#if MC>=12111
        //$$ final var texture = client.getTextureManager().getTexture(id);
        //$$ bindTexture(texture.getGlTextureView(), texture.getSampler());
        //#elseif MC>=12108
        //$$ bindTexture(client.getTextureManager().getTexture(id).getGlTextureView());
        //#elseif MC>=12105
        //$$ RenderSystem.setShaderTexture(0, client.getTextureManager().getTexture(id).getGlTexture());
        //#else
        RenderSystem.setShaderTexture(0, id);
        //#endif
    }
}
