package io.github.wintermarkinteractive.bounties;

import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.ItemStack;

import java.util.UUID;

import static io.github.wintermarkinteractive.bounties.Messages.text;

/** Menu clicks, bounty claims on player kills, join reminder. */
public final class BountyListener implements Listener {

    private final BountiesPlugin plugin;

    public BountyListener(BountiesPlugin plugin) {
        this.plugin = plugin;
    }

    // ---------- menu ----------

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        if (!(event.getInventory().getHolder(false) instanceof BountyMenu menu)) return;
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player)) return;
        if (event.getClickedInventory() == null || event.getClickedInventory() != event.getInventory()) return;

        int slot = event.getRawSlot();
        if (slot == BountyMenu.SLOT_PREV && menu.page() > 0) {
            BountyMenu.open(plugin, player, menu.page() - 1);
            return;
        }
        if (slot == BountyMenu.SLOT_NEXT) {
            BountyMenu.open(plugin, player, menu.page() + 1);
            return;
        }
        UUID target = BountyMenu.targetOf(plugin, event.getCurrentItem());
        if (target == null) return;
        if (target.equals(player.getUniqueId())) {
            plugin.messages().send(player, "place-self");
            return;
        }
        if (!player.hasPermission("wintermark.bounty.place")) {
            plugin.messages().send(player, "no-permission");
            return;
        }
        Bounty b = plugin.bounties().get(target);
        String name = b != null ? b.targetName() : Bukkit.getOfflinePlayer(target).getName();
        player.closeInventory();
        AmountDialog.open(plugin, player, target, name == null ? "?" : name);
    }

    @EventHandler
    public void onDrag(InventoryDragEvent event) {
        if (event.getInventory().getHolder(false) instanceof BountyMenu) event.setCancelled(true);
    }

    // ---------- claims ----------

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDeath(PlayerDeathEvent event) {
        Player victim = event.getEntity();
        Player killer = victim.getKiller();
        if (killer == null || killer.equals(victim)) return;
        boolean wanted = plugin.bounties().get(victim.getUniqueId()) != null;
        if (wanted) {
            String blocked = plugin.bounties().claimBlockedReason(victim, killer);
            if (blocked != null) {
                plugin.messages().send(killer, blocked, text("target", victim.getName()));
            } else {
                ItemStack head = plugin.bounties().claim(victim.getUniqueId(), victim.getName(), killer);
                if (head != null) event.getDrops().add(head);
            }
        }
        plugin.bounties().recordKill(killer, victim, wanted);
    }

    // ---------- reminder ----------

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Player p = event.getPlayer();
        Bounty b = plugin.bounties().get(p.getUniqueId());
        if (b != null && !b.targetName().equals(p.getName())) {
            b.targetName(p.getName());
        }
        if (b == null || !plugin.getConfig().getBoolean("join-reminder", true)) return;
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (p.isOnline() && plugin.bounties().get(p.getUniqueId()) != null) {
                plugin.messages().send(p, "join-reminder",
                        text("total", plugin.economy().format(plugin.bounties().get(p.getUniqueId()).total())));
            }
        }, 40L);
    }

    /** Helper for commands: online or known offline player. */
    public static OfflinePlayer findPlayer(String name) {
        Player online = Bukkit.getPlayerExact(name);
        if (online != null) return online;
        OfflinePlayer cached = Bukkit.getOfflinePlayerIfCached(name);
        if (cached != null && (cached.hasPlayedBefore() || cached.isOnline())) return cached;
        return null;
    }
}
