package ru.essde.combat;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
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

    private BossBar disabledBossBar;
    private boolean pvpEnabled;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        pvpEnabled = getConfig().getBoolean("pvp-enabled", true);

        disabledBossBar = Bukkit.createBossBar(
                GREEN + "Пвп сейчас отключено",
                BarColor.GREEN,
                BarStyle.SOLID
        );

        Objects.requireNonNull(getCommand("pvp"), "Command 'pvp' is missing from plugin.yml")
                .setExecutor(this);
        Objects.requireNonNull(getCommand("pvp"), "Command 'pvp' is missing from plugin.yml")
                .setTabCompleter(this);

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
    public boolean onCommand(
            CommandSender sender,
            Command command,
            String label,
            String[] args
    ) {
        if (args.length != 1
                || (!args[0].equalsIgnoreCase("on") && !args[0].equalsIgnoreCase("off"))) {
            sender.sendMessage("§cИспользование: /pvp <on|off>");
            return true;
        }

        boolean enable = args[0].equalsIgnoreCase("on");
        if (enable == pvpEnabled) {
            sender.sendMessage(enable
                    ? GREEN + "PvP уже включено."
                    : GREEN + "PvP уже отключено.");
            return true;
        }

        setPvpEnabled(enable);
        sender.sendMessage(enable
                ? GREEN + "PvP включено. Боевая система активна."
                : GREEN + "PvP отключено. Урон от других игроков заблокирован.");
        return true;
    }

    @Override
    public List<String> onTabComplete(
            CommandSender sender,
            Command command,
            String alias,
            String[] args
    ) {
        if (args.length != 1) {
            return List.of();
        }

        String prefix = args[0].toLowerCase();
        return List.of("on", "off").stream()
                .filter(value -> value.startsWith(prefix))
                .toList();
    }

    private static final class CombatState {
        private final BossBar bossBar;
        private long expiresAtMillis;

        private CombatState(BossBar bossBar) {
            this.bossBar = bossBar;
        }
    }
}
