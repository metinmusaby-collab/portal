package com.metolox.portal;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.util.*;

public class PortalPlugin extends JavaPlugin implements Listener, TabExecutor {

    /** Bir portalın bölgesi ve hedef noktası. */
    static class Portal {
        final String name;
        String world;
        int minX, minY, minZ, maxX, maxY, maxZ;
        boolean hasRegion = false;
        Location dest;

        Portal(String name) {
            this.name = name;
        }

        boolean contains(Location l) {
            if (!hasRegion || l.getWorld() == null || !l.getWorld().getName().equals(world)) return false;
            int x = l.getBlockX(), y = l.getBlockY(), z = l.getBlockZ();
            return x >= minX && x <= maxX && y >= minY && y <= maxY && z >= minZ && z <= maxZ;
        }
    }

    /** Oyuncunun asa ile yaptığı seçim. */
    static class Selection {
        String portalName;
        Location pos1, pos2;
    }

    private final Map<String, Portal> portals = new LinkedHashMap<>();
    private final Map<UUID, Selection> selections = new HashMap<>();
    private final Map<UUID, Long> cooldowns = new HashMap<>();
    private NamespacedKey wandKey;
    private File dataFile;

    @Override
    public void onEnable() {
        wandKey = new NamespacedKey(this, "portal_wand");
        dataFile = new File(getDataFolder(), "portals.yml");
        loadPortals();
        getServer().getPluginManager().registerEvents(this, this);
        var cmd = getCommand("portal");
        if (cmd != null) {
            cmd.setExecutor(this);
            cmd.setTabCompleter(this);
        }
        getLogger().info(portals.size() + " portal yüklendi.");
    }

    @Override
    public void onDisable() {
        savePortals();
    }

