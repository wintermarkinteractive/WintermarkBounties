package io.github.wintermarkinteractive.bounties;

import io.github.wintermarkinteractive.bounties.economy.EconomyBridge;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.logging.Level;

public final class BountiesPlugin extends JavaPlugin {

    private Database database;
    private BountyService bounties;
    private volatile EconomyBridge economy;
    private volatile Messages messages;
    private volatile BigDecimal minimumAmount;
    private volatile BigDecimal broadcastPlaceMinimum;
    private volatile BigDecimal autoBountyAmount;
    private org.bukkit.scheduler.BukkitTask expiryTask;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        addMissingConfigKeys();
        messages = new Messages(getConfig());

        database = new Database(new File(getDataFolder(), "bounties.db"), getLogger());
        bounties = new BountyService(this);
        try {
            database.open();
            bounties.load(database.loadBounties());
        } catch (Exception e) {
            getLogger().log(Level.SEVERE, "Could not open bounties.db - disabling.", e);
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        getServer().getPluginManager().registerEvents(new BountyListener(this), this);
        registerCommand("bounty", "Place bounties and see who is wanted", List.of("bounties"), new BountyCommand(this));

        // Economy plugins register while the server starts; look for one once everything is enabled.
        getServer().getScheduler().runTask(this, () -> {
            economy = EconomyBridge.find(getLogger());
            if (economy == null) {
                getLogger().severe("No economy found. Install WintermarkEconomy, or Vault/VaultUnlocked + an economy plugin.");
                getServer().getPluginManager().disablePlugin(this);
                return;
            }
            applyAmounts();
            scheduleExpiry();
            getLogger().info("Using economy: " + economy.name() + ". " + bounties.count() + " open bounties loaded.");
        });
    }

    @Override
    public void onDisable() {
        if (database != null) database.close();
    }

    public void reload() {
        reloadConfig();
        addMissingConfigKeys();
        messages = new Messages(getConfig());
        applyAmounts();
        scheduleExpiry();
    }

    /** Checks for expired bounty shares every expiry.check-minutes (first check 1 minute after start). */
    private void scheduleExpiry() {
        if (expiryTask != null) expiryTask.cancel();
        if (economy == null || getConfig().getDouble("expiry.days", 7) <= 0) return;
        long minutes = Math.max(1, getConfig().getLong("expiry.check-minutes", 5));
        expiryTask = getServer().getScheduler().runTaskTimer(this, () -> bounties.expire(), 20L * 60, minutes * 60 * 20L);
    }

    private void applyAmounts() {
        if (economy == null) return;
        minimumAmount = amount("min-amount", "100");
        broadcastPlaceMinimum = amount("broadcast.place-min", "0");
        autoBountyAmount = amount("auto-bounty.amount", "1k");
    }

    /** Reads an amount like "500" or "2.5k"; "0" means none. */
    private BigDecimal amount(String path, String fallback) {
        String raw = String.valueOf(getConfig().get(path, fallback)).trim();
        if (raw.equals("0")) return null;
        BigDecimal v = economy.parse(raw);
        if (v == null) {
            getLogger().warning("Invalid amount '" + raw + "' at " + path + ", using " + fallback);
            v = fallback.equals("0") ? null : economy.parse(fallback);
        }
        return v;
    }

    /** Adds keys that are new in this version to an existing config.yml, with their comments. */
    private void addMissingConfigKeys() {
        try (InputStream in = getResource("config.yml")) {
            if (in == null) return;
            YamlConfiguration defaults;
            try (Reader r = new InputStreamReader(in, StandardCharsets.UTF_8)) {
                defaults = YamlConfiguration.loadConfiguration(r);
            }
            FileConfiguration cfg = getConfig();
            Set<String> newSections = new HashSet<>();
            for (String key : defaults.getKeys(true)) {
                if (defaults.isConfigurationSection(key) && !cfg.contains(key, true)) newSections.add(key);
            }
            boolean changed = false;
            for (String key : defaults.getKeys(true)) {
                if (defaults.isConfigurationSection(key)) continue;
                if (!cfg.contains(key, true)) {
                    cfg.set(key, defaults.get(key));
                    cfg.setComments(key, defaults.getComments(key));
                    changed = true;
                }
            }
            for (String key : newSections) cfg.setComments(key, defaults.getComments(key));
            if (changed) {
                saveConfig();
                reloadConfig();
                getLogger().info("Added new settings to config.yml.");
            }
        } catch (Exception e) {
            getLogger().log(Level.WARNING, "Could not update config.yml with new keys", e);
        }
    }

    public Database database() { return database; }
    public BountyService bounties() { return bounties; }
    public EconomyBridge economy() { return economy; }
    public Messages messages() { return messages; }
    public BigDecimal minimumAmount() { return minimumAmount; }
    public BigDecimal broadcastPlaceMinimum() { return broadcastPlaceMinimum; }
    public BigDecimal autoBountyAmount() { return autoBountyAmount; }
}
