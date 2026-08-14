package cn.net.rms.confluxmap.mc.ui.screen;

import cn.net.rms.confluxmap.ConfluxMapClient;
import cn.net.rms.confluxmap.compat.MinecraftAccess;
import cn.net.rms.confluxmap.compat.Texts;
import cn.net.rms.confluxmap.compat.Widgets;
import cn.net.rms.confluxmap.core.multiworld.ServerAliasResolver;
import cn.net.rms.confluxmap.mc.ui.GuiDraw;
import cn.net.rms.confluxmap.mc.world.ClientMultiworldService;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;

/**
 * Lists every address that reaches the server whose map data this connection uses, and lets the
 * player add or detach one. Servers running the companion learn their own addresses; this screen
 * is the manual path for the servers that cannot prove which addresses are theirs.
 */
public final class ServerAliasScreen extends ConfluxScreen {
    private static final int LIST_TOP = 62;
    private static final int ROW_HEIGHT = 24;
    private static final int GAP = 3;
    private static final int ACTION_WIDTH = 78;

    private final Screen parent;
    private final ClientMultiworldService worlds;
    private final ServerAliasResolver aliases;
    private final List<Row> rows = new ArrayList<>();
    private String canonicalId;
    private int scrollOffset;
    private String pendingDetachId;
    private String message;
    private boolean messageIsError;

    /** One listed address and whether it is currently linked or detached. */
    private record Row(String addressId, boolean detached) {
    }

    public ServerAliasScreen(final Screen parent) {
        super(Texts.translatable("confluxmap.screen.server_alias.title"));
        final ConfluxMapClient app = ConfluxMapClient.get();
        this.parent = parent;
        this.worlds = app.clientMultiworldService();
        this.aliases = app.serverAliasResolver();
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
        canonicalId = worlds.currentServerId().orElse(null);
        rows.clear();
        if (canonicalId != null) {
            for (final String address : aliases.addresses(canonicalId)) {
                rows.add(new Row(address, false));
            }
            for (final String address : aliases.detachedAddresses(canonicalId)) {
                rows.add(new Row(address, true));
            }
        }

        final int rowWidth = Math.min(440, Math.max(250, width - 24));
        final int rowX = width / 2 - rowWidth / 2;
        final int labelWidth = rowWidth - ACTION_WIDTH - GAP;
        final int visible = visibleRows();
        scrollOffset = Math.max(0, Math.min(scrollOffset, Math.max(0, rows.size() - visible)));
        final int end = Math.min(rows.size(), scrollOffset + visible);
        for (int index = scrollOffset; index < end; index++) {
            final int y = LIST_TOP + (index - scrollOffset) * ROW_HEIGHT;
            final Row row = rows.get(index);
            final boolean isCanonical = row.addressId().equals(canonicalId);
            final ButtonWidget label = addDrawableChild(Widgets.button(
                rowX, y, labelWidth, 20, Texts.literal(rowLabel(row, isCanonical)), ignored -> { }
            ));
            label.active = false;
            final ButtonWidget action = addDrawableChild(Widgets.button(
                rowX + labelWidth + GAP, y, ACTION_WIDTH, 20,
                Texts.translatable(actionKey(row)),
                ignored -> {
                    if (row.detached()) {
                        relink(row.addressId());
                    } else {
                        detach(row.addressId());
                    }
                }
            ));
            // The canonical address names the directory the data lives in, so it always stays.
            action.active = !isCanonical;
        }

        final int footerWidth = Math.min(440, rowWidth);
        final int footerX = width / 2 - footerWidth / 2;
        final int footerButtonWidth = (footerWidth - GAP) / 2;
        final ButtonWidget add = addDrawableChild(Widgets.button(
            footerX, height - 28, footerButtonWidth, 20,
            Texts.translatable("confluxmap.screen.server_alias.add"),
            ignored -> openAddressEditor()
        ));
        add.active = canonicalId != null;
        addDrawableChild(Widgets.button(
            footerX + footerButtonWidth + GAP, height - 28,
            footerWidth - footerButtonWidth - GAP, 20,
            Texts.translatable("confluxmap.screen.server_alias.back"),
            ignored -> onClose()
        ));
    }

    private String rowLabel(final Row row, final boolean isCanonical) {
        if (isCanonical) {
            return Texts.translatable("confluxmap.screen.server_alias.primary").getString()
                + " " + row.addressId();
        }
        if (row.detached()) {
            return Texts.translatable("confluxmap.screen.server_alias.detached").getString()
                + " " + row.addressId();
        }
        return row.addressId();
    }

