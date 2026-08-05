package cn.net.rms.confluxmap.mc.ui.screen;

import cn.net.rms.confluxmap.ConfluxMapClient;
import cn.net.rms.confluxmap.compat.MinecraftAccess;
import cn.net.rms.confluxmap.compat.Texts;
import cn.net.rms.confluxmap.compat.Widgets;
import cn.net.rms.confluxmap.core.model.WorldIdentity;
import cn.net.rms.confluxmap.core.multiworld.ClientWorldProfile;
import cn.net.rms.confluxmap.core.waypoint.WaypointService;
import cn.net.rms.confluxmap.mc.predict.ManualSeedService;
import cn.net.rms.confluxmap.mc.ui.GuiDraw;
import cn.net.rms.confluxmap.mc.world.ClientMultiworldService;
import java.util.List;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.option.KeyBinding;

/** Manual fallback and profile manager for client-only proxy world recognition. */
public final class ClientWorldSelectScreen extends ConfluxScreen {
    private static final int LIST_TOP = 54;
    private static final int ROW_HEIGHT = 24;
    private static final int GAP = 3;
    private static final int RENAME_WIDTH = 62;
    private static final int FORGET_WIDTH = 78;

    private final Screen parent;
    private final KeyBinding openMapKey;
    private final boolean openMapAfterSelection;
    private final ClientMultiworldService worlds;
    private final ManualSeedService manualSeedService;
    private final WaypointService waypoints;
    private int scrollOffset;
    private int profileCount;
    private boolean waitingToOpenMap;
    private boolean assigningLegacyWaypoints;
    private String pendingForgetId;
    private String migrationMessage;

    public ClientWorldSelectScreen(
        final Screen parent,
        final KeyBinding openMapKey,
        final boolean openMapAfterSelection
    ) {
        super(Texts.translatable("confluxmap.screen.client_world.title"));
        final ConfluxMapClient app = ConfluxMapClient.get();
        this.parent = parent;
        this.openMapKey = openMapKey;
        this.openMapAfterSelection = openMapAfterSelection;
        this.worlds = app.clientMultiworldService();
        this.manualSeedService = app.manualSeedService();
        this.waypoints = app.waypointService();
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
        final List<ClientWorldProfile> profiles = worlds.profiles();
        profileCount = profiles.size();
        final int visible = visibleRows();
        scrollOffset = Math.max(0, Math.min(scrollOffset, Math.max(0, profiles.size() - visible)));
        final int rowWidth = Math.min(440, Math.max(250, width - 24));
        final int rowX = width / 2 - rowWidth / 2;
        final int selectWidth = rowWidth - RENAME_WIDTH - FORGET_WIDTH - GAP * 2;
        final String currentId = worlds.currentProfile().map(ClientWorldProfile::id).orElse(null);
        final int end = Math.min(profiles.size(), scrollOffset + visible);
        for (int index = scrollOffset; index < end; index++) {
            final ClientWorldProfile profile = profiles.get(index);
            final int y = LIST_TOP + (index - scrollOffset) * ROW_HEIGHT;
            if (assigningLegacyWaypoints) {
                final WorldIdentity target = worlds.worldIdentity(profile);
                final ButtonWidget assign = addDrawableChild(Widgets.button(
                    rowX, y, rowWidth, 20,
                    Texts.translatable(
                        "confluxmap.screen.client_world.migrate_target", profile.displayName()
                    ),
                    ignored -> assignLegacyWaypoints(profile)
                ));
                assign.active = !"world".equals(target.worldId());
                continue;
            }
            final String prefix = profile.id().equals(currentId) ? "✓ " : "";
            addDrawableChild(Widgets.button(
                rowX, y, selectWidth, 20,
                Texts.literal(prefix + profile.displayName()),
                ignored -> select(profile.id())
            ));
            addDrawableChild(Widgets.button(
                rowX + selectWidth + GAP, y, RENAME_WIDTH, 20,
                Texts.translatable("confluxmap.screen.client_world.rename"),
                ignored -> openNameEditor(profile)
            ));
            final ButtonWidget forget = addDrawableChild(Widgets.button(
                rowX + selectWidth + GAP + RENAME_WIDTH + GAP, y, FORGET_WIDTH, 20,
                Texts.translatable(
                    profile.id().equals(pendingForgetId)
                        ? "confluxmap.screen.client_world.confirm_forget"
                        : "confluxmap.screen.client_world.forget"
                ),
                ignored -> forget(profile.id())
            ));
            forget.active = profile.bindingCount() > 0;
        }

        final int footerWidth = Math.min(440, rowWidth);
        final int footerX = width / 2 - footerWidth / 2;
        if (assigningLegacyWaypoints) {
            addDrawableChild(Widgets.button(
                footerX + footerWidth / 2 - 70,
                height - 28,
                140,
                20,
                Texts.translatable("confluxmap.screen.client_world.migrate_cancel"),
                ignored -> {
                    assigningLegacyWaypoints = false;
                    migrationMessage = null;
                    rebuild();
                }
            ));
            return;
        }

        final boolean canMigrate = canMigrateLegacyWaypoints(profiles);
        final int footerButtonCount = canMigrate ? 4 : 3;
        final int footerButtonWidth = (footerWidth - GAP * (footerButtonCount - 1))
            / footerButtonCount;
        int footerIndex = 0;
        addDrawableChild(Widgets.button(
            footerX + footerIndex++ * (footerButtonWidth + GAP),
            height - 28,
            footerButtonWidth,
            20,
            Texts.translatable("confluxmap.screen.client_world.create"),
            ignored -> openNameEditor(null)
        ));
        if (canMigrate) {
            addDrawableChild(Widgets.button(
                footerX + footerIndex++ * (footerButtonWidth + GAP),
                height - 28,
                footerButtonWidth,
                20,
                Texts.translatable("confluxmap.screen.client_world.migrate"),
                ignored -> {
                    assigningLegacyWaypoints = true;
                    migrationMessage = null;
                    rebuild();
                }
            ));
        }
        final ButtonWidget seedPreview = addDrawableChild(Widgets.button(
            footerX + footerIndex++ * (footerButtonWidth + GAP),
            height - 28,
            footerButtonWidth,
            20,
            Texts.translatable("confluxmap.screen.client_world.seed_preview"),
            ignored -> MinecraftAccess.setScreen(
                MinecraftClient.getInstance(), new ManualSeedScreen(this)
            )
        ));
        seedPreview.active = currentId != null && manualSeedService.available();
        addDrawableChild(Widgets.button(
            footerX + footerIndex * (footerButtonWidth + GAP),
            height - 28,
            footerWidth - footerButtonWidth * footerIndex - GAP * footerIndex,
            20,
            Texts.translatable("confluxmap.screen.client_world.back"),
            ignored -> onClose()
        ));
    }

