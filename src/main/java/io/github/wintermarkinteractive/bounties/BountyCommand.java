package io.github.wintermarkinteractive.bounties;

import io.papermc.paper.command.brigadier.BasicCommand;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;

import static io.github.wintermarkinteractive.bounties.Messages.text;

/**
 * /bounty                         open the menu
 * /bounty set <player> <amount>   place or add to a bounty (aliases: add, place)
 * /bounty check <player>          show a bounty
 * /bounty top [claims|earned]     best bounty hunters
 * Admin: give, remove, forceclaim, reload
 */
public final class BountyCommand implements BasicCommand {

    private static final List<String> PLAYER_SUBS = List.of("set", "check", "top", "help");
    private static final List<String> ADMIN_SUBS = List.of("give", "remove", "forceclaim", "reload");

    private final BountiesPlugin plugin;

    public BountyCommand(BountiesPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public void execute(CommandSourceStack source, String[] args) {
        CommandSender sender = source.getSender();
        Messages msg = plugin.messages();

        if (args.length == 0) {
            if (sender instanceof Player p) BountyMenu.open(plugin, p, 0);
            else msg.send(sender, "help");
            return;
        }
        String sub = args[0].toLowerCase(Locale.ROOT);
        boolean admin = sender.hasPermission("wintermark.bounty.admin");
        switch (sub) {
            case "set", "add", "place" -> set(sender, args);
            case "check", "info" -> check(sender, args);
            case "top" -> top(sender, args);
            case "give" -> { if (admin) give(sender, args); else msg.send(sender, "no-permission"); }
            case "remove" -> { if (admin) remove(sender, args); else msg.send(sender, "no-permission"); }
            case "forceclaim" -> { if (admin) forceClaim(sender, args); else msg.send(sender, "no-permission"); }
            case "reload" -> {
                if (!admin) {
                    msg.send(sender, "no-permission");
                    return;
                }
                plugin.reload();
                plugin.messages().send(sender, "reloaded");
            }
            default -> msg.send(sender, admin ? "help-admin" : "help");
        }
    }

    private void set(CommandSender sender, String[] args) {
        Messages msg = plugin.messages();
        if (!(sender instanceof Player player)) {
            msg.send(sender, "players-only");
            return;
        }
        if (!player.hasPermission("wintermark.bounty.place")) {
            msg.send(sender, "no-permission");
            return;
        }
        if (args.length != 3) {
            msg.send(sender, "usage-set");
            return;
        }
        OfflinePlayer target = BountyListener.findPlayer(args[1]);
        if (target == null) {
            msg.send(sender, "player-not-found", text("player", args[1]));
            return;
        }
        BigDecimal amount = plugin.economy().parse(args[2]);
        if (amount == null) {
            msg.send(sender, "invalid-amount", text("input", args[2]));
            return;
        }
        plugin.bounties().place(player, target.getUniqueId(), name(target, args[1]), amount);
    }

    private void check(CommandSender sender, String[] args) {
        Messages msg = plugin.messages();
        // Without a name, /bounty check shows your own bounty.
        String input = args.length >= 2 ? args[1] : sender.getName();
        OfflinePlayer target;
        if (args.length >= 2) {
            target = BountyListener.findPlayer(args[1]);
        } else if (sender instanceof Player p) {
            target = p;
        } else {
            msg.send(sender, "help");
            return;
        }
        Bounty b = target != null ? plugin.bounties().get(target.getUniqueId())
                : plugin.bounties().findByName(input);
        if (target == null && b == null) {
            msg.send(sender, "player-not-found", text("player", input));
            return;
        }
        String name = target != null ? name(target, input) : b.targetName();
        if (b == null) {
            msg.send(sender, "check-none", text("target", name));
            return;
        }
        msg.send(sender, "check", text("target", b.targetName()),
                text("total", plugin.economy().format(b.total())),
                text("contributors", String.valueOf(b.playerContributors())),
                text("placed_by", plugin.bounties().placedBy(b)));
    }

    /** /bounty top [claims|earned] */
    private void top(CommandSender sender, String[] args) {
        boolean byEarned = args.length >= 2 && args[1].equalsIgnoreCase("earned");
        int size = Math.max(1, Math.min(20, plugin.getConfig().getInt("top.size", 10)));
        plugin.database().topHunters(byEarned, size).thenAccept(rows ->
                Bukkit.getScheduler().runTask(plugin, () -> {
                    Messages msg = plugin.messages();
                    if (rows.isEmpty()) {
                        msg.send(sender, "top-empty");
                        return;
                    }
                    String type = plugin.getConfig().getString(byEarned ? "messages.top-type-earned" : "messages.top-type-claims", "");
                    msg.send(sender, "top-header", text("type", type));
                    for (int i = 0; i < rows.size(); i++) {
                        var r = rows.get(i);
                        msg.send(sender, "top-line", text("rank", String.valueOf(i + 1)), text("player", r.name()),
                                text("claims", String.valueOf(r.claims())), text("earned", plugin.economy().format(r.earned())));
                    }
                }));
    }

    private void give(CommandSender sender, String[] args) {
        Messages msg = plugin.messages();
        if (args.length != 3) {
            msg.send(sender, "help-admin");
            return;
        }
        OfflinePlayer target = BountyListener.findPlayer(args[1]);
        if (target == null && args[1].matches("[A-Za-z0-9_]{3,16}")) {
            // Admins may target players who never joined (looks the name up with Mojang).
            target = Bukkit.getOfflinePlayer(args[1]);
        }
        if (target == null) {
            msg.send(sender, "player-not-found", text("player", args[1]));
            return;
        }
        BigDecimal amount = plugin.economy().parse(args[2]);
        if (amount == null) {
            msg.send(sender, "invalid-amount", text("input", args[2]));
            return;
        }
        Bounty b = plugin.bounties().placeAsServer(target.getUniqueId(), name(target, args[1]), amount);
        msg.send(sender, "admin-given", text("target", b.targetName()),
                text("amount", plugin.economy().format(amount)), text("total", plugin.economy().format(b.total())));
    }

    private void remove(CommandSender sender, String[] args) {
        Messages msg = plugin.messages();
        if (args.length < 2) {
            msg.send(sender, "help-admin");
            return;
        }
        Bounty bounty = bountyFor(args[1]);
        if (bounty == null) {
            msg.send(sender, "check-none", text("target", args[1]));
            return;
        }
        boolean refund = !(args.length >= 3 && args[2].equalsIgnoreCase("norefund"));
        String targetName = bounty.targetName();
        BigDecimal refunded = plugin.bounties().remove(bounty.target(), refund);
        msg.send(sender, refund ? "admin-removed" : "admin-removed-norefund",
                text("target", targetName),
                text("amount", plugin.economy().format(refunded == null ? BigDecimal.ZERO : refunded)));
    }

    /** Pays a bounty to a killer by hand (disputes, or testing on a one-account server). */
    private void forceClaim(CommandSender sender, String[] args) {
        Messages msg = plugin.messages();
        if (args.length != 3) {
            msg.send(sender, "help-admin");
            return;
        }
        Bounty bounty = bountyFor(args[1]);
        if (bounty == null) {
            msg.send(sender, "check-none", text("target", args[1]));
            return;
        }
        Player killer = Bukkit.getPlayerExact(args[2]);
        if (killer == null) {
            msg.send(sender, "killer-offline", text("player", args[2]));
            return;
        }
        ItemStack head = plugin.bounties().claim(bounty.target(), bounty.targetName(), killer);
        if (head != null) {
            for (ItemStack left : killer.getInventory().addItem(head).values()) {
                killer.getWorld().dropItemNaturally(killer.getLocation(), left);
            }
        }
    }

    /** Open bounty for a name: known player first, then stored bounty names. */
    private Bounty bountyFor(String name) {
        OfflinePlayer p = BountyListener.findPlayer(name);
        Bounty b = p == null ? null : plugin.bounties().get(p.getUniqueId());
        return b != null ? b : plugin.bounties().findByName(name);
    }

    private static String name(OfflinePlayer p, String fallback) {
        return p.getName() != null ? p.getName() : fallback;
    }

    @Override
    public Collection<String> suggest(CommandSourceStack source, String[] args) {
        CommandSender sender = source.getSender();
        String last = args.length == 0 ? "" : args[args.length - 1];
        int pos = Math.max(1, args.length);
        if (pos == 1) {
            List<String> subs = new ArrayList<>(PLAYER_SUBS);
            if (sender.hasPermission("wintermark.bounty.admin")) subs.addAll(ADMIN_SUBS);
            return filter(subs, last);
        }
        String sub = args[0].toLowerCase(Locale.ROOT);
        if (pos == 2 && List.of("set", "add", "place", "check", "info", "give", "remove", "forceclaim").contains(sub)) {
            List<String> names = new ArrayList<>();
            if (sub.equals("remove") || sub.equals("forceclaim") || sub.equals("check") || sub.equals("info")) {
                for (Bounty b : plugin.bounties().sorted()) names.add(b.targetName());
            }
            for (Player p : Bukkit.getOnlinePlayers()) if (!names.contains(p.getName())) names.add(p.getName());
            if (sub.equals("set") || sub.equals("add") || sub.equals("place")) names.remove(sender.getName());
            return filter(names, last);
        }
        if (pos == 3 && List.of("set", "add", "place", "give").contains(sub) && last.isEmpty()) {
            return List.of("500", "1k", "10k");
        }
        if (pos == 3 && sub.equals("remove")) return filter(new ArrayList<>(List.of("norefund")), last);
        if (pos == 2 && sub.equals("top")) return filter(new ArrayList<>(List.of("claims", "earned")), last);
        if (pos == 3 && sub.equals("forceclaim")) {
            List<String> names = new ArrayList<>();
            for (Player p : Bukkit.getOnlinePlayers()) names.add(p.getName());
            return filter(names, last);
        }
        return List.of();
    }

    private static List<String> filter(List<String> options, String prefix) {
        String p = prefix.toLowerCase(Locale.ROOT);
        List<String> out = new ArrayList<>();
        for (String o : options) if (o.toLowerCase(Locale.ROOT).startsWith(p)) out.add(o);
        return out;
    }

    @Override
    public String permission() {
        return "wintermark.bounty.use";
    }
}
