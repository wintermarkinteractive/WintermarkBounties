package io.github.wintermarkinteractive.bounties;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/** All money placed on one player. Each contributor is tracked so it can be refunded later. */
public final class Bounty {

    /** Contributor id used for bounties placed by the server (admin / auto bounties). */
    public static final UUID SERVER = new UUID(0L, 0L);

    public record Contribution(UUID id, String name, BigDecimal amount, long placed) { }

    private final UUID target;
    private String targetName;
    private final Map<UUID, Contribution> contributions = new LinkedHashMap<>();

    public Bounty(UUID target, String targetName) {
        this.target = target;
        this.targetName = targetName;
    }

    public UUID target() { return target; }
    public String targetName() { return targetName; }
    public void targetName(String name) { this.targetName = name; }

    public Collection<Contribution> contributions() { return contributions.values(); }

    public Contribution contribution(UUID contributor) { return contributions.get(contributor); }

    /** Adds to this contributor's share and returns the new share. */
    public Contribution add(UUID contributor, String name, BigDecimal amount, long time) {
        Contribution old = contributions.get(contributor);
        BigDecimal sum = old == null ? amount : old.amount().add(amount);
        Contribution c = new Contribution(contributor, name, sum, time);
        contributions.put(contributor, c);
        return c;
    }

    /** Removes one contributor's share. */
    public Contribution removeContribution(UUID contributor) {
        return contributions.remove(contributor);
    }

    public boolean isEmpty() { return contributions.isEmpty(); }

    /** Used when loading from the database. */
    void put(Contribution c) {
        contributions.put(c.id(), c);
    }

    public BigDecimal total() {
        BigDecimal t = BigDecimal.ZERO;
        for (Contribution c : contributions.values()) t = t.add(c.amount());
        return t;
    }

    /** Number of players who added money (the server does not count). */
    public int playerContributors() {
        int n = 0;
        for (UUID id : contributions.keySet()) if (!SERVER.equals(id)) n++;
        return n;
    }

    /** True when the server (admin give / auto bounty) has money in this bounty. */
    public boolean hasServerShare() {
        return contributions.containsKey(SERVER);
    }

    public long lastPlaced() {
        long t = 0;
        for (Contribution c : contributions.values()) t = Math.max(t, c.placed());
        return t;
    }
}