    private boolean canMigrateLegacyWaypoints(final List<ClientWorldProfile> profiles) {
        for (final ClientWorldProfile profile : profiles) {
            final WorldIdentity target = worlds.worldIdentity(profile);
            if (!"world".equals(target.worldId())
                && waypoints.hasLegacyMultiplayerWaypoints(target.serverId())) {
                return true;
            }
        }
        return false;
    }

    private void assignLegacyWaypoints(final ClientWorldProfile profile) {
        final WaypointService.LegacyMigrationResult result =
            waypoints.migrateLegacyMultiplayerWaypoints(worlds.worldIdentity(profile));
        if (result.status() == WaypointService.LegacyMigrationStatus.APPLIED) {
            assigningLegacyWaypoints = false;
            migrationMessage = Texts.translatable(
                "confluxmap.screen.client_world.migrate_success",
                result.migratedWaypoints(),
                profile.displayName(),
                result.skippedDuplicates()
            ).getString();
        } else {
            migrationMessage = Texts.translatable(migrationFailureKey(result.status())).getString();
        }
        rebuild();
    }

    private static String migrationFailureKey(
        final WaypointService.LegacyMigrationStatus status
    ) {
        return switch (status) {
            case SOURCE_NOT_FOUND -> "confluxmap.screen.client_world.migrate_source_missing";
            case SOURCE_IS_TARGET -> "confluxmap.screen.client_world.migrate_same_world";
            case SOURCE_READ_ONLY -> "confluxmap.screen.client_world.migrate_source_read_only";
            case TARGET_READ_ONLY -> "confluxmap.screen.client_world.migrate_target_read_only";
            case FAILED -> "confluxmap.screen.client_world.migrate_failed";
            case APPLIED -> throw new IllegalArgumentException("applied migration is not a failure");
        };
    }

