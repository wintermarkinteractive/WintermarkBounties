package io.github.wintermarkinteractive.bounties;

import io.papermc.paper.datacomponent.DataComponentTypes;
import io.papermc.paper.datacomponent.item.TooltipDisplay;
import io.github.wintermarkinteractive.bounties.economy.EconomyBridge;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.Sound;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.SkullMeta;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.InetSocketAddress;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static io.github.wintermarkinteractive.bounties.Messages.text;

/** Placing, removing, expiring and claiming bounties, plus auto bounties. Main thread only. */
public final class BountyService {

    private static final MiniMessage MM = MiniMessage.miniMessage();
    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    private final BountiesPlugin plugin;
    private final Map<UUID, Bounty> bounties = new HashMap<>();
    private final Map<UUID, Long> lastPlaced = new HashMap<>();
    private final Map<UUID, Deque<Long>> recentKills = new HashMap<>();

    public BountyService(BountiesPlugin plugin) {
        this.plugin = plugin;
    }

    public void load(Map<UUID, Bounty> loaded) {
        bounties.putAll(loaded);
    }

    public Bounty get(UUID target) { return bounties.get(target); }

    public int count() { return bounties.size(); }

    /** Open bounty whose target has this name (case-insensitive), or null. */
    public Bounty findByName(String name) {
        for (Bounty b : bounties.values()) if (b.targetName().equalsIgnoreCase(name)) return b;
        return null;
    }

    /** Biggest bounty first. */
    public List<Bounty> sorted() {
        List<Bounty> list = new ArrayList<>(bounties.values());
        list.sort(Comparator.comparing(Bounty::total, Comparator.reverseOrder())
                .thenComparing(b -> b.targetName().toLowerCase(java.util.Locale.ROOT)));
        return list;
    }

    private EconomyBridge eco() { return plugin.economy(); }
    private Messages msg() { return plugin.messages(); }
    private FileConfiguration cfg() { return plugin.getConfig(); }

    // ---------- tax ----------

    public double taxPercent() {
        return Math.max(0, cfg().getDouble("tax-percent", 5));
    }

    /** Tax on top of an amount, rounded to the amount's decimals. */
    public BigDecimal taxFor(BigDecimal amount) {
        double pct = taxPercent();
        if (pct <= 0) return BigDecimal.ZERO;
        return amount.multiply(BigDecimal.valueOf(pct)).divide(HUNDRED, Math.max(0, amount.scale()), RoundingMode.HALF_UP);
    }

    // ---------- placing ----------

    /** A player puts their own money on a target (plus tax). Sends all messages. */
    public void place(Player placer, UUID target, String targetName, BigDecimal amount) {
        if (target.equals(placer.getUniqueId())) {
            msg().send(placer, "place-self");
            return;
        }
        Player online = Bukkit.getPlayer(target);
        if (online != null && online.hasPermission("wintermark.bounty.exempt")) {
            msg().send(placer, "place-exempt", text("target", targetName));
            return;
        }
        BigDecimal min = plugin.minimumAmount();
        if (min != null && amount.compareTo(min) < 0) {
            msg().send(placer, "place-min", text("min", eco().format(min)));
            return;
        }
        int cooldown = cfg().getInt("place-cooldown-seconds", 30);
        if (cooldown > 0 && !placer.hasPermission("wintermark.bounty.bypass.cooldown")) {
            Long last = lastPlaced.get(placer.getUniqueId());
            long left = last == null ? 0 : last + cooldown * 1000L - System.currentTimeMillis();
            if (left > 0) {
                msg().send(placer, "place-cooldown", text("seconds", String.valueOf((left + 999) / 1000)));
                return;
            }
        }
        BigDecimal tax = taxFor(amount);
        BigDecimal cost = amount.add(tax);
        if (!eco().has(placer.getUniqueId(), cost)
                || !eco().withdraw(placer.getUniqueId(), cost, "BOUNTY: placed on " + targetName
                + (tax.signum() > 0 ? " (incl. " + eco().format(tax) + " tax)" : ""))) {
            msg().send(placer, "not-enough", text("amount", eco().format(cost)),
                    text("balance", eco().format(eco().balance(placer.getUniqueId()))));
            return;
        }
        lastPlaced.put(placer.getUniqueId(), System.currentTimeMillis());
        Bounty b = add(target, targetName, placer.getUniqueId(), placer.getName(), amount);

        TagResolver tags = tags(placer.getName(), b, amount, tax);
        msg().send(placer, tax.signum() > 0 ? "placed-taxed" : "placed", tags);
        announce(b, tags, "broadcast-placed", amount);
    }

