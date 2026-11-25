package io.github.wintermarkinteractive.bounties;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.persistence.PersistentDataType;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.UUID;

import static io.github.wintermarkinteractive.bounties.Messages.text;

/** /bounty menu: heads of all wanted players, biggest bounty first, 45 per page. */
public final class BountyMenu implements InventoryHolder {

    public static final int PER_PAGE = 45;
    public static final int SLOT_PREV = 45;
    public static final int SLOT_INFO = 49;
    public static final int SLOT_NEXT = 53;

    private static final MiniMessage MM = MiniMessage.miniMessage();

    private final BountiesPlugin plugin;
    private final int page;
    private final int pages;
    private final Inventory inventory;

    private BountyMenu(BountiesPlugin plugin, int page, int pages) {
        this.plugin = plugin;
        this.page = page;
        this.pages = pages;
        this.inventory = Bukkit.createInventory(this, 54,
                MM.deserialize(plugin.getConfig().getString("menu.title", "<dark_red>Bounties"),
                        TagResolver.resolver(text("page", String.valueOf(page + 1)), text("pages", String.valueOf(pages)))));
    }

    public static void open(BountiesPlugin plugin, Player viewer, int page) {
        List<Bounty> list = plugin.bounties().sorted();
        int pages = Math.max(1, (list.size() + PER_PAGE - 1) / PER_PAGE);
        int p = Math.max(0, Math.min(page, pages - 1));
        BountyMenu menu = new BountyMenu(plugin, p, pages);
        menu.fill(list, viewer);
        viewer.openInventory(menu.inventory);
    }

    public int page() { return page; }

    @Override
    public Inventory getInventory() {
        return inventory;
    }

    public static NamespacedKey targetKey(BountiesPlugin plugin) {
        return new NamespacedKey(plugin, "target");
    }

    private void fill(List<Bounty> list, Player viewer) {
        NamespacedKey key = targetKey(plugin);
        SimpleDateFormat date = new SimpleDateFormat(plugin.getConfig().getString("menu.date-format", "yyyy-MM-dd HH:mm"));
        int from = page * PER_PAGE;
        for (int i = 0; i < PER_PAGE && from + i < list.size(); i++) {
            Bounty b = list.get(from + i);
            ItemStack head = new ItemStack(Material.PLAYER_HEAD);
            SkullMeta meta = (SkullMeta) head.getItemMeta();
            meta.setOwningPlayer(Bukkit.getOfflinePlayer(b.target()));
            TagResolver tags = TagResolver.resolver(
                    text("rank", String.valueOf(from + i + 1)),
                    text("target", b.targetName()),
                    text("total", plugin.economy().format(b.total())),
                    text("contributors", String.valueOf(b.playerContributors())),
                    text("placed_by", plugin.bounties().placedBy(b)),
                    text("status", Bukkit.getPlayer(b.target()) != null ? "online" : "offline"),
                    text("date", date.format(new Date(b.lastPlaced()))));
            meta.displayName(BountyService.noItalic(MM.deserialize(
                    plugin.getConfig().getString("menu.head-name", "<red><target>"), tags)));
            List<Component> lore = new ArrayList<>();
            List<String> lines = b.target().equals(viewer.getUniqueId())
                    ? plugin.getConfig().getStringList("menu.head-lore-self")
                    : plugin.getConfig().getStringList("menu.head-lore");
            for (String line : lines) lore.add(BountyService.noItalic(MM.deserialize(BountyService.upgradeLine(line), tags)));
            meta.lore(lore);
            meta.getPersistentDataContainer().set(key, PersistentDataType.STRING, b.target().toString());
            head.setItemMeta(meta);
            BountyService.hideProfileLine(head);
            inventory.setItem(i, head);
        }

        if (list.isEmpty()) {
            inventory.setItem(22, simple(Material.BARRIER, "menu.empty-name", "menu.empty-lore", TagResolver.empty()));
        }

        ItemStack filler = simple(Material.GRAY_STAINED_GLASS_PANE, null, null, TagResolver.empty());
        for (int s = 45; s < 54; s++) inventory.setItem(s, filler);
        double expiry = plugin.getConfig().getDouble("expiry.days", 7);
        TagResolver nav = TagResolver.resolver(text("page", String.valueOf(page + 1)), text("pages", String.valueOf(pages)),
                text("count", String.valueOf(list.size())),
                text("tax", trim(plugin.bounties().taxPercent())),
                text("expiry", expiry <= 0 ? "never" : trim(expiry) + " days"));
        if (page > 0) inventory.setItem(SLOT_PREV, simple(Material.ARROW, "menu.prev-name", null, nav));
        if (page < pages - 1) inventory.setItem(SLOT_NEXT, simple(Material.ARROW, "menu.next-name", null, nav));
        ItemStack info = simple(Material.BOOK, "menu.info-name", "menu.info-lore", nav);
        // Extra lines (own key, so servers with an older config.yml get them too)
        ItemMeta infoMeta = info.getItemMeta();
        List<Component> infoLore = infoMeta.lore() == null ? new ArrayList<>() : new ArrayList<>(infoMeta.lore());
        for (String line : plugin.getConfig().getStringList("menu.info-extra")) {
            infoLore.add(BountyService.noItalic(MM.deserialize(line, nav)));
        }
        infoMeta.lore(infoLore);
        info.setItemMeta(infoMeta);
        inventory.setItem(SLOT_INFO, info);
    }

    private ItemStack simple(Material mat, String namePath, String lorePath, TagResolver tags) {
        ItemStack item = new ItemStack(mat);
        ItemMeta meta = item.getItemMeta();
        if (namePath == null) {
            meta.displayName(Component.empty());
            meta.setHideTooltip(true);
        } else {
            meta.displayName(BountyService.noItalic(MM.deserialize(plugin.getConfig().getString(namePath, ""), tags)));
        }
        if (lorePath != null) {
            List<Component> lore = new ArrayList<>();
            for (String line : plugin.getConfig().getStringList(lorePath)) lore.add(BountyService.noItalic(MM.deserialize(line, tags)));
            meta.lore(lore);
        }
        item.setItemMeta(meta);
        return item;
    }

    static String trim(double d) {
        return d == Math.rint(d) ? String.valueOf((long) d) : String.valueOf(d);
    }

    /** Target id stored on a head in this menu, or null. */
    public static UUID targetOf(BountiesPlugin plugin, ItemStack item) {
        if (item == null || !item.hasItemMeta()) return null;
        String s = item.getItemMeta().getPersistentDataContainer().get(targetKey(plugin), PersistentDataType.STRING);
        if (s == null) return null;
        try {
            return UUID.fromString(s);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
