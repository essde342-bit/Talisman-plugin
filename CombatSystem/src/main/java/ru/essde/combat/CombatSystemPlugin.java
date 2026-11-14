package ru.essde.combat;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarStyle;
import org.bukkit.boss.BossBar;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.ExperienceOrb;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

public final class CombatSystemPlugin extends JavaPlugin implements Listener, TabExecutor {

    private static final long COMBAT_DURATION_MILLIS = 30_000L;
    private static final String RED = "§c";
    private static final String GREEN = "§a";

    private final Map<UUID, CombatState> combatants = new HashMap<>();
    private final Set<UUID> combatLogoutDeaths = new HashSet<>();
    private final Map<UUID, Selection> selections = new HashMap<>();
    private final Map<String, ProtectedRegion> protectedRegions = new HashMap<>();

    private BossBar disabledBossBar;
    private boolean pvpEnabled;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        pvpEnabled = getConfig().getBoolean("pvp-enabled", true);
        loadProtectedRegions();

        disabledBossBar = Bukkit.createBossBar(
                GREEN + "Пвп сейчас отключено",
                BarColor.GREEN,
                BarStyle.SOLID
        );

        Objects.requireNonNull(getCommand("pvp"), "Command 'pvp' is missing from plugin.yml")
                .setExecutor(this);
        Objects.requireNonNull(getCommand("pvp"), "Command 'pvp' is missing from plugin.yml")
                .setTabCompleter(this);
        Objects.requireNonNull(getCommand("pvpwand")).setExecutor(this);
        Objects.requireNonNull(getCommand("pvpzone")).setExecutor(this);
        Objects.requireNonNull(getCommand("pvpzone")).setTabCompleter(this);

        getServer().getPluginManager().registerEvents(this, this);
        getServer().getScheduler().runTaskTimer(this, this::updateCombatBars, 20L, 20L);

        if (!pvpEnabled) {
            getServer().getOnlinePlayers().forEach(disabledBossBar::addPlayer);
        }

