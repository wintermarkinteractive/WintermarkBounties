package io.github.wintermarkinteractive.bounties.economy;

import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;

import java.math.BigDecimal;
import java.util.UUID;
import java.util.logging.Logger;

/**
 * Money access for Bounties: Wintermark Economy when it is installed, otherwise any
 * economy plugin through Vault (EssentialsX, CMI, ...).
 */
public interface EconomyBridge {

    String name();

    BigDecimal balance(UUID player);

    boolean has(UUID player, BigDecimal amount);

    /** Returns true when the money was taken. */
    boolean withdraw(UUID player, BigDecimal amount, String reason);

    /** Returns true when the money was given. */
    boolean deposit(UUID player, BigDecimal amount, String reason);

    String format(BigDecimal amount);

    /** Parses "500", "2.5k", "1m". Null when invalid or not positive. */
    BigDecimal parse(String input);

    /** Finds the best available economy, or null when there is none. */
    static EconomyBridge find(Logger log) {
        Plugin wm = Bukkit.getPluginManager().getPlugin("WintermarkEconomy");
        if (wm != null && wm.isEnabled()) {
            try {
                EconomyBridge b = WintermarkBridge.create();
                if (b != null) return b;
            } catch (Throwable t) {
                log.warning("WintermarkEconomy found but its API could not be used: " + t);
            }
        }
        Plugin vault = Bukkit.getPluginManager().getPlugin("Vault");
        if (vault != null && vault.isEnabled()) {
            try {
                return VaultBridge.create();
            } catch (Throwable t) {
                log.warning("Vault found but no economy is registered: " + t);
            }
        }
        return null;
    }
}
