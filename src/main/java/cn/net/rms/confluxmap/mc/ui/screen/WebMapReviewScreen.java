package cn.net.rms.confluxmap.mc.ui.screen;

import cn.net.rms.confluxmap.compat.Texts;
import cn.net.rms.confluxmap.compat.Widgets;
import cn.net.rms.confluxmap.core.waypoint.library.WaypointMediaService;
import cn.net.rms.confluxmap.mc.net.WebMapClient;
import cn.net.rms.confluxmap.mc.ui.GuiDraw;
import com.google.gson.JsonObject;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.Base64;
import java.util.UUID;
import javax.imageio.ImageIO;
import net.minecraft.client.gui.screen.Screen;

/** The server supplies a private thumbnail of exactly the pending web copy. */
final class WebMapReviewScreen extends ConfluxScreen {
    private final Screen parent;
    private JsonObject previous;
    private WaypointPhotoTexture texture;
    private String description = "";
    private int index;

    WebMapReviewScreen(Screen parent) {
        super(Texts.translatable("confluxmap.webmap.review"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        previous = WebMapClient.review();
        description = Texts.translatable("confluxmap.webmap.review_loading").getString();
        controls(null, 0);
        WebMapClient.requestReview(index);
    }

    private void controls(UUID image, int count) {
        clearChildren();
        int center = width / 2;
        int y = height - 53;
        addDrawableChild(Widgets.button(center - 114, y, 54, 20, Texts.literal("<"),
            button -> WebMapClient.requestReview(index - 1))).active = count > 1;
        addDrawableChild(Widgets.button(center + 60, y, 54, 20, Texts.literal(">"),
            button -> WebMapClient.requestReview(index + 1))).active = count > 1;
        addDrawableChild(Widgets.button(center - 56, y, 54, 20,
            Texts.translatable("confluxmap.webmap.review_approve"), button -> resolve(image, true))).active = image != null && texture != null;
        addDrawableChild(Widgets.button(center + 2, y, 54, 20,
            Texts.translatable("confluxmap.webmap.review_reject"), button -> resolve(image, false))).active = image != null;
        addDrawableChild(Widgets.button(center - 50, height - 27, 100, 20,
            Texts.translatable("confluxmap.screen.waypoint.done"), button -> onClose()));
    }

    private void resolve(UUID image, boolean approve) {
        if (image == null || !WebMapClient.flag("review")) return;
        WebMapClient.moderate(image, approve);
        WebMapClient.requestReview(index);
    }

    @Override
    public void tick() {
        super.tick();
        if (!WebMapClient.flag("review")) {
            onClose();
            return;
        }
        JsonObject response = WebMapClient.review();
        if (previous == response) return;
        previous = response;
        closePreview();
        int count = response.has("count") ? response.get("count").getAsInt() : 0;
        if (count == 0) {
            description = Texts.translatable("confluxmap.webmap.review_empty").getString();
            controls(null, 0);
            return;
        }
        index = response.get("index").getAsInt();
        UUID waypoint = UUID.fromString(response.get("waypoint").getAsString());
        var row = cn.net.rms.confluxmap.ConfluxMapClient.get().sharedWaypointListModel().rowById(waypoint);
        String name = row == null ? waypoint.toString().substring(0, 8) : row.name();
        description = Texts.translatable("confluxmap.webmap.review_author", index + 1, count,
            response.get("author").getAsString()).getString() + " · " + name;
        try {
            byte[] bytes = Base64.getDecoder().decode(response.get("preview").getAsString());
            var image = readThumbnail(bytes);
            int w = image.getWidth(), h = image.getHeight();
            texture = new WaypointPhotoTexture(new WaypointMediaService.Pixels(
                w, h, image.getRGB(0, 0, w, h, null, 0, w)
            ));
        } catch (IOException | IllegalArgumentException error) {
            description = Texts.translatable("confluxmap.webmap.photo_failed").getString();
        }
        controls(UUID.fromString(response.get("id").getAsString()), count);
    }

    @Override
    protected void renderContents(GuiDraw draw, int mouseX, int mouseY, float delta) {
        draw.renderBackground(this, mouseX, mouseY, delta);
        String text = this.textRenderer.trimToWidth(description, Math.max(0, width - 24));
        draw.drawTextWithShadow(this.textRenderer, text, (width - this.textRenderer.getWidth(text)) / 2,
            10, 0xFFFFFFFF);
        if (texture != null) texture.draw(draw, 12, 27, width - 24, Math.max(1, height - 91), 1);
    }

    private static java.awt.image.BufferedImage readThumbnail(byte[] bytes) throws IOException {
        try (var input = new javax.imageio.stream.MemoryCacheImageInputStream(new ByteArrayInputStream(bytes))) {
            var readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext()) throw new IOException("Invalid review preview");
            var reader = readers.next();
            try {
                reader.setInput(input, true, true);
                int w = reader.getWidth(0), h = reader.getHeight(0);
                if (w < 1 || h < 1 || w > 128 || h > 128) throw new IOException("Invalid preview dimensions");
                return reader.read(0);
            } finally {
                reader.dispose();
            }
        }
    }

    private void closePreview() {
        if (texture != null) {
            texture.close();
            texture = null;
        }
    }

    @Override public boolean isPauseScreen() { return false; }

    @Override public void onClose() { requestClose(parent); }

    @Override public void removed() {
        closePreview();
        super.removed();
    }
}
