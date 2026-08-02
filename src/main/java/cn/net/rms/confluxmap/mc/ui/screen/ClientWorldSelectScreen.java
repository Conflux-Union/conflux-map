package cn.net.rms.confluxmap.mc.ui.screen;

import cn.net.rms.confluxmap.ConfluxMapClient;
import cn.net.rms.confluxmap.compat.MinecraftAccess;
import cn.net.rms.confluxmap.compat.Texts;
import cn.net.rms.confluxmap.compat.Widgets;
import cn.net.rms.confluxmap.core.multiworld.ClientWorldProfile;
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
    private int scrollOffset;
    private int profileCount;
    private boolean waitingToOpenMap;
    private String pendingForgetId;

    public ClientWorldSelectScreen(
        final Screen parent,
        final KeyBinding openMapKey,
        final boolean openMapAfterSelection
    ) {
        super(Texts.translatable("confluxmap.screen.client_world.title"));
        this.parent = parent;
        this.openMapKey = openMapKey;
        this.openMapAfterSelection = openMapAfterSelection;
        this.worlds = ConfluxMapClient.get().clientMultiworldService();
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

        addDrawableChild(Widgets.button(
            width / 2 - 104,
            height - 28,
            100,
            20,
            Texts.translatable("confluxmap.screen.client_world.create"),
            ignored -> openNameEditor(null)
        ));
        addDrawableChild(Widgets.button(
            width / 2 + 4,
            height - 28,
            100,
            20,
            Texts.translatable("confluxmap.screen.client_world.back"),
            ignored -> onClose()
        ));
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
        final String promptKey = worlds.needsSelection()
            ? "confluxmap.screen.client_world.ambiguous"
            : "confluxmap.screen.client_world.prompt";
        drawCentered(draw, Texts.translatable(promptKey).getString(), 34,
            worlds.needsSelection() ? 0xFFFFCC55 : 0xFFBBBBBB);
        if (profileCount == 0) {
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