        getLogger().info("CombatSystem enabled. PvP is " + (pvpEnabled ? "ON" : "OFF") + ".");
    }

    @Override
    public void onDisable() {
        clearAllCombatTags();
        if (disabledBossBar != null) {
            disabledBossBar.removeAll();
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPlayerDamage(EntityDamageByEntityEvent event) {
        if (pvpEnabled || !(event.getEntity() instanceof Player victim)) {
            return;
        }

        Player attacker = getResponsiblePlayer(event.getDamager());
        if (attacker != null && !attacker.getUniqueId().equals(victim.getUniqueId())) {
            // This also blocks arrows, tridents and other player-fired projectiles.
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void blockDamageInProtectedRegions(EntityDamageByEntityEvent event) {
        if (!(event.getEntity() instanceof Player victim)) return;
        Player attacker = getResponsiblePlayer(event.getDamager());
        if (attacker == null || attacker.getUniqueId().equals(victim.getUniqueId())) return;
        if (isInsideProtectedRegion(attacker.getLocation()) || isInsideProtectedRegion(victim.getLocation())) {
            event.setCancelled(true);
            attacker.sendMessage("§aPvP здесь запрещено: игрок находится в защищённой зоне.");
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void tagPlayersInCombat(EntityDamageByEntityEvent event) {
        if (!pvpEnabled || !(event.getEntity() instanceof Player victim)) {
            return;
        }

        Player attacker = getResponsiblePlayer(event.getDamager());
        if (attacker == null
                || attacker.getUniqueId().equals(victim.getUniqueId())
                || event.getFinalDamage() <= 0.0) {
            return;
        }

        // Both participants receive their own red, 30-second countdown bar.
        refreshCombatTag(attacker);
        refreshCombatTag(victim);
    }

    @EventHandler
    public void onWandUse(PlayerInteractEvent event) {
        if (event.getHand() != org.bukkit.inventory.EquipmentSlot.HAND || event.getClickedBlock() == null
                || !isSelectionWand(event.getItem())) return;
        Action action = event.getAction();
        if (action != Action.LEFT_CLICK_BLOCK && action != Action.RIGHT_CLICK_BLOCK) return;
        event.setCancelled(true);
        Player player = event.getPlayer();
        Selection selection = selections.computeIfAbsent(player.getUniqueId(), id -> new Selection());
        Block block = event.getClickedBlock();
        if (action == Action.LEFT_CLICK_BLOCK) {
            selection.pos1 = block.getLocation();
            player.sendMessage("§eПервая точка: §f" + block.getX() + ", " + block.getY() + ", " + block.getZ());
        } else {
            selection.pos2 = block.getLocation();
            player.sendMessage("§eВторая точка: §f" + block.getX() + ", " + block.getY() + ", " + block.getZ());
        }
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        if (!pvpEnabled) {
            disabledBossBar.addPlayer(event.getPlayer());
        }
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        CombatState state = combatants.remove(player.getUniqueId());
        if (state == null) {
            return;
        }

        state.bossBar.removeAll();
        punishCombatLogout(player);
    }

    @EventHandler
    public void onPlayerDeath(PlayerDeathEvent event) {
        Player player = event.getEntity();
        removeCombatTag(player);

        if (combatLogoutDeaths.remove(player.getUniqueId())) {
            event.setDeathMessage("§c" + player.getName() + " вышел во время PvP и погиб.");
        }
    }

    private Player getResponsiblePlayer(org.bukkit.entity.Entity damager) {
        if (damager instanceof Player player) {
            return player;
        }
        if (damager instanceof Projectile projectile && projectile.getShooter() instanceof Player player) {
            return player;
        }
        return null;
    }

    private void refreshCombatTag(Player player) {
        UUID uuid = player.getUniqueId();
        long now = System.currentTimeMillis();
        CombatState state = combatants.get(uuid);

        if (state == null) {
            BossBar bar = Bukkit.createBossBar("", BarColor.RED, BarStyle.SOLID);
            bar.addPlayer(player);
            state = new CombatState(bar);
            combatants.put(uuid, state);
        }

        state.expiresAtMillis = now + COMBAT_DURATION_MILLIS;
        updateCombatBar(player, state, now);
    }

    private void updateCombatBars() {
        long now = System.currentTimeMillis();
        Iterator<Map.Entry<UUID, CombatState>> iterator = combatants.entrySet().iterator();

        while (iterator.hasNext()) {
            Map.Entry<UUID, CombatState> entry = iterator.next();
            Player player = Bukkit.getPlayer(entry.getKey());
            CombatState state = entry.getValue();

            if (player == null || !player.isOnline() || now >= state.expiresAtMillis) {
                state.bossBar.removeAll();
                iterator.remove();

                if (player != null && player.isOnline() && now >= state.expiresAtMillis) {
                    player.sendMessage(GREEN + "Вы вышли из боя. Теперь можно безопасно выйти.");
                }
                continue;
            }

            updateCombatBar(player, state, now);
        }
    }

    private void updateCombatBar(Player player, CombatState state, long now) {
        long remaining = Math.max(0L, state.expiresAtMillis - now);
        long seconds = (remaining + 999L) / 1_000L;
        double progress = Math.max(0.0, Math.min(1.0, remaining / (double) COMBAT_DURATION_MILLIS));

        state.bossBar.setTitle(RED + "⚔ В БОЮ! Выход = смерть • " + seconds + " сек.");
        state.bossBar.setProgress(progress);
    }

    private void removeCombatTag(Player player) {
        CombatState state = combatants.remove(player.getUniqueId());
        if (state != null) {
            state.bossBar.removeAll();
        }
    }

    private void clearAllCombatTags() {
        for (CombatState state : combatants.values()) {
            state.bossBar.removeAll();
        }
        combatants.clear();
    }

    private void setPvpEnabled(boolean enabled) {
        pvpEnabled = enabled;
        getConfig().set("pvp-enabled", enabled);
        saveConfig();
        clearAllCombatTags();

        if (enabled) {
            disabledBossBar.removeAll();
        } else {
            disabledBossBar.setTitle(GREEN + "Пвп сейчас отключено");
            disabledBossBar.setColor(BarColor.GREEN);
            disabledBossBar.setProgress(1.0);
            getServer().getOnlinePlayers().forEach(disabledBossBar::addPlayer);
        }
    }

    private void punishCombatLogout(Player player) {
        Location dropLocation = player.getLocation().clone();
        List<ItemStack> drops = new ArrayList<>();
        collectItems(drops, player.getInventory().getStorageContents());
        collectItems(drops, player.getInventory().getArmorContents());

        ItemStack offHand = player.getInventory().getItemInOffHand();
        if (isRealItem(offHand)) {
            drops.add(offHand.clone());
        }

        // Drop inventory contents ourselves so keepInventory cannot save them.
        player.getInventory().clear();
        player.getInventory().setArmorContents(new ItemStack[4]);
        player.getInventory().setItemInOffHand(new ItemStack(Material.AIR));

        for (ItemStack item : drops) {
            dropLocation.getWorld().dropItemNaturally(dropLocation, item);
        }

        int totalExperience = player.getTotalExperience();
        if (totalExperience > 0) {
            ExperienceOrb orb = dropLocation.getWorld().spawn(dropLocation, ExperienceOrb.class);
            orb.setExperience(totalExperience);
        }
        player.setTotalExperience(0);
        player.setLevel(0);
        player.setExp(0.0f);

        combatLogoutDeaths.add(player.getUniqueId());
        // Setting health to zero kills the still-connected player during quit processing.
        player.setHealth(0.0);
        getLogger().info(player.getName() + " logged out during combat and was killed; inventory was dropped.");
    }

    private void collectItems(List<ItemStack> drops, ItemStack[] contents) {
        for (ItemStack item : contents) {
            if (isRealItem(item)) {
                drops.add(item.clone());
            }
        }
    }

    private boolean isRealItem(ItemStack item) {
        return item != null && !item.getType().isAir() && item.getAmount() > 0;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        String cmd = command.getName().toLowerCase();
        if (cmd.equals("pvpwand")) {
            if (!(sender instanceof Player player)) { sender.sendMessage("§cТолько игрок может получить топорик."); return true; }
            if (!player.hasPermission("pvpsystem.admin")) { player.sendMessage("§cНедостаточно прав."); return true; }
            ItemStack wand = new ItemStack(Material.WOODEN_AXE);
            ItemMeta meta = wand.getItemMeta();
            meta.setDisplayName("§6Топорик выделения PvP-зоны");
            meta.setLore(List.of("§7ЛКМ — точка 1", "§7ПКМ — точка 2"));
            wand.setItemMeta(meta);
            player.getInventory().addItem(wand).values().forEach(item -> player.getWorld().dropItemNaturally(player.getLocation(), item));
            player.sendMessage("§aТопорик выдан. ЛКМ — точка 1, ПКМ — точка 2.");
            return true;
        }
        if (cmd.equals("pvpzone")) {
            if (!sender.hasPermission("pvpsystem.admin")) { sender.sendMessage("§cНедостаточно прав."); return true; }
            if (args.length == 1 && args[0].equalsIgnoreCase("list")) {
                sender.sendMessage("§eЗащищённые зоны: §f" + (protectedRegions.isEmpty() ? "нет" : String.join(", ", protectedRegions.keySet())));
                return true;
            }
            if (args.length == 2 && args[0].equalsIgnoreCase("delete")) {
                String name = args[1].toLowerCase();
                if (protectedRegions.remove(name) == null) sender.sendMessage("§cЗона не найдена.");
                else { saveProtectedRegions(); sender.sendMessage("§aЗона '" + name + "' удалена."); }
                return true;
            }
            if (args.length == 2 && args[0].equalsIgnoreCase("create")) {
                if (!(sender instanceof Player player)) { sender.sendMessage("§cТолько игрок может создавать зоны."); return true; }
                String name = args[1].toLowerCase();
                if (!name.matches("[a-z0-9_-]{1,32}")) { player.sendMessage("§cНазвание: латиница, цифры, _ или -, до 32 символов."); return true; }
                Selection sel = selections.get(player.getUniqueId());
                if (sel == null || sel.pos1 == null || sel.pos2 == null) { player.sendMessage("§cСначала выдели две точки топориком /pvpwand."); return true; }
                if (!sel.pos1.getWorld().equals(sel.pos2.getWorld())) { player.sendMessage("§cТочки должны быть в одном мире."); return true; }
                if (protectedRegions.containsKey(name)) { player.sendMessage("§cТакая зона уже существует."); return true; }
                protectedRegions.put(name, new ProtectedRegion(sel.pos1.getWorld().getName(),
                    Math.min(sel.pos1.getBlockX(), sel.pos2.getBlockX()), Math.min(sel.pos1.getBlockY(), sel.pos2.getBlockY()), Math.min(sel.pos1.getBlockZ(), sel.pos2.getBlockZ()),
                    Math.max(sel.pos1.getBlockX(), sel.pos2.getBlockX()), Math.max(sel.pos1.getBlockY(), sel.pos2.getBlockY()), Math.max(sel.pos1.getBlockZ(), sel.pos2.getBlockZ())));
                saveProtectedRegions();
                player.sendMessage("§aЗона '" + name + "' создана. PvP внутри отключено.");
                return true;
            }
            sender.sendMessage("§e/pvpzone create <имя> | /pvpzone delete <имя> | /pvpzone list");
            return true;
        }
        if (args.length != 1 || (!args[0].equalsIgnoreCase("on") && !args[0].equalsIgnoreCase("off"))) {
            sender.sendMessage("§cИспользование: /pvp <on|off>"); return true;
        }
        if (!sender.hasPermission("pvpsystem.admin")) { sender.sendMessage("§cНедостаточно прав."); return true; }
        boolean enable = args[0].equalsIgnoreCase("on");
        if (enable == pvpEnabled) { sender.sendMessage(enable ? GREEN + "PvP уже включено." : GREEN + "PvP уже отключено."); return true; }
        setPvpEnabled(enable);
        sender.sendMessage(enable ? GREEN + "PvP включено. Боевая система активна." : GREEN + "PvP отключено. Урон от других игроков заблокирован.");
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (command.getName().equalsIgnoreCase("pvpzone")) {
            if (args.length == 1) return List.of("create", "delete", "list").stream().filter(v -> v.startsWith(args[0].toLowerCase())).toList();
            if (args.length == 2 && args[0].equalsIgnoreCase("delete")) return protectedRegions.keySet().stream().filter(v -> v.startsWith(args[1].toLowerCase())).toList();
            return List.of();
        }
        if (args.length != 1) return List.of();
        String prefix = args[0].toLowerCase();
        return List.of("on", "off").stream().filter(v -> v.startsWith(prefix)).toList();
    }

    private boolean isSelectionWand(ItemStack item) {
        return item != null && item.getType() == Material.WOODEN_AXE && item.hasItemMeta()
                && item.getItemMeta().hasDisplayName()
                && "§6Топорик выделения PvP-зоны".equals(item.getItemMeta().getDisplayName());
    }

    private boolean isInsideProtectedRegion(Location loc) {
        if (loc.getWorld() == null) return false;
        int x = loc.getBlockX(), y = loc.getBlockY(), z = loc.getBlockZ();
        for (ProtectedRegion r : protectedRegions.values()) {
            if (r.world.equals(loc.getWorld().getName()) && x >= r.minX && x <= r.maxX
                    && y >= r.minY && y <= r.maxY && z >= r.minZ && z <= r.maxZ) return true;
        }
        return false;
    }

    private void loadProtectedRegions() {
        protectedRegions.clear();
        for (Map<?, ?> map : getConfig().getMapList("protected-regions")) {
            try {
                String name = String.valueOf(map.get("name")).toLowerCase();
                protectedRegions.put(name, new ProtectedRegion(String.valueOf(map.get("world")),
                    ((Number) map.get("min-x")).intValue(), ((Number) map.get("min-y")).intValue(), ((Number) map.get("min-z")).intValue(),
                    ((Number) map.get("max-x")).intValue(), ((Number) map.get("max-y")).intValue(), ((Number) map.get("max-z")).intValue()));
            } catch (Exception ex) { getLogger().warning("Не удалось загрузить защищённую зону: " + ex.getMessage()); }
        }
    }

    private void saveProtectedRegions() {
        List<Map<String, Object>> list = new ArrayList<>();
        for (Map.Entry<String, ProtectedRegion> entry : protectedRegions.entrySet()) {
            ProtectedRegion r = entry.getValue();
            Map<String, Object> map = new HashMap<>();
            map.put("name", entry.getKey()); map.put("world", r.world);
            map.put("min-x", r.minX); map.put("min-y", r.minY); map.put("min-z", r.minZ);
            map.put("max-x", r.maxX); map.put("max-y", r.maxY); map.put("max-z", r.maxZ);
            list.add(map);
        }
        getConfig().set("protected-regions", list);
        saveConfig();
    }

    private static final class Selection { private Location pos1; private Location pos2; }
    private static final class ProtectedRegion {
        private final String world;
        private final int minX, minY, minZ, maxX, maxY, maxZ;
        private ProtectedRegion(String world, int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
            this.world = world; this.minX = minX; this.minY = minY; this.minZ = minZ;
            this.maxX = maxX; this.maxY = maxY; this.maxZ = maxZ;
        }
    }

    private static final class CombatState {
        private final BossBar bossBar;
        private long expiresAtMillis;

        private CombatState(BossBar bossBar) {
            this.bossBar = bossBar;
        }
    }
}