    /** The server (admin command) adds money that nobody paid for. */
    public Bounty placeAsServer(UUID target, String targetName, BigDecimal amount) {
        Bounty b = add(target, targetName, Bounty.SERVER, "Server", amount);
        announce(b, tags("Server", b, amount, BigDecimal.ZERO), "broadcast-placed", amount);
        return b;
    }

    private Bounty add(UUID target, String targetName, UUID contributor, String contributorName, BigDecimal amount) {
        Bounty b = bounties.computeIfAbsent(target, id -> new Bounty(id, targetName));
        b.targetName(targetName);
        Bounty.Contribution c = b.add(contributor, contributorName, amount, System.currentTimeMillis());
        plugin.database().saveContribution(b, c);
        return b;
    }

    private TagResolver tags(String placerName, Bounty b, BigDecimal amount, BigDecimal tax) {
        return TagResolver.resolver(
                text("player", placerName),
                text("target", b.targetName()),
                text("amount", eco().format(amount)),
                text("tax", eco().format(tax)),
                text("total", eco().format(b.total())));
    }

    private void announce(Bounty b, TagResolver tags, String broadcastKey, BigDecimal amount) {
        BigDecimal broadcastMin = plugin.broadcastPlaceMinimum();
        if (cfg().getBoolean("broadcast.place", true)
                && (broadcastMin == null || amount.compareTo(broadcastMin) >= 0)) {
            Component c = msg().render(broadcastKey, tags);
            if (c != null) Bukkit.broadcast(c);
        }
        Player target = Bukkit.getPlayer(b.target());
        if (target != null) {
            msg().send(target, "placed-on-you", tags);
            target.playSound(target.getLocation(), Sound.ENTITY_WITHER_SPAWN, 0.4f, 1.6f);
        }
    }

    // ---------- auto bounties ----------

    /** Called for every player kill. Puts a server bounty on killers who kill too often. */
    public void recordKill(Player killer, Player victim, boolean victimWasWanted) {
        if (!cfg().getBoolean("auto-bounty.enabled", true)) return;
        if (victimWasWanted && cfg().getBoolean("auto-bounty.ignore-wanted-victims", true)) return;
        if (killer.hasPermission("wintermark.bounty.exempt")) return;
        if (sameIp(killer, victim)) return;

        int needed = Math.max(1, cfg().getInt("auto-bounty.kills", 3));
        long window = Math.max(1, cfg().getLong("auto-bounty.minutes", 10)) * 60_000L;
        long now = System.currentTimeMillis();
        Deque<Long> kills = recentKills.computeIfAbsent(killer.getUniqueId(), id -> new ArrayDeque<>());
        while (!kills.isEmpty() && kills.peekFirst() < now - window) kills.pollFirst();
        kills.addLast(now);
        if (kills.size() < needed) return;

        BigDecimal amount = plugin.autoBountyAmount();
        if (amount == null) return;
        if (!cfg().getBoolean("auto-bounty.stack-per-kill", true)) kills.clear();

        Bounty b = add(killer.getUniqueId(), killer.getName(), Bounty.SERVER, "Server", amount);
        TagResolver tags = TagResolver.resolver(
                text("target", killer.getName()),
                text("amount", eco().format(amount)),
                text("total", eco().format(b.total())),
                text("kills", String.valueOf(kills.isEmpty() ? needed : kills.size())),
                text("minutes", String.valueOf(window / 60_000L)));
        Component c = msg().render("broadcast-auto", tags);
        if (c != null) Bukkit.broadcast(c);
        msg().send(killer, "auto-on-you", tags);
        killer.playSound(killer.getLocation(), Sound.ENTITY_WITHER_SPAWN, 0.4f, 1.6f);
    }

