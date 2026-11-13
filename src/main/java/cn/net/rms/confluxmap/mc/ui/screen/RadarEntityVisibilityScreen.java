package cn.net.rms.confluxmap.mc.ui.screen;

import cn.net.rms.confluxmap.compat.Regs;
import cn.net.rms.confluxmap.compat.Texts;
import cn.net.rms.confluxmap.compat.Widgets;
import cn.net.rms.confluxmap.core.config.ConfigIo;
import cn.net.rms.confluxmap.core.config.ConfluxConfig;
import cn.net.rms.confluxmap.core.radar.RadarCategory;
import cn.net.rms.confluxmap.mc.radar.EntityRadarScanner;
import cn.net.rms.confluxmap.mc.ui.GuiDraw;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.util.Identifier;
import net.minecraft.world.World;
//#if MC>=12002
//$$ import net.minecraft.client.gui.DrawContext;
//#endif

/**
 * Per-entity-type visibility management for the entity radar, in the {@link ConfigScreen}
 * row style: every radar-trackable entity type is one {@link RowButton} under the same
 * {@link RadarCategory} header its instances are filtered under at scan time, and clicking
 * cycles follow-category / always-show / always-hide. Overrides persist in
 * {@link ConfluxConfig#radarEntityTypeVisibility} keyed by registry id; absent keys follow
 * the category toggles. The player type is absent on purpose: {@code radarShowPlayers}
 * already owns it.
 */
public final class RadarEntityVisibilityScreen extends ConfluxScreen {
    private static final int ROW_HEIGHT = ConfigScreen.ROW_HEIGHT;
    private static final int MAX_ROW_WIDTH = 280;
    private static final int MARGIN = 8;
    private static final int SEARCH_Y = 26;
    private static final int SEARCH_HEIGHT = 16;
    private static final int BOTTOM_MARGIN = 30;
    private static final int SECTION_TEXT_COLOR = 0xFFA0A0A0;
    private static final int SECTION_LINE_COLOR = 0xFF3A3A3A;
    private static final int SCROLL_TRACK_COLOR = 0xFF3A3A3A;
    private static final int SCROLL_THUMB_COLOR = 0xFFFFFFFF;

    private final Screen parent;
    private final ConfluxConfig config;
    private final ConfigIo configIo;
    private final List<EntityRow> allEntities = new ArrayList<>();
    private final List<SectionLabel> sectionLabels = new ArrayList<>();

    private int rowWidth = MAX_ROW_WIDTH;
    /** Rows scroll in {@link #ROW_HEIGHT} steps; widgets outside the viewport are not built. */
    private int scrollOffset;
    private int contentHeight;
    private TextFieldWidget searchField;

    private record EntityRow(String id, String displayName, RadarCategory category) {
    }

