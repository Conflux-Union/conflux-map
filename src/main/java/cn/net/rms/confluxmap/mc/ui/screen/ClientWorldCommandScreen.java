package cn.net.rms.confluxmap.mc.ui.screen;

import cn.net.rms.confluxmap.ConfluxMapClient;
import cn.net.rms.confluxmap.compat.MinecraftAccess;
import cn.net.rms.confluxmap.compat.Texts;
import cn.net.rms.confluxmap.compat.Widgets;
import cn.net.rms.confluxmap.core.multiworld.ClientWorldCommand;
import cn.net.rms.confluxmap.core.multiworld.ClientWorldProfile;
import cn.net.rms.confluxmap.core.multiworld.ClientWorldProfileResolver;
import cn.net.rms.confluxmap.mc.ui.GuiDraw;
import cn.net.rms.confluxmap.mc.world.ClientMultiworldService;
import java.util.List;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;

/** Adds, removes, and explicitly confirms rebinding switch commands for one profile. */
final class ClientWorldCommandScreen extends ConfluxScreen {
    private static final int LIST_TOP = 88;
    private static final int ROW_HEIGHT = 24;
    private static final int REMOVE_WIDTH = 72;
    private static final int GAP = 3;

    private final Screen parent;
    private final String profileId;
    private final ClientMultiworldService worlds;
    private TextFieldWidget commandField;
    private int scrollOffset;
    private String pendingCommand;
    private String conflictingProfileName;
    private String draft = "";
    private String error;
    private String persistenceError;

    ClientWorldCommandScreen(final Screen parent, final String profileId) {
        super(Texts.translatable("confluxmap.screen.client_world.commands_title"));
        this.parent = parent;
        this.profileId = profileId;
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
        final ClientWorldProfile profile = worlds.profile(profileId).orElse(null);
        if (profile == null) {
            MinecraftAccess.setScreen(MinecraftClient.getInstance(), parent);
            return;
        }
        final int rowWidth = Math.max(1, Math.min(420, width - 24));
        final int rowX = Math.max(8, width / 2 - rowWidth / 2);
        commandField = new TextFieldWidget(
            this.textRenderer, rowX, 54, rowWidth - REMOVE_WIDTH - GAP, 20,
            Texts.translatable("confluxmap.screen.client_world.command")
        );
        commandField.setMaxLength(256);
        commandField.setText(draft);
        addDrawableChild(commandField);
        setInitialFocus(commandField);
        addDrawableChild(Widgets.button(
            rowX + rowWidth - REMOVE_WIDTH, 54, REMOVE_WIDTH, 20,
            Texts.translatable(pendingCommand == null
                ? "confluxmap.screen.client_world.command_add"
                : "confluxmap.screen.client_world.command_rebind"),
            ignored -> submit()
        ));

        final List<String> commands = profile.switchCommands();
        final int visible = visibleRows();
        scrollOffset = Math.max(0, Math.min(scrollOffset, Math.max(0, commands.size() - visible)));
        final int end = Math.min(commands.size(), scrollOffset + visible);
        for (int index = scrollOffset; index < end; index++) {
            final String command = commands.get(index);
            final int y = LIST_TOP + (index - scrollOffset) * ROW_HEIGHT;
            addDrawableChild(Widgets.button(
                rowX, y, rowWidth - REMOVE_WIDTH - GAP, 20, Texts.literal(command), ignored -> {
                    draft = command;
                    pendingCommand = null;
                    conflictingProfileName = null;
                    error = null;
                    persistenceError = null;
                    rebuild();
                }
            ));
            addDrawableChild(Widgets.button(
                rowX + rowWidth - REMOVE_WIDTH, y, REMOVE_WIDTH, 20,
                Texts.translatable("confluxmap.screen.client_world.command_remove"),
                ignored -> {
                    final ClientWorldProfileResolver.MutationResult result = worlds.removeSwitchCommand(profileId, command);
                    pendingCommand = null;
                    conflictingProfileName = null;
                    error = result.applied() ? null : "confluxmap.screen.client_world.save_failed";
                    persistenceError = result.error();
                    rebuild();
                }
            ));
        }
        addDrawableChild(Widgets.button(
            width / 2 - 50, height - 28, 100, 20,
            Texts.translatable("confluxmap.screen.client_world.back"),
            ignored -> onClose()
        ));
    }