    // ---------- expiry ----------

    /** Refunds and removes shares older than expiry.days. Runs on a timer. */
    public void expire() {
        double days = cfg().getDouble("expiry.days", 7);
        if (days <= 0) return;
        long cutoff = System.currentTimeMillis() - (long) (days * 86_400_000L);
        Iterator<Bounty> it = bounties.values().iterator();
        while (it.hasNext()) {
            Bounty b = it.next();
            List<Bounty.Contribution> old = new ArrayList<>();
            for (Bounty.Contribution c : b.contributions()) if (c.placed() < cutoff) old.add(c);
            if (old.isEmpty()) continue;
            for (Bounty.Contribution c : old) {
                b.removeContribution(c.id());
                plugin.database().deleteContribution(b.target(), c.id());
                if (Bounty.SERVER.equals(c.id())) continue;
                if (eco().deposit(c.id(), c.amount(), "BOUNTY: refund (bounty on " + b.targetName() + " expired)")) {
                    Player p = Bukkit.getPlayer(c.id());
                    if (p != null) {
                        msg().send(p, "expired-refund", text("target", b.targetName()),
                                text("amount", eco().format(c.amount())));
                    }
                } else {
                    plugin.getLogger().warning("Could not refund expired bounty share " + c.amount() + " to " + c.name());
                }
            }
            if (b.isEmpty()) {
                it.remove();
                plugin.database().deleteBounty(b.target());
            }
            Player target = Bukkit.getPlayer(b.target());
            if (target != null) {
                if (b.isEmpty()) msg().send(target, "expired-all");
                else msg().send(target, "expired-part", text("total", eco().format(b.total())));
            }
        }
    }

    // ---------- removing ----------

    /** Admin removal. Refunds every player contributor when {@code refund} is true. Returns the refunded total. */
    public BigDecimal remove(UUID target, boolean refund) {
        Bounty b = bounties.remove(target);
        if (b == null) return null;
        plugin.database().deleteBounty(target);
        BigDecimal refunded = BigDecimal.ZERO;
        if (refund) {
            for (Bounty.Contribution c : b.contributions()) {
                if (Bounty.SERVER.equals(c.id())) continue;
                if (eco().deposit(c.id(), c.amount(), "BOUNTY: refund (bounty on " + b.targetName() + " removed)")) {
                    refunded = refunded.add(c.amount());
                    Player p = Bukkit.getPlayer(c.id());
                    if (p != null) {
                        msg().send(p, "refunded", text("target", b.targetName()), text("amount", eco().format(c.amount())));
                    }
                } else {
                    plugin.getLogger().warning("Could not refund " + c.amount() + " to " + c.name());
                }
            }
        }
        return refunded;
    }

    // ---------- claiming ----------

    /** Anti-abuse check for a real kill. Returns a message key when the claim is not allowed, else null. */
    public String claimBlockedReason(Player victim, Player killer) {
        Bounty b = bounties.get(victim.getUniqueId());
        if (b == null) return null;
        if (cfg().getBoolean("anti-abuse.block-same-ip", true) && sameIp(victim, killer)) return "claim-same-ip";
        if (!cfg().getBoolean("anti-abuse.contributors-can-claim", false)
                && b.contribution(killer.getUniqueId()) != null) return "claim-contributor";
        return null;
    }