    @Override
    public void tick() {
        if (waitingToOpenMap && ConfluxMapClient.get().sessionGuard().current().active()) {
            waitingToOpenMap = false;
            MinecraftAccess.setScreen(MinecraftClient.getInstance(), new FullscreenMapScreen(openMapKey));
        }
    }

    private void select(final String profileId) {
        pendingForgetId = null;
        worlds.select(profileId);
        finishSelection();
    }

    private void forget(final String profileId) {
        if (!profileId.equals(pendingForgetId)) {
            pendingForgetId = profileId;
            rebuild();
            return;
        }
        pendingForgetId = null;
        worlds.clearBindings(profileId);
        rebuild();
    }

    private void openNameEditor(final ClientWorldProfile profile) {
        pendingForgetId = null;
        MinecraftAccess.setScreen(MinecraftClient.getInstance(), new ClientWorldNameScreen(
            this,
            profile == null ? null : profile.displayName(),
            name -> {
                if (profile == null) {
                    worlds.createAndSelect(name);
                    finishSelection();
                } else {
                    worlds.rename(profile.id(), name);
                    rebuild();
                }
            }
        ));
    }

    private void finishSelection() {
        if (parent != null) {
            MinecraftAccess.setScreen(MinecraftClient.getInstance(), parent);
        } else if (openMapAfterSelection) {
            waitingToOpenMap = true;
            rebuild();
        } else {
            MinecraftAccess.setScreen(MinecraftClient.getInstance(), null);
        }
    }

    private int visibleRows() {
        return Math.max(1, (height - LIST_TOP - 38) / ROW_HEIGHT);
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
        final int rowWidth = Math.min(440, Math.max(250, width - 24));
        final boolean overList = mouseX >= width / 2 - rowWidth / 2
            && mouseX <= width / 2 + rowWidth / 2 + 6
            && mouseY >= LIST_TOP && mouseY <= LIST_TOP + visibleRows() * ROW_HEIGHT;
        if (amount != 0 && overList && profileCount > visibleRows()) {
            scrollOffset -= (int) Math.signum(amount);
            rebuild();
            return true;
        }
        //#if MC>=12002
        //$$ return super.mouseScrolled(mouseX, mouseY, horizontalAmount, amount);
        //#else
        return super.mouseScrolled(mouseX, mouseY, amount);
        //#endif
    }

    @Override
    public void onClose() {
        MinecraftAccess.setScreen(MinecraftClient.getInstance(), parent);
    }

    @Override
    protected void renderContents(final GuiDraw draw, final int mouseX, final int mouseY, final float tickDelta) {
        draw.renderBackground(this, mouseX, mouseY, tickDelta);
        drawCentered(draw, getTitle().getString(), 14, 0xFFFFFFFF);
        final String prompt;
        final int promptColor;
        if (migrationMessage != null) {
            prompt = migrationMessage;
            promptColor = assigningLegacyWaypoints ? 0xFFFF7777 : 0xFF77FF77;
        } else if (assigningLegacyWaypoints) {
            prompt = Texts.translatable("confluxmap.screen.client_world.migrate_prompt").getString();
            promptColor = 0xFFFFCC55;
        } else {
            final String promptKey = worlds.needsSelection()
                ? "confluxmap.screen.client_world.ambiguous"
                : "confluxmap.screen.client_world.prompt";
            prompt = Texts.translatable(promptKey).getString();
            promptColor = worlds.needsSelection() ? 0xFFFFCC55 : 0xFFBBBBBB;
        }
        drawCentered(draw, prompt, 34, promptColor);
        if (profileCount == 0 && !assigningLegacyWaypoints) {
            drawCentered(draw, Texts.translatable("confluxmap.screen.client_world.empty").getString(), 70, 0xFFBBBBBB);
        }
        final int rowWidth = Math.min(440, Math.max(250, width - 24));
        drawListScrollbar(
            draw,
            width / 2 + rowWidth / 2 + 3,
            LIST_TOP,
            visibleRows() * ROW_HEIGHT - 4,
            profileCount,
            visibleRows(),
            scrollOffset
        );
    }

    private void drawCentered(final GuiDraw draw, final String text, final float y, final int color) {
        draw.drawTextWithShadow(
            this.textRenderer, text, width / 2f - this.textRenderer.getWidth(text) / 2f, y, color
        );
    }
}
