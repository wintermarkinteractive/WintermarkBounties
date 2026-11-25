package io.github.wintermarkinteractive.bounties.economy;

import io.github.wintermarkinteractive.economy.api.EconomyAPI;
import org.bukkit.Bukkit;
import org.bukkit.plugin.RegisteredServiceProvider;

import java.math.BigDecimal;
import java.util.UUID;

/** Uses Wintermark Economy's own API (exact amounts, proper history types). */
final class WintermarkBridge implements EconomyBridge {

    private final EconomyAPI api;

    private WintermarkBridge(EconomyAPI api) {
        this.api = api;
    }

    static EconomyBridge create() {
        RegisteredServiceProvider<EconomyAPI> rsp = Bukkit.getServicesManager().getRegistration(EconomyAPI.class);
        return rsp == null ? null : new WintermarkBridge(rsp.getProvider());
    }

    @Override public String name() { return "WintermarkEconomy"; }

    @Override
    public BigDecimal balance(UUID player) {
        return api.getBalance(player);
    }

    @Override
    public boolean has(UUID player, BigDecimal amount) {
        return api.has(player, amount);
    }

    @Override
    public boolean withdraw(UUID player, BigDecimal amount, String reason) {
        return api.withdraw(player, amount, reason).success();
    }

    @Override
    public boolean deposit(UUID player, BigDecimal amount, String reason) {
        if (!api.hasAccount(player)) {
            String name = Bukkit.getOfflinePlayer(player).getName();
            api.createAccount(player, name == null ? player.toString().substring(0, 16) : name);
        }
        return api.deposit(player, amount, reason).success();
    }

    @Override
    public String format(BigDecimal amount) {
        return api.format(amount);
    }

    @Override
    public BigDecimal parse(String input) {
        return api.parse(input);
    }
}