    public static boolean sameIp(Player a, Player b) {
        InetSocketAddress x = a.getAddress();
        InetSocketAddress y = b.getAddress();
        if (x == null || y == null || x.getAddress() == null || y.getAddress() == null) return false;
        return x.getAddress().getHostAddress().equals(y.getAddress().getHostAddress());
    }

    /**
     * Pays the whole bounty to the killer. Returns the head item (or null when there was
     * no bounty / payment failed). The caller decides where the head goes.
     */
    public ItemStack claim(UUID target, String targetName, Player killer) {
        Bounty b = bounties.get(target);
        if (b == null) return null;
        BigDecimal total = b.total();
        if (!eco().deposit(killer.getUniqueId(), total, "BOUNTY: claimed " + targetName)) {
            msg().send(killer, "claim-failed", text("target", targetName), text("amount", eco().format(total)));
            return null;
        }
        bounties.remove(target);
        plugin.database().deleteBounty(target);
        plugin.database().insertClaim(target, targetName, killer.getUniqueId(), killer.getName(), total);

        TagResolver tags = TagResolver.resolver(
                text("killer", killer.getName()),
                text("target", targetName),
                text("amount", eco().format(total)));
        msg().send(killer, "claimed", tags);
        killer.playSound(killer.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 0.8f, 1f);
        if (cfg().getBoolean("broadcast.claim", true)) {
            Component c = msg().render("broadcast-claimed", tags);
            if (c != null) Bukkit.broadcast(c);
        }
        Player victim = Bukkit.getPlayer(target);
        if (victim != null) msg().send(victim, "claimed-on-you", tags);

        if (!cfg().getBoolean("head-drop.enabled", true)) return null;
        return head(Bukkit.getOfflinePlayer(target), targetName, killer.getName(), total);
    }

    private ItemStack head(OfflinePlayer owner, String targetName, String killerName, BigDecimal amount) {
        ItemStack item = new ItemStack(Material.PLAYER_HEAD);
        SkullMeta meta = (SkullMeta) item.getItemMeta();
        meta.setOwningPlayer(owner);
        TagResolver tags = TagResolver.resolver(
                text("target", targetName),
                text("killer", killerName),
                text("amount", eco().format(amount)));
        meta.displayName(noItalic(MM.deserialize(cfg().getString("head-drop.name", "<red><target>'s head"), tags)));
        List<Component> lore = new ArrayList<>();
        for (String line : cfg().getStringList("head-drop.lore")) lore.add(noItalic(MM.deserialize(line, tags)));
        meta.lore(lore);
        item.setItemMeta(meta);
        hideProfileLine(item);
        return item;
    }

    /** "3 players", "the server", "1 player + the server" (texts from config placed-by.*). */
    String placedBy(Bounty b) {
        int n = b.playerContributors();
        String players = n == 1 ? cfg().getString("placed-by.one", "1 player")
                : cfg().getString("placed-by.many", "<count> players").replace("<count>", String.valueOf(n));
        String server = cfg().getString("placed-by.server", "the server");
        if (n == 0) return server;
        if (!b.hasServerShare()) return players;
        return cfg().getString("placed-by.both", "<players> + <server>")
                .replace("<players>", players).replace("<server>", server);
    }

    /** Old 0.x configs said "<contributors> player(s)"; show the new placed-by text there too. */
    static String upgradeLine(String line) {
        return line.replace("<contributors></white> player(s)", "<placed_by></white>")
                .replace("(<contributors> player(s))", "(<placed_by>)");
    }

    /** Hides the vanilla profile line ("Dynamic") under player head names. */
    static void hideProfileLine(ItemStack item) {
        item.setData(DataComponentTypes.TOOLTIP_DISPLAY, TooltipDisplay.tooltipDisplay()
                .addHiddenComponents(DataComponentTypes.PROFILE).build());
    }

    static Component noItalic(Component c) {
        return c.decorationIfAbsent(TextDecoration.ITALIC, TextDecoration.State.FALSE);
    }

    public void forget(UUID player) {
        recentKills.remove(player);
    }
}
