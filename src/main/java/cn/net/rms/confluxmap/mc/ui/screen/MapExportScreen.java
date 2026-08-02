package cn.net.rms.confluxmap.mc.ui.screen;

import cn.net.rms.confluxmap.ConfluxMapClient;
import cn.net.rms.confluxmap.compat.MinecraftAccess;
import cn.net.rms.confluxmap.compat.Texts;
import cn.net.rms.confluxmap.compat.Widgets;
import cn.net.rms.confluxmap.core.export.MapExportBounds;
import cn.net.rms.confluxmap.core.export.MapExportPathLines;
import cn.net.rms.confluxmap.core.export.MapExportResolution;
import cn.net.rms.confluxmap.core.export.MapExportRequest;
import cn.net.rms.confluxmap.core.export.MapExportService;
import cn.net.rms.confluxmap.core.export.MapExportSizeEstimate;
import cn.net.rms.confluxmap.core.export.MapExportStatus;
import cn.net.rms.confluxmap.mc.ui.GuiDraw;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;

/** Coordinate/resolution form and non-pausing progress screen for one PNG export. */
final class MapExportScreen extends ConfluxScreen {
    private static final Pattern INTEGER = Pattern.compile("-?[0-9]*");
    private static final int FIELD_WIDTH = 92;
    private static final int FIELD_HEIGHT = 20;

    private final FullscreenMapScreen parent;
    private final MapExportService exports;
    private final MapExportDesktopActions desktopActions = MapExportDesktopActions.system();
    private MapExportRequest renderSnapshot;
    private MapExportBounds bounds;
    private MapExportResolution resolution;
    private TextFieldWidget firstXField;
    private TextFieldWidget firstZField;
    private TextFieldWidget secondXField;
    private TextFieldWidget secondZField;
    private ButtonWidget exportButton;
    private ButtonWidget resolutionButton;
    private ButtonWidget drawingsButton;
    private boolean includeDrawings = true;
    private boolean submitted;
    private MapExportStatus.State renderedState;
    private MapExportRequest submittedRequest;
    private java.nio.file.Path clipboardRequestedOutput;

