package io.github.wintermarkinteractive.bounties;

import io.papermc.paper.dialog.Dialog;
import io.papermc.paper.registry.data.dialog.ActionButton;
import io.papermc.paper.registry.data.dialog.DialogBase;
import io.papermc.paper.registry.data.dialog.action.DialogAction;
import io.papermc.paper.registry.data.dialog.body.DialogBody;
import io.papermc.paper.registry.data.dialog.input.DialogInput;
import io.papermc.paper.registry.data.dialog.type.DialogType;
import net.kyori.adventure.text.event.ClickCallback;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.UUID;

import static io.github.wintermarkinteractive.bounties.Messages.text;

/** Native text-input dialog: "How much do you want to add?" */
public final class AmountDialog {

    private static final MiniMessage MM = MiniMessage.miniMessage();

    private AmountDialog() { }

    public static void open(BountiesPlugin plugin, Player player, UUID target, String targetName) {
        Bounty b = plugin.bounties().get(target);
        BigDecimal current = b == null ? BigDecimal.ZERO : b.total();
        TagResolver tags = TagResolver.resolver(
                text("target", targetName),
                text("total", plugin.economy().format(current)),
                text("balance", plugin.economy().format(plugin.economy().balance(player.getUniqueId()))),
                text("tax", BountyMenu.trim(plugin.bounties().taxPercent())));

        String cfg = "dialog.";
        String body = plugin.getConfig().getString(cfg + "body", "Current: <total>");
        String extra = plugin.getConfig().getString(cfg + "body-extra", "");
        if (extra != null && !extra.isEmpty()) body = body + "<newline>" + extra;
        final String bodyText = body;
        Dialog dialog = Dialog.create(builder -> builder.empty()
                .base(DialogBase.builder(MM.deserialize(plugin.getConfig().getString(cfg + "title", "Bounty on <target>"), tags))
                        .canCloseWithEscape(true)
                        .body(List.of(DialogBody.plainMessage(
                                MM.deserialize(bodyText, tags), 250)))
                        .inputs(List.of(DialogInput.text("amount",
                                        MM.deserialize(plugin.getConfig().getString(cfg + "input-label", "Amount"), tags))
                                .width(200)
                                .maxLength(16)
                                .build()))
                        .build())
                .type(DialogType.confirmation(
                        ActionButton.builder(MM.deserialize(plugin.getConfig().getString(cfg + "confirm", "Place"), tags))
                                .width(100)
                                .action(DialogAction.customClick((view, audience) -> {
                                    String input = view.getText("amount");
                                    Bukkit.getScheduler().runTask(plugin, () -> submit(plugin, player, target, targetName, input));
                                }, ClickCallback.Options.builder().uses(1).lifetime(Duration.ofMinutes(5)).build()))
                                .build(),
                        ActionButton.builder(MM.deserialize(plugin.getConfig().getString(cfg + "cancel", "Cancel"), tags))
                                .width(100)
                                .build())));
        player.showDialog(dialog);
    }

    private static void submit(BountiesPlugin plugin, Player player, UUID target, String targetName, String input) {
        if (!player.isOnline()) return;
        BigDecimal amount = plugin.economy().parse(input);
        if (amount == null) {
            plugin.messages().send(player, "invalid-amount", text("input", input == null ? "" : input));
            return;
        }
        plugin.bounties().place(player, target, targetName, amount);
    }
}
