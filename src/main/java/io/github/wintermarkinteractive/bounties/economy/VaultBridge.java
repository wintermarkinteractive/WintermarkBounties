package io.github.wintermarkinteractive.bounties.economy;

import net.milkbowl.vault.economy.Economy;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.plugin.RegisteredServiceProvider;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Pattern;

/** Any Vault economy (EssentialsX, CMI, ...). */
@SuppressWarnings("deprecation")
final class VaultBridge implements EconomyBridge {

    private static final Pattern NUMBER = Pattern.compile("^[0-9]*\\.?[0-9]+$");

    private final Economy eco;

    private VaultBridge(Economy eco) {
        this.eco = eco;
    }

    static EconomyBridge create() {
        RegisteredServiceProvider<Economy> rsp = Bukkit.getServicesManager().getRegistration(Economy.class);
        if (rsp == null) throw new IllegalStateException("no Vault economy provider");
        return new VaultBridge(rsp.getProvider());
    }

    private static OfflinePlayer op(UUID id) {
        return Bukkit.getOfflinePlayer(id);
    }

    @Override public String name() { return "Vault (" + eco.getName() + ")"; }

    @Override
    public BigDecimal balance(UUID player) {
        return BigDecimal.valueOf(eco.getBalance(op(player)));
    }

    @Override
    public boolean has(UUID player, BigDecimal amount) {
        return eco.has(op(player), amount.doubleValue());
    }

    @Override
    public boolean withdraw(UUID player, BigDecimal amount, String reason) {
        return eco.withdrawPlayer(op(player), amount.doubleValue()).transactionSuccess();
    }

    @Override
    public boolean deposit(UUID player, BigDecimal amount, String reason) {
        OfflinePlayer p = op(player);
        if (!eco.hasAccount(p)) eco.createPlayerAccount(p);
        return eco.depositPlayer(p, amount.doubleValue()).transactionSuccess();
    }

    @Override
    public String format(BigDecimal amount) {
        return eco.format(amount.doubleValue());
    }

    @Override
    public BigDecimal parse(String input) {
        if (input == null) return null;
        String s = input.trim().replace(",", "").replace("_", "").replace("$", "").toLowerCase(Locale.ROOT);
        if (s.isEmpty() || s.length() > 32) return null;
        int exp = switch (s.charAt(s.length() - 1)) {
            case 'k' -> 3;
            case 'm' -> 6;
            case 'b' -> 9;
            case 't' -> 12;
            default -> 0;
        };
        if (exp > 0) s = s.substring(0, s.length() - 1);
        if (!NUMBER.matcher(s).matches()) return null;
        int fd = eco.fractionalDigits();
        int digits = fd < 0 ? 2 : fd;
        BigDecimal v = new BigDecimal(s).movePointRight(exp).setScale(digits, RoundingMode.DOWN);
        return v.signum() > 0 ? v : null;
    }
}