    // ------------------------------------------------------------ komutlar

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player p)) {
            sender.sendMessage("Bu komut sadece oyuncular içindir.");
            return true;
        }
        if (!p.hasPermission("portal.admin")) {
            msg(p, "Bu komut için yetkin yok.", NamedTextColor.RED);
            return true;
        }
        if (args.length == 0) {
            usage(p);
            return true;
        }

        String sub = args[0].toLowerCase();
        switch (sub) {
            case "list" -> {
                if (portals.isEmpty()) {
                    msg(p, "Hiç portal yok.", NamedTextColor.YELLOW);
                } else {
                    msg(p, "Portallar: " + String.join(", ", portals.keySet()), NamedTextColor.GREEN);
                }
            }
            case "create" -> {
                if (args.length < 2) { usage(p); return true; }
                String name = args[1].toLowerCase();
                if (portals.containsKey(name)) {
                    msg(p, "Bu isimde bir portal zaten var.", NamedTextColor.RED);
                    return true;
                }
                portals.put(name, new Portal(name));
                savePortals();
                msg(p, "'" + name + "' portalı oluşturuldu. Şimdi /portal wand " + name
                        + " ile bölgeyi seç, sonra /portal tp " + name + " ile hedefi ayarla.", NamedTextColor.GREEN);
            }
            case "wand" -> {
                if (args.length < 2) { usage(p); return true; }
                String name = args[1].toLowerCase();
                if (!portals.containsKey(name)) {
                    msg(p, "Böyle bir portal yok. Önce /portal create " + name, NamedTextColor.RED);
                    return true;
                }
                Selection sel = new Selection();
                sel.portalName = name;
                selections.put(p.getUniqueId(), sel);
                p.getInventory().addItem(createWand(name));
                msg(p, "'" + name + "' seçildi. Asa ile SOL TIK = 1. nokta, SAĞ TIK = 2. nokta.", NamedTextColor.GREEN);
            }
            case "tp" -> {
                if (args.length < 2) { usage(p); return true; }
                String name = args[1].toLowerCase();
                Portal portal = portals.get(name);
                if (portal == null) {
                    msg(p, "Böyle bir portal yok.", NamedTextColor.RED);
                    return true;
                }
                portal.dest = p.getLocation().clone();
                savePortals();
                msg(p, "'" + name + "' portalının hedefi bulunduğun yer olarak ayarlandı.", NamedTextColor.GREEN);
            }
            case "delete" -> {
                if (args.length < 2) { usage(p); return true; }
                String name = args[1].toLowerCase();
                if (portals.remove(name) == null) {
                    msg(p, "Böyle bir portal yok.", NamedTextColor.RED);
                    return true;
                }
                savePortals();
                msg(p, "'" + name + "' silindi.", NamedTextColor.YELLOW);
            }
            default -> usage(p);
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!sender.hasPermission("portal.admin")) return List.of();
        List<String> out = new ArrayList<>();
        if (args.length == 1) {
            for (String s : List.of("create", "wand", "tp", "delete", "list")) {
                if (s.startsWith(args[0].toLowerCase())) out.add(s);
            }
        } else if (args.length == 2 && !args[0].equalsIgnoreCase("create") && !args[0].equalsIgnoreCase("list")) {
            for (String s : portals.keySet()) {
                if (s.startsWith(args[1].toLowerCase())) out.add(s);
            }
        }
        return out;
    }

    private void usage(Player p) {
        msg(p, "/portal create <ad> | /portal wand <ad> | /portal tp <ad> | /portal delete <ad> | /portal list",
                NamedTextColor.YELLOW);
    }

    private void msg(Player p, String text, NamedTextColor color) {
        p.sendMessage(Component.text("[Portal] ", NamedTextColor.GOLD).append(Component.text(text, color)));
    }

    // ------------------------------------------------------------ asa

    private ItemStack createWand(String portalName) {
        ItemStack item = new ItemStack(Material.BLAZE_ROD);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text("Portal Asası (" + portalName + ")", NamedTextColor.LIGHT_PURPLE));
        meta.getPersistentDataContainer().set(wandKey, PersistentDataType.BYTE, (byte) 1);
        item.setItemMeta(meta);
        return item;
    }

    private boolean isWand(ItemStack item) {
        if (item == null || item.getType() != Material.BLAZE_ROD || !item.hasItemMeta()) return false;
        return item.getItemMeta().getPersistentDataContainer().has(wandKey, PersistentDataType.BYTE);
    }

    @EventHandler
    public void onInteract(PlayerInteractEvent e) {
        if (e.getHand() != EquipmentSlot.HAND) return;
        if (e.getClickedBlock() == null) return;
        if (!isWand(e.getItem())) return;

        Action a = e.getAction();
        if (a != Action.LEFT_CLICK_BLOCK && a != Action.RIGHT_CLICK_BLOCK) return;

        Player p = e.getPlayer();
        e.setCancelled(true);

        if (!p.hasPermission("portal.admin")) return;

        Selection sel = selections.get(p.getUniqueId());
        if (sel == null) {
            msg(p, "Önce /portal wand <ad> yaz.", NamedTextColor.RED);
            return;
        }
        Portal portal = portals.get(sel.portalName);
        if (portal == null) {
            msg(p, "Seçili portal artık yok.", NamedTextColor.RED);
            selections.remove(p.getUniqueId());
            return;
        }

        Location loc = e.getClickedBlock().getLocation();
        if (a == Action.LEFT_CLICK_BLOCK) {
            sel.pos1 = loc;
            msg(p, "1. nokta: " + fmt(loc), NamedTextColor.AQUA);
        } else {
            sel.pos2 = loc;
            msg(p, "2. nokta: " + fmt(loc), NamedTextColor.AQUA);
        }

        if (sel.pos1 != null && sel.pos2 != null) {
            World w1 = sel.pos1.getWorld();
            if (w1 == null || !w1.equals(sel.pos2.getWorld())) {
                msg(p, "İki nokta aynı dünyada olmalı.", NamedTextColor.RED);
                return;
            }
            portal.world = w1.getName();
            portal.minX = Math.min(sel.pos1.getBlockX(), sel.pos2.getBlockX());
            portal.minY = Math.min(sel.pos1.getBlockY(), sel.pos2.getBlockY());
            portal.minZ = Math.min(sel.pos1.getBlockZ(), sel.pos2.getBlockZ());
            portal.maxX = Math.max(sel.pos1.getBlockX(), sel.pos2.getBlockX());
            portal.maxY = Math.max(sel.pos1.getBlockY(), sel.pos2.getBlockY());
            portal.maxZ = Math.max(sel.pos1.getBlockZ(), sel.pos2.getBlockZ());
            portal.hasRegion = true;
            savePortals();
            msg(p, "'" + portal.name + "' bölgesi kaydedildi. Hedef için /portal tp " + portal.name,
                    NamedTextColor.GREEN);
        }
    }

    private String fmt(Location l) {
        return l.getBlockX() + ", " + l.getBlockY() + ", " + l.getBlockZ();
    }

    // ------------------------------------------------------------ ışınlama

    @EventHandler
    public void onMove(PlayerMoveEvent e) {
        Location from = e.getFrom();
        Location to = e.getTo();
        if (to == null) return;
        if (from.getBlockX() == to.getBlockX() && from.getBlockY() == to.getBlockY()
                && from.getBlockZ() == to.getBlockZ() && from.getWorld() == to.getWorld()) return;

        Player p = e.getPlayer();
        long now = System.currentTimeMillis();
        Long last = cooldowns.get(p.getUniqueId());
        if (last != null && now - last < 2000) return;

        for (Portal portal : portals.values()) {
            if (portal.dest != null && portal.contains(to)) {
                cooldowns.put(p.getUniqueId(), now);
                p.teleport(portal.dest);
                break;
            }
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        selections.remove(e.getPlayer().getUniqueId());
        cooldowns.remove(e.getPlayer().getUniqueId());
    }

    // ------------------------------------------------------------ kayıt

    private void loadPortals() {
        portals.clear();
        if (!dataFile.exists()) return;
        YamlConfiguration cfg = YamlConfiguration.loadConfiguration(dataFile);
        var section = cfg.getConfigurationSection("portals");
        if (section == null) return;
        for (String key : section.getKeys(false)) {
            var s = section.getConfigurationSection(key);
            if (s == null) continue;
            Portal p = new Portal(key);
            p.hasRegion = s.getBoolean("hasRegion", false);
            p.world = s.getString("world");
            p.minX = s.getInt("minX");
            p.minY = s.getInt("minY");
            p.minZ = s.getInt("minZ");
            p.maxX = s.getInt("maxX");
            p.maxY = s.getInt("maxY");
            p.maxZ = s.getInt("maxZ");
            p.dest = s.getLocation("dest");
            portals.put(key, p);
        }
    }

    private void savePortals() {
        YamlConfiguration cfg = new YamlConfiguration();
        for (Portal p : portals.values()) {
            String base = "portals." + p.name + ".";
            cfg.set(base + "hasRegion", p.hasRegion);
            cfg.set(base + "world", p.world);
            cfg.set(base + "minX", p.minX);
            cfg.set(base + "minY", p.minY);
            cfg.set(base + "minZ", p.minZ);
            cfg.set(base + "maxX", p.maxX);
            cfg.set(base + "maxY", p.maxY);
            cfg.set(base + "maxZ", p.maxZ);
            cfg.set(base + "dest", p.dest);
        }
        try {
            getDataFolder().mkdirs();
            cfg.save(dataFile);
        } catch (IOException ex) {
            getLogger().severe("portals.yml kaydedilemedi: " + ex.getMessage());
        }
    }
}