    public RadarEntityVisibilityScreen(
        final Screen parent,
        final ConfluxConfig config,
        final ConfigIo configIo
    ) {
        super(Texts.translatable("confluxmap.screen.radar_entities.title"));
        this.parent = parent;
        this.config = config;
        this.configIo = configIo;
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    protected void init() {
        if (allEntities.isEmpty()) {
            loadEntityTypes();
        }
        rowWidth = Math.min(MAX_ROW_WIDTH, width - MARGIN * 2);
        searchField = new TextFieldWidget(
            this.textRenderer, rowX(), SEARCH_Y, rowWidth, SEARCH_HEIGHT, Texts.literal("")
        );
        Widgets.setChangedListener(searchField, text -> rebuild());
        addBottomButtons();
        rebuild();
    }

    private void addBottomButtons() {
        final int buttonWidth = Math.min(120, Math.max(1, (width - 6 - 16) / 2));
        final int buttonY = height - 28;
        final int buttonLeft = width / 2 - buttonWidth - 3;
        addDrawableChild(Widgets.button(
            buttonLeft,
            buttonY,
            buttonWidth,
            20,
            Texts.translatable("confluxmap.screen.radar_entities.reset"),
            button -> {
                config.radarEntityTypeVisibility.clear();
                rebuild();
            }
        ));
        addDrawableChild(Widgets.button(
            buttonLeft + buttonWidth + 6,
            buttonY,
            buttonWidth,
            20,
            Texts.translatable("confluxmap.screen.waypoint.done"),
            button -> onClose()
        ));
    }

    private void loadEntityTypes() {
        final World world = this.client != null ? this.client.world : null;
        for (final EntityType<?> type : Regs.entityTypes()) {
            final Identifier id = Regs.entityTypeId(type);
            if (id == null || "minecraft:player".equals(id.toString())) {
                continue;
            }
            final RadarCategory category = rowCategory(type, world);
            if (category == null) {
                continue;
            }
            allEntities.add(new EntityRow(id.toString(), type.getName().getString(), category));
        }
        allEntities.sort(
            Comparator.comparing(EntityRow::displayName, String.CASE_INSENSITIVE_ORDER)
        );
    }

    /**
     * Registry-level bucket for one type's row, or null to omit it. With a world the type's
     * factory is probed ({@link Regs#probeEntityClass}), so rows bucket exactly like live
     * scans and never-trackable decorations drop out; without one — the config screen opens
     * from the title screen via Mod Menu — rows fall back to
     * {@link EntityRadarScanner#classifySpawnGroup}. Null-probe and player-like types are
     * omitted.
     */
    private RadarCategory rowCategory(final EntityType<?> type, final World world) {
        if (world == null) {
            return EntityRadarScanner.classifySpawnGroup(type.getSpawnGroup());
        }
        final Class<? extends Entity> entityClass = Regs.probeEntityClass(type, world);
        if (entityClass == null) {
            return null;
        }
        final RadarCategory category =
            EntityRadarScanner.classifyType(entityClass, type.getSpawnGroup());
        if (category == RadarCategory.PLAYER || !EntityRadarScanner.isRadarCandidate(entityClass)) {
            return null;
        }
        return category;
    }

    private void rebuild() {
        clearChildren();
        addDrawableChild(searchField);
        addBottomButtons();
        sectionLabels.clear();

        final String query = Widgets.text(searchField).trim().toLowerCase(Locale.ROOT);
        contentHeight = rowsTop();
        int y = contentHeight - scrollOffset;
        if (query.isEmpty()) {
            for (final RadarCategory category : new RadarCategory[] {
                RadarCategory.HOSTILE, RadarCategory.PASSIVE, RadarCategory.OTHER
            }) {
                if (hasCategory(category)) {
                    if (rowVisible(y)) {
                        sectionLabels.add(new SectionLabel(y, headerKey(category)));
                    }
                    y += ROW_HEIGHT;
                }
                for (final EntityRow entity : allEntities) {
                    if (entity.category() == category) {
                        y = addEntityRow(y, entity);
                    }
                }
            }
        } else {
            for (final EntityRow entity : allEntities) {
                if (entity.displayName().toLowerCase(Locale.ROOT).contains(query)
                    || entity.id().contains(query)) {
                    y = addEntityRow(y, entity);
                }
            }
        }
        contentHeight = y + scrollOffset;
        clampScroll();
    }

    private boolean hasCategory(final RadarCategory category) {
        for (final EntityRow entity : allEntities) {
            if (entity.category() == category) {
                return true;
            }
        }
        return false;
    }

    private int addEntityRow(final int y, final EntityRow entity) {
        if (rowVisible(y)) {
            addDrawableChild(new RowButton(
                rowX(), y, rowWidth, ROW_HEIGHT - 2,
                rowText(entity),
                ignored -> cycleVisibility(entity)
            ));
        }
        return y + ROW_HEIGHT;
    }

    private net.minecraft.text.Text rowText(final EntityRow entity) {
        final String state = Texts.translatable(stateKey(entity.id())).getString();
        return Texts.literal(entity.displayName() + ": " + state);
    }

    private void cycleVisibility(final EntityRow entity) {
        final Boolean current = config.radarEntityTypeVisibility.get(entity.id());
        if (current == null) {
            config.radarEntityTypeVisibility.put(entity.id(), true);
        } else if (current) {
            config.radarEntityTypeVisibility.put(entity.id(), false);
        } else {
            config.radarEntityTypeVisibility.remove(entity.id());
        }
        rebuild();
    }

    private String stateKey(final String id) {
        final Boolean override = config.radarEntityTypeVisibility.get(id);
        if (override == null) {
            return "confluxmap.screen.radar_entities.state.follow";
        }
        return override
            ? "confluxmap.screen.radar_entities.state.show"
            : "confluxmap.screen.radar_entities.state.hide";
    }

    private static String headerKey(final RadarCategory category) {
        switch (category) {
            case HOSTILE:
                return "confluxmap.screen.radar_entities.group.hostile";
            case PASSIVE:
                return "confluxmap.screen.radar_entities.group.passive";
            default:
                return "confluxmap.screen.radar_entities.group.other";
        }
    }

    @Override
    public void tick() {
        Widgets.tick(searchField);
    }

    @Override
    public void onClose() {
        configIo.save(config);
        requestClose(parent);
    }

    private int rowsTop() {
        return SEARCH_Y + SEARCH_HEIGHT + 8;
    }

    private int rowX() {
        return width / 2 - rowWidth / 2;
    }

    private int viewportHeight() {
        return Math.max(ROW_HEIGHT, height - BOTTOM_MARGIN - rowsTop());
    }

    private boolean rowVisible(final int y) {
        return y >= rowsTop() && y + ROW_HEIGHT - 2 <= height - BOTTOM_MARGIN;
    }

    private void clampScroll() {
        final int maxScroll = Math.max(0, contentHeight - viewportHeight());
        scrollOffset = Math.max(0, Math.min(maxScroll, scrollOffset));
    }

    @Override
    //#if MC>=12002
    //$$ public boolean mouseScrolled(
    //$$     final double mouseX,
    //$$     final double mouseY,
    //$$     final double horizontalAmount,
    //$$     final double amount
    //$$ ) {
    //#else
    public boolean mouseScrolled(final double mouseX, final double mouseY, final double amount) {
    //#endif
        // Scrolling rebuilds the rows and would destroy the search field mid-edit.
        if (getFocused() instanceof TextFieldWidget) {
            return false;
        }
        final boolean overRows = mouseX >= rowX() && mouseX <= rowX() + rowWidth + 8
            && mouseY >= rowsTop() && mouseY <= height - BOTTOM_MARGIN;
        if (amount != 0 && overRows) {
            final int maxScroll = Math.max(0, contentHeight - viewportHeight());
            final int next = Math.max(
                0,
                Math.min(maxScroll, scrollOffset - (int) Math.signum(amount) * ROW_HEIGHT)
            );
            if (next != scrollOffset) {
                scrollOffset = next;
                rebuild();
            }
            return true;
        }
        //#if MC>=12002
        //$$ return super.mouseScrolled(mouseX, mouseY, horizontalAmount, amount);
        //#else
        return super.mouseScrolled(mouseX, mouseY, amount);
        //#endif
    }

    @Override
    protected void renderContents(
        final GuiDraw draw,
        final int mouseX,
        final int mouseY,
        final float tickDelta
    ) {
        draw.renderBackground(this, mouseX, mouseY, tickDelta);
        final String title = getTitle().getString();
        draw.drawTextWithShadow(
            this.textRenderer, title, width / 2f - this.textRenderer.getWidth(title) / 2f,
            8, 0xFFFFFFFF
        );
        for (final SectionLabel section : sectionLabels) {
            draw.drawTextWithShadow(
                this.textRenderer,
                Texts.translatable(section.key()),
                rowX(),
                section.y() + 1,
                SECTION_TEXT_COLOR
            );
            final int lineY = section.y() + 1 + this.textRenderer.fontHeight + 2;
            draw.fill(
                rowX(), lineY, rowX() + rowWidth, lineY + 1, SECTION_LINE_COLOR
            );
        }
        drawScrollbar(draw);
    }

    private void drawScrollbar(final GuiDraw draw) {
        final int viewport = viewportHeight();
        final int maxScroll = Math.max(0, contentHeight - viewport);
        if (maxScroll <= 0) {
            return;
        }
        final int top = rowsTop();
        final int trackHeight = viewport;
        final int thumbHeight = Math.max(12, trackHeight * viewport / contentHeight);
        final int thumbTravel = Math.max(1, trackHeight - thumbHeight);
        final int thumbY = top + (int) Math.round(thumbTravel * (scrollOffset / (double) maxScroll));
        final int x = rowX() + rowWidth + 4;
        draw.fill(x, top, x + 2, top + trackHeight, SCROLL_TRACK_COLOR);
        draw.fill(x, thumbY, x + 2, thumbY + thumbHeight, SCROLL_THUMB_COLOR);
    }

    private record SectionLabel(int y, String key) {
    }
}