    private String actionKey(final Row row) {
        if (row.detached()) {
            return "confluxmap.screen.server_alias.relink";
        }
        return row.addressId().equals(pendingDetachId)
            ? "confluxmap.screen.server_alias.confirm_detach"
            : "confluxmap.screen.server_alias.detach";
    }

    private void openAddressEditor() {
        clearMessage();
        MinecraftAccess.setScreen(MinecraftClient.getInstance(), new ServerAliasAddScreen(
            this, this::addAddress
        ));
    }

    /**
     * Adds a typed address after checking what it is already doing. An address that stores map
     * data of its own is refused rather than linked, because linking it would leave that data
     * unreachable with no way to notice.
     */
    private void addAddress(final String rawAddress) {
        if (canonicalId == null) {
            return;
        }
        final ServerAliasResolver.AddressStatus status = aliases.inspect(canonicalId, rawAddress);
        switch (status.state()) {
            case FREE -> {
                aliases.link(canonicalId, rawAddress);
                setMessage(Texts.translatable(
                    "confluxmap.screen.server_alias.added", status.addressId()
                ).getString(), false);
            }
            case LINKED -> setMessage(Texts.translatable(
                "confluxmap.screen.server_alias.already_linked", status.addressId()
            ).getString(), true);
            case TAKEN -> setMessage(Texts.translatable(
                "confluxmap.screen.server_alias.taken", status.addressId(), status.owner()
            ).getString(), true);
            case HOLDS_DATA -> setMessage(Texts.translatable(
                "confluxmap.screen.server_alias.holds_data", status.addressId()
            ).getString(), true);
        }
        rebuild();
    }

    private void detach(final String addressId) {
        if (!addressId.equals(pendingDetachId)) {
            pendingDetachId = addressId;
            setMessage(Texts.translatable(
                "confluxmap.screen.server_alias.detach_warning", addressId
            ).getString(), false);
            rebuild();
            return;
        }
        pendingDetachId = null;
        aliases.unlink(addressId);
        setMessage(Texts.translatable(
            "confluxmap.screen.server_alias.detached_done", addressId
        ).getString(), false);
        rebuild();
    }

    private void relink(final String addressId) {
        pendingDetachId = null;
        final ServerAliasResolver.AddressStatus status = aliases.inspect(canonicalId, addressId);
        if (status.state() == ServerAliasResolver.AddressState.FREE) {
            aliases.link(canonicalId, addressId);
            setMessage(Texts.translatable(
                "confluxmap.screen.server_alias.added", addressId
            ).getString(), false);
        } else {
            setMessage(Texts.translatable(
                "confluxmap.screen.server_alias.holds_data", addressId
            ).getString(), true);
        }
        rebuild();
    }

    private void setMessage(final String text, final boolean error) {
        message = text;
        messageIsError = error;
    }

    private void clearMessage() {
        message = null;
        messageIsError = false;
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
        if (amount != 0 && overList && rows.size() > visibleRows()) {
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
    protected void renderContents(
        final GuiDraw draw,
        final int mouseX,
        final int mouseY,
        final float tickDelta
    ) {
        draw.renderBackground(this, mouseX, mouseY, tickDelta);
        drawCentered(draw, getTitle().getString(), 14, 0xFFFFFFFF);
        final String prompt;
        final int promptColor;
        if (message != null) {
            prompt = message;
            promptColor = messageIsError ? 0xFFFF7777 : 0xFF77FF77;
        } else if (canonicalId == null) {
            prompt = Texts.translatable("confluxmap.screen.server_alias.disconnected").getString();
            promptColor = 0xFFFFCC55;
        } else {
            prompt = Texts.translatable(
                "confluxmap.screen.server_alias.prompt", canonicalId
            ).getString();
            promptColor = 0xFFBBBBBB;
        }
        drawCentered(draw, prompt, 34, promptColor);
        drawCentered(
            draw,
            Texts.translatable("confluxmap.screen.server_alias.hint").getString(),
            46,
            0xFF888888
        );
        final int rowWidth = Math.min(440, Math.max(250, width - 24));
        drawListScrollbar(
            draw,
            width / 2 + rowWidth / 2 + 3,
            LIST_TOP,
            visibleRows() * ROW_HEIGHT - 4,
            rows.size(),
            visibleRows(),
            scrollOffset
        );
    }

    private void drawCentered(
        final GuiDraw draw,
        final String text,
        final float y,
        final int color
    ) {
        draw.drawTextWithShadow(
            this.textRenderer, text, width / 2f - this.textRenderer.getWidth(text) / 2f, y, color
        );
    }
}