    MapExportScreen(
        final FullscreenMapScreen parent,
        final MapExportBounds initialBounds,
        final MapExportResolution initialResolution
    ) {
        super(Texts.translatable("confluxmap.screen.map_export.title"));
        this.parent = parent;
        this.bounds = initialBounds;
        this.resolution = initialResolution;
        this.exports = ConfluxMapClient.get().mapExportService();
        this.renderSnapshot = parent.createExportRequest(initialBounds, initialResolution);
        this.submitted = exports.status().active();
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    protected void init() {
        rebuild();
    }

    private void rebuild() {
        clearChildren();
        renderedState = exports.status().state();
        if (submitted || exports.status().active()) {
            addStatusControls();
        } else {
            addFormControls();
        }
    }

    private void addFormControls() {
        final int left = width / 2 - FIELD_WIDTH - 6;
        final int right = width / 2 + 6;
        firstXField = numericField(left, 58, bounds.minX());
        firstZField = numericField(right, 58, bounds.minZ());
        secondXField = numericField(left, 94, bounds.maxX());
        secondZField = numericField(right, 94, bounds.maxZ());
        addDrawableChild(firstXField);
        addDrawableChild(firstZField);
        addDrawableChild(secondXField);
        addDrawableChild(secondZField);

        resolutionButton = addDrawableChild(Widgets.button(
            width / 2 - 98, 130, 196, FIELD_HEIGHT,
            resolutionLabel(),
            ignored -> {
                final MapExportResolution[] values = MapExportResolution.values();
                resolution = values[(resolution.ordinal() + 1) % values.length];
                resolutionButton.setMessage(resolutionLabel());
                refreshExportButton();
            }
        ));
        drawingsButton = addDrawableChild(Widgets.button(
            width / 2 - 98, 156, 95, FIELD_HEIGHT,
            drawingsLabel(),
            ignored -> {
                includeDrawings = !includeDrawings;
                drawingsButton.setMessage(drawingsLabel());
            }
        ));
        addDrawableChild(Widgets.button(
            width / 2 + 3, 156, 95, FIELD_HEIGHT,
            Texts.translatable("confluxmap.screen.map_export.select_on_map"),
            ignored -> selectOnMap()
        ));
        exportButton = addDrawableChild(Widgets.button(
            width / 2 - 104, height - 32, 100, FIELD_HEIGHT,
            Texts.translatable("confluxmap.screen.map_export.export"),
            ignored -> submit()
        ));
        addDrawableChild(Widgets.button(
            width / 2 + 4, height - 32, 100, FIELD_HEIGHT,
            Texts.translatable("confluxmap.screen.waypoint.cancel"),
            ignored -> onClose()
        ));
        refreshExportButton();
    }

    private void addStatusControls() {
        final MapExportStatus status = exports.status();
        if (status.active()) {
            addDrawableChild(Widgets.button(
                width / 2 - 50, height - 32, 100, FIELD_HEIGHT,
                Texts.translatable("confluxmap.screen.map_export.cancel"),
                ignored -> exports.cancel()
            ));
            return;
        }
        addDrawableChild(Widgets.button(
            status.state() == MapExportStatus.State.COMPLETED ? width / 2 - 148 : width / 2 - 104,
            height - 32,
            status.state() == MapExportStatus.State.COMPLETED ? 94 : 100,
            FIELD_HEIGHT,
            Texts.translatable("confluxmap.screen.map_export.new_export"),
            ignored -> {
                submitted = false;
                parent.beginExportSelection(this);
            }
        ));
        if (status.state() == MapExportStatus.State.COMPLETED && status.output() != null) {
            addDrawableChild(Widgets.button(
                width / 2 - 47, height - 32, 94, FIELD_HEIGHT,
                Texts.translatable("confluxmap.screen.map_export.open_folder"),
                ignored -> desktopActions.openDirectory(status.output())
            ));
        }
        addDrawableChild(Widgets.button(
            status.state() == MapExportStatus.State.COMPLETED ? width / 2 + 54 : width / 2 + 4,
            height - 32,
            status.state() == MapExportStatus.State.COMPLETED ? 94 : 100,
            FIELD_HEIGHT,
            Texts.translatable("confluxmap.screen.map_export.back_to_map"),
            ignored -> returnToMap()
        ));
    }

    private TextFieldWidget numericField(final int x, final int y, final int value) {
        final TextFieldWidget field = new TextFieldWidget(
            this.textRenderer, x, y, FIELD_WIDTH, FIELD_HEIGHT, Texts.literal("")
        );
        field.setMaxLength(11);
        //#if MC>=260100
        //$$ final String[] lastValid = {Integer.toString(value)};
        //$$ field.setResponder(text -> {
        //$$     if (INTEGER.matcher(text).matches()) {
        //$$         lastValid[0] = text;
        //$$     } else {
        //$$         field.setValue(lastValid[0]);
        //$$     }
        //$$ });
        //#else
        field.setTextPredicate(text -> INTEGER.matcher(text).matches());
        //#endif
        field.setText(Integer.toString(value));
        return field;
    }

    private void selectOnMap() {
        final MapExportBounds parsed = parsedBounds();
        if (parsed != null) {
            bounds = parsed;
        }
        parent.beginExportSelection(this);
    }

    void applySelection(final MapExportBounds selected) {
        bounds = selected;
        renderSnapshot = parent.createExportRequest(selected, resolution);
    }

    private void submit() {
        final MapExportBounds parsed = parsedBounds();
        if (parsed == null) {
            return;
        }
        bounds = parsed;
        MapExportRequest request = renderSnapshot.withSelection(bounds, resolution);
        if (!includeDrawings) {
            request = request.withAnnotations(List.of());
        }
        clipboardRequestedOutput = null;
        desktopActions.resetForExport();
        exports.start(request);
        submittedRequest = request;
        submitted = true;
        rebuild();
    }

    private MapExportBounds parsedBounds() {
        try {
            return MapExportBounds.between(
                Integer.parseInt(firstXField.getText()),
                Integer.parseInt(firstZField.getText()),
                Integer.parseInt(secondXField.getText()),
                Integer.parseInt(secondZField.getText())
            );
        } catch (final NumberFormatException e) {
            return null;
        }
    }

    private void refreshExportButton() {
        if (exportButton == null) {
            return;
        }
        final MapExportBounds parsed = parsedBounds();
        if (parsed == null) {
            exportButton.active = false;
            return;
        }
        try {
            Math.multiplyExact(parsed.pixelCount(resolution), 4L);
            exportButton.active = true;
        } catch (final IllegalArgumentException | ArithmeticException e) {
            exportButton.active = false;
        }
    }

    private net.minecraft.text.Text resolutionLabel() {
        return Texts.translatable(
            "confluxmap.screen.map_export.resolution",
            resolution.blocksPerPixel()
        );
    }

    private net.minecraft.text.Text drawingsLabel() {
        return Texts.translatable(
            "confluxmap.screen.map_export.drawings",
            Texts.translatable(
                includeDrawings ? "confluxmap.value.on" : "confluxmap.value.off"
            ).getString()
        );
    }

    @Override
    public void tick() {
        super.tick();
        if (!submitted) {
            Widgets.tick(firstXField);
            Widgets.tick(firstZField);
            Widgets.tick(secondXField);
            Widgets.tick(secondZField);
            refreshExportButton();
            return;
        }
        final MapExportStatus status = exports.status();
        if (status.state() == MapExportStatus.State.COMPLETED
            && status.output() != null
            && submittedRequest != null
            && !status.output().equals(clipboardRequestedOutput)) {
            clipboardRequestedOutput = status.output();
            desktopActions.copyImage(
                status.output(), submittedRequest.pixelWidth(), submittedRequest.pixelHeight()
            );
        }
        if (renderedState != status.state()) {
            rebuild();
        }
    }

    @Override
    public void onClose() {
        if (exports.status().active()) {
            exports.cancel();
        }
        returnToMap();
    }

    private void returnToMap() {
        MinecraftAccess.setScreen(MinecraftClient.getInstance(), parent);
    }

    @Override
    protected void renderContents(
        final GuiDraw draw,
        final int mouseX,
        final int mouseY,
        final float tickDelta
    ) {
        draw.renderBackground(this, mouseX, mouseY, tickDelta);
        drawCentered(draw, getTitle().getString(), 24, 0xFFFFFFFF);
        if (!submitted) {
            drawCentered(draw, Texts.translatable("confluxmap.screen.map_export.first_corner").getString(), 44, 0xFFBBBBBB);
            drawCentered(draw, Texts.translatable("confluxmap.screen.map_export.second_corner").getString(), 80, 0xFFBBBBBB);
            final MapExportBounds parsed = parsedBounds();
            if (parsed != null) {
                try {
                    final int pixelWidth = parsed.pixelWidth(resolution);
                    final int pixelHeight = parsed.pixelHeight(resolution);
                    final String size = String.format(
                        Locale.ROOT,
                        "%d × %d px",
                        pixelWidth,
                        pixelHeight
                    );
                    drawCentered(draw, size, 184, 0xFFB8B8B8);
                    final String estimate = MapExportSizeEstimate.formatBytes(
                        MapExportSizeEstimate.estimatedMaximumPngBytes(
                            pixelWidth, pixelHeight
                        )
                    );
                    drawCentered(
                        draw,
                        Texts.translatable(
                            "confluxmap.screen.map_export.estimated_size", estimate
                        ).getString(),
                        198,
                        0xFFB8B8B8
                    );
                } catch (final IllegalArgumentException | ArithmeticException ignored) {
                    drawCentered(draw, Texts.translatable("confluxmap.screen.map_export.invalid_size").getString(), 184, 0xFFFF7777);
                }
            }
            return;
        }
        drawStatus(draw, exports.status());
    }

    private void drawStatus(final GuiDraw draw, final MapExportStatus status) {
        final String state = Texts.translatable(
            "confluxmap.screen.map_export.state." + status.state().name().toLowerCase(Locale.ROOT)
        ).getString();
        drawCentered(draw, state, 66, status.state() == MapExportStatus.State.FAILED ? 0xFFFF7777 : 0xFFFFFFFF);
        if (status.total() > 0L && status.active()) {
            final long percent = Math.min(100L, status.completed() * 100L / status.total());
            drawCentered(draw, percent + "%", 86, 0xFFB8B8B8);
        }
        if (status.output() != null) {
            int lineY = 108;
            for (final String line : MapExportPathLines.wrap(
                status.output().toString(), Math.max(40, width - 32), this::textWidth
            )) {
                drawCentered(draw, line, lineY, 0xFFB8B8B8);
                lineY += 11;
            }
        }
        if (status.error() != null) {
            drawCentered(draw, fitToWidth(
                status.error(), Math.max(40, width - 32)
            ), 108, 0xFFFF7777);
        }
        drawDesktopStatus(draw);
    }

    private void drawDesktopStatus(final GuiDraw draw) {
        final String key = switch (desktopActions.copyState()) {
            case COPYING -> "confluxmap.screen.map_export.clipboard.copying";
            case COPIED -> "confluxmap.screen.map_export.clipboard.copied";
            case SKIPPED -> "confluxmap.screen.map_export.clipboard.skipped";
            case FAILED -> "confluxmap.screen.map_export.clipboard.failed";
            default -> null;
        };
        if (key != null) {
            drawCentered(draw, Texts.translatable(key).getString(), 152,
                desktopActions.copyState() == MapExportDesktopActions.CopyState.COPIED
                    ? 0xFF77DD88 : 0xFFFFAA66);
        }
        if (desktopActions.openState() == MapExportDesktopActions.OpenState.FAILED) {
            drawCentered(
                draw,
                fitToWidth(
                    Texts.translatable("confluxmap.screen.map_export.open_folder_failed").getString()
                        + ": " + desktopActions.openError(),
                    Math.max(40, width - 32)
                ),
                166,
                0xFFFF7777
            );
        }
    }

    private String fitToWidth(final String text, final int maxWidth) {
        //#if MC>=260100
        //$$ return this.font.plainSubstrByWidth(text, maxWidth);
        //#else
        return this.textRenderer.trimToWidth(text, maxWidth);
        //#endif
    }

    private int textWidth(final String text) {
        //#if MC>=260100
        //$$ return this.font.width(text);
        //#else
        return this.textRenderer.getWidth(text);
        //#endif
    }

    private void drawCentered(final GuiDraw draw, final String text, final int y, final int color) {
        draw.drawTextWithShadow(
            this.textRenderer,
            text,
            width / 2f - this.textRenderer.getWidth(text) / 2f,
            y,
            color
        );
    }
}
