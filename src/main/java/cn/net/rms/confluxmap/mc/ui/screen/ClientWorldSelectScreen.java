package cn.net.rms.confluxmap.mc.ui.screen;

import cn.net.rms.confluxmap.ConfluxMapClient;
import cn.net.rms.confluxmap.compat.MinecraftAccess;
import cn.net.rms.confluxmap.compat.Texts;
import cn.net.rms.confluxmap.compat.Widgets;
import cn.net.rms.confluxmap.core.cache.MapCacheMigration;
import cn.net.rms.confluxmap.core.model.WorldIdentity;
import cn.net.rms.confluxmap.core.multiworld.ClientWorldProfile;
import cn.net.rms.confluxmap.core.waypoint.WaypointService;
import cn.net.rms.confluxmap.mc.predict.ManualSeedService;
import cn.net.rms.confluxmap.mc.ui.GuiDraw;
import cn.net.rms.confluxmap.mc.world.ClientMultiworldService;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
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
    private String selectedMigrationId;
    private boolean migrationConfirm;
    private CompletableFuture<MapCacheMigration.Result> migrationFuture;
    private String migrationMessage;
    private boolean migrationError;

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
        final boolean authoritative = worlds.companionWorldIdentityAuthoritative();
        final int authorityRows = authoritative ? 1 : 0;
        profileCount = profiles.size() + authorityRows;
        final int visible = visibleRows();
        scrollOffset = Math.max(0, Math.min(scrollOffset, Math.max(0, profileCount - visible)));
        final int rowWidth = Math.min(440, Math.max(250, width - 24));
        final int rowX = width / 2 - rowWidth / 2;
        final int selectWidth = rowWidth - RENAME_WIDTH - FORGET_WIDTH - GAP * 2;
        final String currentId = authoritative
            ? null
            : worlds.currentProfile().map(ClientWorldProfile::id).orElse(null);
        final boolean migrationBusy = migrationFuture != null;
        final int end = Math.min(profileCount, scrollOffset + visible);
        for (int index = scrollOffset; index < end; index++) {
            final int y = LIST_TOP + (index - scrollOffset) * ROW_HEIGHT;
            if (authoritative && index == 0) {
                final String worldId = worlds.companionWorldIdentity()
                    .map(WorldIdentity::worldId)
                    .orElse("?");
                final ButtonWidget serverWorld = addDrawableChild(Widgets.button(
                    rowX, y, rowWidth, 20,
                    Texts.literal("✓ [" + Texts.translatable(
                        "confluxmap.screen.client_world.server_world"
                    ).getString() + "] " + worldId),
                    ignored -> { }
                ));
                serverWorld.active = false;
                continue;
            }
            final ClientWorldProfile profile = profiles.get(index - authorityRows);
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
            final ButtonWidget select = addDrawableChild(Widgets.button(
                rowX, y, selectWidth, 20,
                Texts.literal(
                    (profile.id().equals(selectedMigrationId) ? "-> " : "")
                        + prefix + profile.displayName()
                ),
                ignored -> {
                    if (authoritative) {
                        selectMigrationSource(profile.id());
                    } else {
                        select(profile.id());
                    }
                }
            ));
            // The companion UUID controls active map storage. Keep old profiles visible for
            // review and waypoint migration, but do not silently fork the active map session.
            select.active = !migrationBusy;
            final ButtonWidget rename = addDrawableChild(Widgets.button(
                rowX + selectWidth + GAP, y, RENAME_WIDTH, 20,
                Texts.translatable("confluxmap.screen.client_world.rename"),
                ignored -> openNameEditor(profile)
            ));
            rename.active = !migrationBusy;
            final ButtonWidget forget = addDrawableChild(Widgets.button(
                rowX + selectWidth + GAP + RENAME_WIDTH + GAP, y, FORGET_WIDTH, 20,
                Texts.translatable(
                    profile.id().equals(pendingForgetId)
                        ? "confluxmap.screen.client_world.confirm_forget"
                        : "confluxmap.screen.client_world.forget"
                ),
                ignored -> forget(profile.id())
            ));
            forget.active = !migrationBusy && profile.bindingCount() > 0;
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
                    migrationError = false;
                    rebuild();
                }
            ));
            return;
        }

        final boolean canMerge = authoritative && selectedMigrationId != null;
        final boolean canMigrate = !authoritative && canMigrateLegacyWaypoints(profiles);
        final int footerButtonCount = canMerge || canMigrate ? 4 : 3;
        final int footerButtonWidth = (footerWidth - GAP * (footerButtonCount - 1))
            / footerButtonCount;
        int footerIndex = 0;
        final ButtonWidget create = addDrawableChild(Widgets.button(
            footerX + footerIndex++ * (footerButtonWidth + GAP),
            height - 28,
            footerButtonWidth,
            20,
            Texts.translatable("confluxmap.screen.client_world.create"),
            ignored -> openNameEditor(null)
        ));
        create.active = !authoritative && !migrationBusy;
        if (canMerge) {
            final ButtonWidget merge = addDrawableChild(Widgets.button(
                footerX + footerIndex++ * (footerButtonWidth + GAP),
                height - 28,
                footerButtonWidth,
                20,
                Texts.translatable(
                    migrationConfirm
                        ? "confluxmap.screen.client_world.merge_confirm"
                        : "confluxmap.screen.client_world.merge"
                ),
                ignored -> requestMerge()
            ));
            merge.active = !migrationBusy;
        } else if (canMigrate) {
            addDrawableChild(Widgets.button(
                footerX + footerIndex++ * (footerButtonWidth + GAP),
                height - 28,
                footerButtonWidth,
                20,
                Texts.translatable("confluxmap.screen.client_world.migrate"),
                ignored -> {
                    assigningLegacyWaypoints = true;
                    migrationMessage = null;
                    migrationError = false;
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
        // This is also the recovery path when the server has just gained the companion plugin;
        // it must not depend on a pre-existing client profile selection.
        seedPreview.active = manualSeedService.available();
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
            migrationError = false;
            migrationMessage = Texts.translatable(
                "confluxmap.screen.client_world.migrate_success",
                result.migratedWaypoints(),
                profile.displayName(),
                result.skippedDuplicates()
            ).getString();
        } else {
            migrationError = true;
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
        if (migrationFuture != null && migrationFuture.isDone()) {
            final CompletableFuture<MapCacheMigration.Result> completed = migrationFuture;
            migrationFuture = null;
            migrationConfirm = false;
            try {
                final MapCacheMigration.Result result = completed.join();
                if (result.status() == MapCacheMigration.Status.APPLIED) {
                    migrationError = false;
                    migrationMessage = Texts.translatable(
                        "confluxmap.screen.client_world.merge_success",
                        result.migratedChunks(), result.copiedRegions(), result.mergedRegions()
                    ).getString();
                    selectedMigrationId = null;
                } else {
                    migrationError = true;
                    migrationMessage = Texts.translatable(
                        cacheMigrationFailureKey(result.status())
                    ).getString();
                }
            } catch (final CompletionException | IllegalStateException error) {
                migrationError = true;
                migrationMessage = Texts.translatable(
                    "confluxmap.screen.client_world.merge_failed"
                ).getString();
            }
            rebuild();
        }
        if (waitingToOpenMap && ConfluxMapClient.get().sessionGuard().current().active()) {
            waitingToOpenMap = false;
            MinecraftAccess.setScreen(MinecraftClient.getInstance(), new FullscreenMapScreen(openMapKey));
        }
    }

    private void select(final String profileId) {
        pendingForgetId = null;
        migrationMessage = null;
        migrationError = false;
        worlds.select(profileId);
        finishSelection();
    }

    /** Selects an old client profile as a migration source without switching active map storage. */
    private void selectMigrationSource(final String profileId) {
        pendingForgetId = null;
        migrationConfirm = false;
        migrationMessage = null;
        migrationError = false;
        selectedMigrationId = profileId.equals(selectedMigrationId) ? null : profileId;
        if (selectedMigrationId != null) {
            final ClientWorldProfile profile = worlds.profiles().stream()
                .filter(candidate -> candidate.id().equals(selectedMigrationId))
                .findFirst()
                .orElse(null);
            if (profile != null) {
                migrationMessage = Texts.translatable(
                    "confluxmap.screen.client_world.merge_source_selected", profile.displayName()
                ).getString();
            }
        }
        rebuild();
    }

    private void requestMerge() {
        if (selectedMigrationId == null || migrationFuture != null) {
            return;
        }
        if (!migrationConfirm) {
            migrationConfirm = true;
            migrationError = false;
            migrationMessage = Texts.translatable(
                "confluxmap.screen.client_world.merge_warning"
            ).getString();
            rebuild();
            return;
        }

        final ClientMultiworldService.ProfileMigrationPreparation preparation =
            worlds.prepareProfileMigration(selectedMigrationId);
        if (!preparation.ready()) {
            migrationConfirm = false;
            migrationError = true;
            migrationMessage = Texts.translatable(
                profileMigrationFailureKey(preparation.status())
            ).getString();
            rebuild();
            return;
        }

        // End the active session first. RegionCacheService queues its final flush on the same IO
        // executor used by executeProfileMigration, so the merge cannot race dirty map writes.
        ConfluxMapClient.get().sessionTracker().endSession();
        migrationError = false;
        migrationMessage = Texts.translatable(
            "confluxmap.screen.client_world.merge_running"
        ).getString();
        try {
            migrationFuture = worlds.executeProfileMigration(preparation);
        } catch (final RuntimeException error) {
            migrationConfirm = false;
            migrationError = true;
            migrationMessage = Texts.translatable(
                "confluxmap.screen.client_world.merge_failed"
            ).getString();
        }
        rebuild();
    }

    private static String profileMigrationFailureKey(
        final ClientMultiworldService.ProfileMigrationStatus status
    ) {
        return switch (status) {
            case NOT_CONNECTED -> "confluxmap.screen.client_world.merge_not_connected";
            case COMPANION_REQUIRED -> "confluxmap.screen.client_world.merge_server_required";
            case SEED_UNKNOWN -> "confluxmap.screen.client_world.merge_seed_unknown";
            case SEED_MISMATCH -> "confluxmap.screen.client_world.merge_seed_mismatch";
            case SOURCE_IS_TARGET -> "confluxmap.screen.client_world.merge_same_target";
            case ALREADY_RUNNING -> "confluxmap.screen.client_world.merge_running";
            case READY -> throw new IllegalArgumentException("ready migration is not a failure");
        };
    }

    private static String cacheMigrationFailureKey(final MapCacheMigration.Status status) {
        return switch (status) {
            case SOURCE_NOT_FOUND -> "confluxmap.screen.client_world.merge_source_missing";
            case SOURCE_IS_TARGET -> "confluxmap.screen.client_world.merge_same_target";
            case FAILED -> "confluxmap.screen.client_world.merge_failed";
            case APPLIED -> throw new IllegalArgumentException("applied migration is not a failure");
        };
    }

    private void forget(final String profileId) {
        if (!profileId.equals(pendingForgetId)) {
            pendingForgetId = profileId;
            rebuild();
            return;
        }
        pendingForgetId = null;
        migrationMessage = null;
        migrationError = false;
        worlds.clearBindings(profileId);
        rebuild();
    }

    private void openNameEditor(final ClientWorldProfile profile) {
        pendingForgetId = null;
        migrationMessage = null;
        migrationError = false;
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
            promptColor = migrationError ? 0xFFFF7777 : 0xFF77FF77;
        } else if (assigningLegacyWaypoints) {
            prompt = Texts.translatable("confluxmap.screen.client_world.migrate_prompt").getString();
            promptColor = 0xFFFFCC55;
        } else {
            final String promptKey = worlds.companionWorldIdentityAuthoritative()
                ? "confluxmap.screen.client_world.companion"
                : worlds.needsSelection()
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
