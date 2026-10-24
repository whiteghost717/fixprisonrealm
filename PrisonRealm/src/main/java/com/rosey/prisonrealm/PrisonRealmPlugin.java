package com.rosey.prisonrealm;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.ShapedRecipe;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import java.util.*;

public final class PrisonRealmPlugin extends JavaPlugin implements Listener {
    private NamespacedKey itemKey;
    private final Map<UUID, PendingSeal> pending = new HashMap<>();
    private final Map<UUID, UUID> sealedBy = new HashMap<>(); // prisoner -> sealer
    private final Map<String, UUID> placedCores = new HashMap<>(); // block key -> placer
    private final Map<UUID, Location> frozenAt = new HashMap<>();

    @Override public void onEnable() {
        saveDefaultConfig();
        itemKey = new NamespacedKey(this, "prison_realm");
        Bukkit.getPluginManager().registerEvents(this, this);
        registerRecipe();
        loadSealed();
        getLogger().info("PrisonRealm enabled. Recipe registered.");
    }

    @Override public void onDisable() { saveSealed(); }

    private void registerRecipe() {
        NamespacedKey recipeKey = new NamespacedKey(this, "prison_realm_recipe");
        Bukkit.removeRecipe(recipeKey);
        ShapedRecipe recipe = new ShapedRecipe(recipeKey, createRealmItem());
        recipe.shape("RNR", "NEN", "RNR");
        recipe.setIngredient('R', Material.REDSTONE_BLOCK);
        recipe.setIngredient('N', Material.NETHER_STAR);
        recipe.setIngredient('E', Material.DRAGON_EGG);
        Bukkit.addRecipe(recipe);
    }

    private ItemStack createRealmItem() {
        ItemStack item = new ItemStack(Material.CRYING_OBSIDIAN);
        ItemMeta meta = item.getItemMeta();
        meta.setDisplayName(color(getConfig().getString("item.name", "&5&lPrison Realm")));
        List<String> lore = getConfig().getStringList("item.lore").stream().map(this::color).toList();
        meta.setLore(lore);
        meta.getPersistentDataContainer().set(itemKey, PersistentDataType.BYTE, (byte) 1);
        meta.setCustomModelData(getConfig().getInt("item.custom-model-data", 26001));
        if (getConfig().getBoolean("item.glow", true)) {
            meta.addEnchant(org.bukkit.enchantments.Enchantment.UNBREAKING, 1, true);
            meta.addItemFlags(org.bukkit.inventory.ItemFlag.HIDE_ENCHANTS);
        }
        item.setItemMeta(meta);
        return item;
    }

    private boolean isRealm(ItemStack item) {
        if (item == null || !item.hasItemMeta()) return false;
        return item.getItemMeta().getPersistentDataContainer().has(itemKey, PersistentDataType.BYTE);
    }

    private String color(String s) { return ChatColor.translateAlternateColorCodes('&', s == null ? "" : s); }
    private String blockKey(Location l) { return l.getWorld().getName()+":"+l.getBlockX()+":"+l.getBlockY()+":"+l.getBlockZ(); }

