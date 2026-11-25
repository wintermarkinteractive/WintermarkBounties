package io.github.wintermarkinteractive.bounties;

import java.io.File;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

/** SQLite storage (bounties.db). All queries run in order on one background thread. */
public final class Database {

    private final File file;
    private final Logger log;
    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "WintermarkBounties-DB");
        t.setDaemon(true);
        return t;
    });
    private Connection connection;

    public Database(File file, Logger log) {
        this.file = file;
        this.log = log;
    }

    public void open() throws Exception {
        executor.submit(() -> {
            Class.forName("org.sqlite.JDBC");
            connection = DriverManager.getConnection("jdbc:sqlite:" + file.getAbsolutePath());
            try (Statement st = connection.createStatement()) {
                st.execute("PRAGMA journal_mode=WAL");
                st.execute("PRAGMA synchronous=NORMAL");
                st.execute("CREATE TABLE IF NOT EXISTS contributions ("
                        + "target TEXT NOT NULL, target_name TEXT NOT NULL, "
                        + "contributor TEXT NOT NULL, contributor_name TEXT NOT NULL, "
                        + "amount TEXT NOT NULL, placed INTEGER NOT NULL, "
                        + "PRIMARY KEY (target, contributor))");
                st.execute("CREATE TABLE IF NOT EXISTS claims ("
                        + "id INTEGER PRIMARY KEY AUTOINCREMENT, "
                        + "target TEXT NOT NULL, target_name TEXT NOT NULL, "
                        + "killer TEXT NOT NULL, killer_name TEXT NOT NULL, "
                        + "amount TEXT NOT NULL, time INTEGER NOT NULL)");
                st.execute("CREATE INDEX IF NOT EXISTS claims_killer ON claims(killer)");
            }
            return null;
        }).get();
    }

    /** Loads every open bounty (startup only). */
    public Map<UUID, Bounty> loadBounties() throws Exception {
        return executor.submit(() -> {
            Map<UUID, Bounty> map = new LinkedHashMap<>();
            try (Statement st = connection.createStatement();
                 ResultSet rs = st.executeQuery(
                         "SELECT target, target_name, contributor, contributor_name, amount, placed FROM contributions")) {
                while (rs.next()) {
                    try {
                        UUID target = UUID.fromString(rs.getString(1));
                        Bounty b = map.computeIfAbsent(target, id -> {
                            try {
                                return new Bounty(id, rs.getString(2));
                            } catch (SQLException e) {
                                return new Bounty(id, id.toString().substring(0, 8));
                            }
                        });
                        b.put(new Bounty.Contribution(UUID.fromString(rs.getString(3)), rs.getString(4),
                                new BigDecimal(rs.getString(5)), rs.getLong(6)));
                    } catch (IllegalArgumentException bad) {
                        log.warning("Skipping a broken bounty row: " + bad.getMessage());
                    }
                }
            }
            return map;
        }).get();
    }

    /** Stores one contributor's (new) share. */
    public void saveContribution(Bounty b, Bounty.Contribution c) {
        final UUID target = b.target();
        final String targetName = b.targetName();
        executor.execute(() -> {
            try (PreparedStatement ps = connection.prepareStatement(
                    "INSERT INTO contributions(target, target_name, contributor, contributor_name, amount, placed) "
                            + "VALUES(?, ?, ?, ?, ?, ?) ON CONFLICT(target, contributor) DO UPDATE SET "
                            + "target_name = excluded.target_name, contributor_name = excluded.contributor_name, "
                            + "amount = excluded.amount, placed = excluded.placed")) {
                ps.setString(1, target.toString());
                ps.setString(2, targetName);
                ps.setString(3, c.id().toString());
                ps.setString(4, c.name());
                ps.setString(5, c.amount().toPlainString());
                ps.setLong(6, c.placed());
                ps.executeUpdate();
            } catch (SQLException e) {
                log.log(Level.SEVERE, "Could not save bounty on " + targetName, e);
            }
        });
    }

    public void deleteBounty(UUID target) {
        executor.execute(() -> {
            try (PreparedStatement ps = connection.prepareStatement("DELETE FROM contributions WHERE target = ?")) {
                ps.setString(1, target.toString());
                ps.executeUpdate();
            } catch (SQLException e) {
                log.log(Level.SEVERE, "Could not delete bounty " + target, e);
            }
        });
    }

    public void insertClaim(UUID target, String targetName, UUID killer, String killerName, BigDecimal amount) {
        final long now = System.currentTimeMillis();
        executor.execute(() -> {
            try (PreparedStatement ps = connection.prepareStatement(
                    "INSERT INTO claims(target, target_name, killer, killer_name, amount, time) VALUES(?, ?, ?, ?, ?, ?)")) {
                ps.setString(1, target.toString());
                ps.setString(2, targetName);
                ps.setString(3, killer.toString());
                ps.setString(4, killerName);
                ps.setString(5, amount.toPlainString());
                ps.setLong(6, now);
                ps.executeUpdate();
            } catch (SQLException e) {
                log.log(Level.WARNING, "Could not record bounty claim", e);
            }
        });
    }

    /** Removes one contributor's share (expiry). */
    public void deleteContribution(UUID target, UUID contributor) {
        executor.execute(() -> {
            try (PreparedStatement ps = connection.prepareStatement(
                    "DELETE FROM contributions WHERE target = ? AND contributor = ?")) {
                ps.setString(1, target.toString());
                ps.setString(2, contributor.toString());
                ps.executeUpdate();
            } catch (SQLException e) {
                log.log(Level.SEVERE, "Could not delete expired bounty share", e);
            }
        });
    }

    public record HunterRow(String name, int claims, BigDecimal earned) { }

    /** Top bounty hunters, sorted by number of claims or by money earned. */
    public CompletableFuture<List<HunterRow>> topHunters(boolean byEarned, int limit) {
        return CompletableFuture.supplyAsync(() -> {
            List<HunterRow> rows = new ArrayList<>();
            String order = byEarned ? "earned DESC, claims DESC" : "claims DESC, earned DESC";
            String sql = "SELECT (SELECT c2.killer_name FROM claims c2 WHERE c2.killer = c.killer ORDER BY c2.id DESC LIMIT 1), "
                    + "COUNT(*) AS claims, SUM(CAST(amount AS REAL)) AS earned "
                    + "FROM claims c GROUP BY killer ORDER BY " + order + " LIMIT ?";
            try (PreparedStatement ps = connection.prepareStatement(sql)) {
                ps.setInt(1, limit);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        rows.add(new HunterRow(rs.getString(1), rs.getInt(2),
                                BigDecimal.valueOf(rs.getDouble(3)).setScale(2, java.math.RoundingMode.HALF_UP)));
                    }
                }
            } catch (SQLException e) {
                log.log(Level.WARNING, "Could not read top hunters", e);
            }
            return rows;
        }, executor);
    }

    public void close() {
        executor.shutdown();
        try {
            if (!executor.awaitTermination(15, TimeUnit.SECONDS)) log.severe("Database writes did not finish in time.");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        try {
            if (connection != null) connection.close();
        } catch (SQLException e) {
            log.log(Level.WARNING, "Could not close the database", e);
        }
    }
}