    @Override
    public void tick() {
        Widgets.tick(commandField);
        if (commandField != null) {
            draft = commandField.getText();
        }
    }

    private void submit() {
        final String submitted = commandField.getText();
        try {
            final String normalized = ClientWorldCommand.normalizeConfigured(submitted);
            final boolean rebind = normalized.equals(pendingCommand);
            final ClientWorldProfileResolver.CommandBindingResult result = worlds.addSwitchCommand(
                profileId, normalized, rebind
            );
            if (result.status() == ClientWorldProfileResolver.CommandBindingResult.Status.CONFLICT) {
                pendingCommand = normalized;
                conflictingProfileName = result.profile().displayName();
                error = null;
                persistenceError = null;
            } else if (result.status() == ClientWorldProfileResolver.CommandBindingResult.Status.PERSISTENCE_FAILED) {
                pendingCommand = null;
                conflictingProfileName = null;
                error = "confluxmap.screen.client_world.save_failed";
                persistenceError = result.mutation().error();
            } else {
                draft = "";
                pendingCommand = null;
                conflictingProfileName = null;
                error = null;
                persistenceError = null;
            }
        } catch (final IllegalArgumentException e) {
            pendingCommand = null;
            conflictingProfileName = null;
            error = "confluxmap.screen.client_world.command_invalid";
            persistenceError = null;
        }
        rebuild();
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
        final ClientWorldProfile profile = worlds.profile(profileId).orElse(null);
        final int rowWidth = Math.max(1, Math.min(420, width - 24));
        final int rowX = Math.max(8, width / 2 - rowWidth / 2);
        final boolean withinCommandList = mouseX >= rowX && mouseX <= rowX + rowWidth
            && mouseY >= LIST_TOP && mouseY < LIST_TOP + visibleRows() * ROW_HEIGHT;
        if (profile != null && withinCommandList && amount != 0
            && profile.switchCommands().size() > visibleRows()) {
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
        final ClientWorldProfile profile = worlds.profile(profileId).orElse(null);
        final String title = getTitle().getString();
        draw.drawTextWithShadow(
            this.textRenderer, title, width / 2f - this.textRenderer.getWidth(title) / 2f, 14, 0xFFFFFFFF
        );
        if (profile != null) {
            final String name = profile.displayName();
            draw.drawTextWithShadow(
                this.textRenderer, name, width / 2f - this.textRenderer.getWidth(name) / 2f, 34, 0xFFBBBBBB
            );
        }
        if (pendingCommand != null && conflictingProfileName != null) {
            final String warning = Texts.translatable(
                "confluxmap.screen.client_world.command_rebind_warning", conflictingProfileName
            ).getString();
            draw.drawTextWithShadow(
                this.textRenderer, warning, width / 2f - this.textRenderer.getWidth(warning) / 2f, 78, 0xFFFFCC55
            );
        } else if (error != null) {
            final String message = "confluxmap.screen.client_world.save_failed".equals(error)
                ? Texts.translatable(error, persistenceError == null ? "unknown error" : persistenceError).getString()
                : Texts.translatable(error).getString();
            draw.drawTextWithShadow(
                this.textRenderer, message, width / 2f - this.textRenderer.getWidth(message) / 2f, 78, 0xFFFF7777
            );
        }
    }

    private int visibleRows() {
        return Math.max(1, (height - LIST_TOP - 38) / ROW_HEIGHT);
    }
}