    @Override public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        String cmd = command.getName().toLowerCase(Locale.ROOT);
        if (cmd.equals("prisonrealm")) {
            if (args.length == 0) return help(sender);
            switch (args[0].toLowerCase(Locale.ROOT)) {
                case "give" -> {
                    if (!sender.hasPermission("prisonrealm.admin")) return noPerm(sender);
                    if (args.length < 2) { sender.sendMessage("§cUsage: /prisonrealm give <player>"); return true; }
                    Player target = Bukkit.getPlayerExact(args[1]);
                    if (target == null) { sender.sendMessage("§cPlayer must be online."); return true; }
                    target.getInventory().addItem(createRealmItem()).values().forEach(drop -> target.getWorld().dropItemNaturally(target.getLocation(), drop));
                    sender.sendMessage("§aGave Prison Realm to " + target.getName() + "."); return true;
                }
                case "status" -> { sender.sendMessage("§5PrisonRealm §7| sealed prisoners: §f" + sealedBy.size() + " §7| pending seals: §f" + pending.size()); return true; }
                case "sealed" -> { listSealed(sender); return true; }
                case "release" -> {
                    if (!sender.hasPermission("prisonrealm.admin")) return noPerm(sender);
                    if (args.length < 2) { sender.sendMessage("§cUsage: /prisonrealm release <player>"); return true; }
                    Player target = Bukkit.getPlayerExact(args[1]);
                    UUID id = target != null ? target.getUniqueId() : findOfflineUUID(args[1]);
                    if (id == null || !sealedBy.containsKey(id)) { sender.sendMessage("§cThat player is not sealed."); return true; }
                    release(id, "§eAn admin released you from the Prison Realm."); sender.sendMessage("§aReleased " + args[1] + "."); return true;
                }
                case "reload" -> { if (!sender.hasPermission("prisonrealm.admin")) return noPerm(sender); reloadConfig(); registerRecipe(); sender.sendMessage("§aPrisonRealm config and recipe reloaded."); return true; }
                default -> { return help(sender); }
            }
        }
        if (cmd.equals("freeze")) {
            if (!(sender instanceof Player sealer)) { sender.sendMessage("Players only."); return true; }
            if (!sealer.hasPermission("prisonrealm.use")) return noPerm(sender);
            if (args.length < 1) { sender.sendMessage("§cUsage: /freeze <player>"); return true; }
            Player target = Bukkit.getPlayerExact(args[0]);
            if (target == null || target.equals(sealer)) { sender.sendMessage("§cTarget must be another online player."); return true; }
            if (sealedBy.containsKey(target.getUniqueId())) { sender.sendMessage("§cThat player is already sealed."); return true; }
            pending.put(target.getUniqueId(), new PendingSeal(sealer.getUniqueId(), System.currentTimeMillis() + getConfig().getInt("sealing.window-seconds", 60) * 1000L));
            frozenAt.put(target.getUniqueId(), target.getLocation().clone());
            target.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, 20 * 65, 10, false, false, false));
            target.addPotionEffect(new PotionEffect(PotionEffectType.JUMP_BOOST, 20 * 65, 250, false, false, false));
            UUID targetId = target.getUniqueId();
            getServer().getScheduler().runTaskLater(this, () -> {
                PendingSeal current = pending.get(targetId);
                if (current != null && current.owner.equals(sealer.getUniqueId()) && current.expiresAt <= System.currentTimeMillis()) {
                    pending.remove(targetId);
                    frozenAt.remove(targetId);
                    Player online = Bukkit.getPlayer(targetId);
                    if (online != null && !sealedBy.containsKey(targetId)) {
                        online.removePotionEffect(PotionEffectType.SLOWNESS);
                        online.removePotionEffect(PotionEffectType.JUMP_BOOST);
                        online.sendMessage("§7The sealing attempt expired; you are free.");
                    }
                    sealer.sendMessage("§cThe 60-second sealing window expired.");
                }
            }, getConfig().getInt("sealing.window-seconds", 60) * 20L);
            sealer.sendMessage("§dTarget frozen. Place your Prison Realm within 1 block of them and run §f/seal§d within 60 seconds.");
            target.sendMessage("§5You have been frozen by a sealing attempt.");
            return true;
        }
        if (cmd.equals("seal")) {
            if (!(sender instanceof Player sealer)) { sender.sendMessage("Players only."); return true; }
            Player target = null;
            if (args.length >= 1) target = Bukkit.getPlayerExact(args[0]);
            else {
                double nearest = Double.MAX_VALUE;
                for (Map.Entry<UUID, PendingSeal> entry : pending.entrySet()) {
                    if (!entry.getValue().owner.equals(sealer.getUniqueId()) || entry.getValue().expiresAt < System.currentTimeMillis()) continue;
                    Player candidate = Bukkit.getPlayer(entry.getKey());
                    if (candidate == null || candidate.getWorld() != sealer.getWorld()) continue;
                    double d = candidate.getLocation().distance(sealer.getLocation());
                    if (d <= 2.5 && d < nearest) { nearest = d; target = candidate; }
                }
            }
            if (target == null) { sender.sendMessage("§cNo frozen target nearby. Use /seal or /seal <player>."); return true; }
            PendingSeal p = pending.get(target.getUniqueId());
            if (p == null || !p.owner.equals(sealer.getUniqueId()) || p.expiresAt < System.currentTimeMillis()) { sender.sendMessage("§cYou did not freeze this player, or the 60-second window expired."); return true; }
            if (sealer.getWorld() != target.getWorld() || sealer.getLocation().distance(target.getLocation()) > getConfig().getDouble("sealing.target-distance", 1.5) + 1.0) { sender.sendMessage("§cStand within 1 block of the frozen player."); return true; }
            final Player targetForCheck = target;
            final Location targetLocation = targetForCheck.getLocation();
            final World targetWorld = targetLocation.getWorld();
            final UUID sealerId = sealer.getUniqueId();
            boolean coreNear = placedCores.keySet().stream()
                    .map(this::parseBlockKey)
                    .filter(Objects::nonNull)
                    .anyMatch(loc -> loc.getWorld().equals(targetWorld)
                            && loc.distance(targetLocation) <= 2.0
                            && sealerId.equals(placedCores.get(blockKey(loc))));
            if (!coreNear) { sender.sendMessage("§cPlace your Prison Realm (the special crying obsidian item) within 1 block of the frozen player first."); return true; }
            sealedBy.put(target.getUniqueId(), sealer.getUniqueId());
            pending.remove(target.getUniqueId());
            frozenAt.remove(target.getUniqueId());
            target.sendMessage("§5You have been sealed inside the Prison Realm by " + sealer.getName() + ". Only they can unseal you.");
            sealer.sendMessage("§dSuccessfully sealed " + target.getName() + ". Use §f/unseal " + target.getName() + " §dto release them.");
            saveSealed(); return true;
        }
        if (cmd.equals("unseal")) {
            if (!(sender instanceof Player owner)) { sender.sendMessage("Players only. Admins can use /prisonrealm release <player>."); return true; }
            if (args.length < 1) { sender.sendMessage("§cUsage: /unseal <player>"); return true; }
            UUID targetId = findPlayerUUID(args[0]);
            if (targetId == null || !sealedBy.containsKey(targetId)) { sender.sendMessage("§cThat player is not sealed."); return true; }
            if (!owner.getUniqueId().equals(sealedBy.get(targetId))) { sender.sendMessage("§cOnly the player who sealed them can unseal them."); return true; }
            release(targetId, "§aYou have been released from the Prison Realm by " + owner.getName() + ".");
            owner.sendMessage("§aYou unsealed " + args[0] + "."); return true;
        }
        if (cmd.equals("player")) {
            if (args.length == 1 && args[0].equalsIgnoreCase("seal")) { listSealed(sender); return true; }
            sender.sendMessage("§cUsage: /player seal"); return true;
        }
        return false;
    }

    private boolean help(CommandSender s) { s.sendMessage("§5PrisonRealm commands: /prisonrealm give <player>, /prisonrealm status, /prisonrealm sealed, /freeze <player>, /seal [player], /unseal <player>, /player seal"); return true; }
    private boolean noPerm(CommandSender s) { s.sendMessage("§cYou don't have permission."); return true; }
    private void listSealed(CommandSender s) {
        if (sealedBy.isEmpty()) { s.sendMessage("§7No players are currently sealed."); return; }
        s.sendMessage("§5Sealed players:");
        for (Map.Entry<UUID, UUID> e : sealedBy.entrySet()) {
            String prisoner = Bukkit.getOfflinePlayer(e.getKey()).getName();
            String owner = Bukkit.getOfflinePlayer(e.getValue()).getName();
            s.sendMessage("§d- §f" + (prisoner == null ? e.getKey() : prisoner) + " §7(sealed by §f" + (owner == null ? e.getValue() : owner) + "§7)");
        }
    }
    private UUID findPlayerUUID(String name) { Player p = Bukkit.getPlayerExact(name); return p != null ? p.getUniqueId() : findOfflineUUID(name); }
    private UUID findOfflineUUID(String name) { for (org.bukkit.OfflinePlayer p : Bukkit.getOfflinePlayers()) if (p.getName() != null && p.getName().equalsIgnoreCase(name)) return p.getUniqueId(); return null; }

    private void release(UUID id, String message) {
        sealedBy.remove(id); pending.remove(id); frozenAt.remove(id);
        Player p = Bukkit.getPlayer(id);
        if (p != null) { p.removePotionEffect(PotionEffectType.SLOWNESS); p.removePotionEffect(PotionEffectType.JUMP_BOOST); p.sendMessage(message); }
        saveSealed();
    }

    @EventHandler public void onPlace(BlockPlaceEvent e) {
        if (!isRealm(e.getItemInHand())) return;
        Location loc = e.getBlockPlaced().getLocation();
        placedCores.put(blockKey(loc), e.getPlayer().getUniqueId());
        getServer().getScheduler().runTaskLater(this, () -> {
            // Core placement records are intentionally retained while the marker block remains.
            if (loc.getBlock().getType() == Material.AIR) placedCores.remove(blockKey(loc));
        }, 1L);
        e.getPlayer().sendMessage("§5Prison Realm placed. Freeze a target and use /seal <player> while they are within 1 block.");
    }

    @EventHandler public void onMove(PlayerMoveEvent e) {
        UUID id = e.getPlayer().getUniqueId();
        if (sealedBy.containsKey(id)) { e.setTo(e.getFrom()); return; }
        PendingSeal p = pending.get(id);
        if (p == null) return;
        if (p.expiresAt < System.currentTimeMillis()) {
            pending.remove(id); frozenAt.remove(id);
            e.getPlayer().removePotionEffect(PotionEffectType.SLOWNESS); e.getPlayer().removePotionEffect(PotionEffectType.JUMP_BOOST);
            Player owner = Bukkit.getPlayer(p.owner); if (owner != null) owner.sendMessage("§cSealing window expired.");
            return;
        }
        Location from = e.getFrom(), to = e.getTo();
        if (to != null && (from.getBlockX()!=to.getBlockX() || from.getBlockY()!=to.getBlockY() || from.getBlockZ()!=to.getBlockZ())) e.setTo(from);
    }

    @EventHandler public void onQuit(PlayerQuitEvent e) {
        // Keep pending and sealed records across disconnects; no owner transfer occurs.
        saveSealed();
    }

    @EventHandler public void onJoin(PlayerJoinEvent e) {
        UUID id = e.getPlayer().getUniqueId();
        if (sealedBy.containsKey(id)) {
            e.getPlayer().sendMessage("§5You are still sealed. Only the player who sealed you can use /unseal.");
            e.getPlayer().addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, Integer.MAX_VALUE, 10, false, false, false));
            e.getPlayer().addPotionEffect(new PotionEffect(PotionEffectType.JUMP_BOOST, Integer.MAX_VALUE, 250, false, false, false));
        } else if (pending.containsKey(id)) {
            PendingSeal p = pending.get(id);
            if (p.expiresAt < System.currentTimeMillis()) pending.remove(id);
            else {
                e.getPlayer().addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, (int)Math.max(1, (p.expiresAt-System.currentTimeMillis())/50), 10, false, false, false));
                e.getPlayer().addPotionEffect(new PotionEffect(PotionEffectType.JUMP_BOOST, (int)Math.max(1, (p.expiresAt-System.currentTimeMillis())/50), 250, false, false, false));
            }
        }
    }

    @EventHandler public void onBreak(BlockBreakEvent e) {
        String key = blockKey(e.getBlock().getLocation());
        UUID placer = placedCores.get(key);
        if (placer != null) {
            placedCores.remove(key);
            e.getPlayer().sendMessage("§7Prison Realm core removed.");
        }
    }

    private Location parseBlockKey(String key) {
        try { String[] a = key.split(":"); World w = Bukkit.getWorld(a[0]); if (w == null) return null; return new Location(w, Integer.parseInt(a[1]), Integer.parseInt(a[2]), Integer.parseInt(a[3])); } catch (Exception ex) { return null; }
    }
    private void loadSealed() {
        if (!getDataFolder().exists()) getDataFolder().mkdirs();
        var section = getConfig().getConfigurationSection("sealed");
        if (section != null) for (String prisoner : section.getKeys(false)) {
            try { sealedBy.put(UUID.fromString(prisoner), UUID.fromString(section.getString(prisoner))); } catch (Exception ignored) {}
        }
    }
    private void saveSealed() {
        for (String key : new HashSet<>(getConfig().getKeys(false))) if (key.equals("sealed")) getConfig().set("sealed", null);
        for (Map.Entry<UUID, UUID> e : sealedBy.entrySet()) getConfig().set("sealed." + e.getKey(), e.getValue().toString());
        saveConfig();
    }
    private record PendingSeal(UUID owner, long expiresAt) {}
}
