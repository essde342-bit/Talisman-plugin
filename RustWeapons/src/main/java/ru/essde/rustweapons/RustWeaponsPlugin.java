package ru.essde.rustweapons;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.FluidCollisionMode;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

public final class RustWeaponsPlugin extends JavaPlugin implements Listener, CommandExecutor, TabCompleter {
    private NamespacedKey weaponKey;
    private NamespacedKey loadedKey;
    private NamespacedKey ammoKey;

    private final Map<UUID, Long> nextShotAt = new HashMap<>();
    private final Map<UUID, BukkitTask> reloadTasks = new HashMap<>();
    private final Random random = new Random();

    @Override
    public void onEnable() {
        saveDefaultConfig();
        weaponKey = new NamespacedKey(this, "weapon_id");
        loadedKey = new NamespacedKey(this, "loaded_ammo");
        ammoKey = new NamespacedKey(this, "ammo_id");
        Bukkit.getPluginManager().registerEvents(this, this);
        if (getCommand("rw") != null) {
            getCommand("rw").setExecutor(this);
            getCommand("rw").setTabCompleter(this);
        }
        getLogger().info("RustWeapons enabled for Paper 1.21.4.");
    }

    @Override
    public void onDisable() {
        for (BukkitTask task : reloadTasks.values()) task.cancel();
        reloadTasks.clear();
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0 || args[0].equalsIgnoreCase("list")) {
            sender.sendMessage(Component.text("RustWeapons — оружие:", NamedTextColor.GOLD));
            for (Weapon weapon : Weapon.values()) {
                sender.sendMessage(Component.text(" • " + weapon.id + " — " + weapon.displayName, NamedTextColor.GRAY));
            }
            sender.sendMessage(Component.text("/rw give <игрок> <оружие> [кол-во] | /rw ammo <игрок> <тип> [кол-во] | /rw reload", NamedTextColor.YELLOW));
            return true;
        }

        if (args[0].equalsIgnoreCase("reload")) {
            if (!(sender instanceof Player player)) {
                sender.sendMessage("Эту команду может использовать только игрок.");
                return true;
            }
            startReload(player);
            return true;
        }

        if (!sender.hasPermission("rustweapons.admin")) {
            sender.sendMessage(Component.text("Недостаточно прав.", NamedTextColor.RED));
            return true;
        }

        if (args[0].equalsIgnoreCase("give") && args.length >= 3) {
            Player target = Bukkit.getPlayerExact(args[1]);
            Weapon weapon = Weapon.byId(args[2]);
            int amount = parseAmount(args.length >= 4 ? args[3] : "1");
            if (target == null || weapon == null || amount < 1 || amount > 64) {
                sender.sendMessage(Component.text("Использование: /rw give <игрок> <semi|ak|thompson|smg|pump|double|bolt|m92> [1-64]", NamedTextColor.RED));
                return true;
            }
            for (int i = 0; i < amount; i++) giveOrDrop(target, createWeapon(weapon));
            sender.sendMessage(Component.text("Выдано " + amount + " × " + weapon.displayName + " игроку " + target.getName(), NamedTextColor.GREEN));
            return true;
        }

        if (args[0].equalsIgnoreCase("ammo") && args.length >= 3) {
            Player target = Bukkit.getPlayerExact(args[1]);
            AmmoType type = AmmoType.byId(args[2]);
            int amount = parseAmount(args.length >= 4 ? args[3] : "32");
            if (target == null || type == null || amount < 1 || amount > 2304) {
                sender.sendMessage(Component.text("Использование: /rw ammo <игрок> <rifle|pistol|shell> [1-2304]", NamedTextColor.RED));
                return true;
            }
            giveOrDrop(target, createAmmo(type, amount));
            sender.sendMessage(Component.text("Выдано " + amount + " × " + type.displayName + " игроку " + target.getName(), NamedTextColor.GREEN));
            return true;
        }

        sender.sendMessage(Component.text("Использование: /rw list | give | ammo | reload", NamedTextColor.YELLOW));
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) return partial(List.of("list", "give", "ammo", "reload"), args[0]);
        if (args.length == 2 && (args[0].equalsIgnoreCase("give") || args[0].equalsIgnoreCase("ammo"))) {
            List<String> names = new ArrayList<>();
            for (Player player : Bukkit.getOnlinePlayers()) names.add(player.getName());
            return partial(names, args[1]);
        }
        if (args.length == 3 && args[0].equalsIgnoreCase("give")) {
            List<String> ids = new ArrayList<>();
            for (Weapon weapon : Weapon.values()) ids.add(weapon.id);
            return partial(ids, args[2]);
        }
        if (args.length == 3 && args[0].equalsIgnoreCase("ammo")) return partial(List.of("rifle", "pistol", "shell"), args[2]);
        return List.of();
    }

    private List<String> partial(List<String> values, String token) {
        String lower = token.toLowerCase();
        return values.stream().filter(value -> value.toLowerCase().startsWith(lower)).toList();
    }

    private int parseAmount(String value) {
        try { return Integer.parseInt(value); }
        catch (NumberFormatException ignored) { return -1; }
    }

    private ItemStack createWeapon(Weapon weapon) {
        ItemStack item = new ItemStack(Material.PAPER);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text(weapon.displayName, NamedTextColor.GOLD).decoration(TextDecoration.ITALIC, false));
        meta.setItemModel(new NamespacedKey(this, weapon.id));
        meta.setUnbreakable(true);
        meta.setMaxStackSize(1);
        meta.addItemFlags(ItemFlag.HIDE_UNBREAKABLE);
        PersistentDataContainer data = meta.getPersistentDataContainer();
        data.set(weaponKey, PersistentDataType.STRING, weapon.id);
        data.set(loadedKey, PersistentDataType.INTEGER, weapon.magazine);
        updateWeaponLore(meta, weapon, weapon.magazine);
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack createAmmo(AmmoType type, int amount) {
        ItemStack item = new ItemStack(Material.GUNPOWDER, amount);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text(type.displayName, NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false));
        meta.lore(List.of(Component.text("Боеприпасы RustWeapons", NamedTextColor.DARK_GRAY).decoration(TextDecoration.ITALIC, false)));
        meta.getPersistentDataContainer().set(ammoKey, PersistentDataType.STRING, type.id);
        item.setItemMeta(meta);
        return item;
    }

    private void updateWeaponLore(ItemMeta meta, Weapon weapon, int loaded) {
        meta.lore(List.of(
                Component.text("Патроны: " + loaded + "/" + weapon.magazine, NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false),
                Component.text("Тип: " + weapon.ammo.displayName, NamedTextColor.DARK_GRAY).decoration(TextDecoration.ITALIC, false),
                Component.text("ПКМ — выстрел | /rw reload — перезарядка", NamedTextColor.YELLOW).decoration(TextDecoration.ITALIC, false)
        ));
    }

    private Weapon getWeapon(ItemStack item) {
        if (item == null || item.getType().isAir() || !item.hasItemMeta()) return null;
        String id = item.getItemMeta().getPersistentDataContainer().get(weaponKey, PersistentDataType.STRING);
        return id == null ? null : Weapon.byId(id);
    }

    private int getLoaded(ItemStack item) {
        if (item == null || !item.hasItemMeta()) return 0;
        Integer loaded = item.getItemMeta().getPersistentDataContainer().get(loadedKey, PersistentDataType.INTEGER);
        return loaded == null ? 0 : loaded;
    }

    private void setLoaded(ItemStack item, Weapon weapon, int amount) {
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return;
        int safeAmount = Math.max(0, Math.min(weapon.magazine, amount));
        meta.getPersistentDataContainer().set(loadedKey, PersistentDataType.INTEGER, safeAmount);
        updateWeaponLore(meta, weapon, safeAmount);
        item.setItemMeta(meta);
    }

    private void giveOrDrop(Player player, ItemStack item) {
        Map<Integer, ItemStack> leftovers = player.getInventory().addItem(item);
        for (ItemStack leftover : leftovers.values()) player.getWorld().dropItemNaturally(player.getLocation(), leftover);
    }

    private AmmoType getAmmoType(ItemStack item) {
        if (item == null || item.getType().isAir() || !item.hasItemMeta()) return null;
        String id = item.getItemMeta().getPersistentDataContainer().get(ammoKey, PersistentDataType.STRING);
        return id == null ? null : AmmoType.byId(id);
    }

    private int countAmmo(Player player, AmmoType type) {
        int total = 0;
        for (ItemStack item : player.getInventory().getStorageContents()) if (getAmmoType(item) == type) total += item.getAmount();
        return total;
    }

    private int consumeAmmo(Player player, AmmoType type, int requested) {
        int remaining = requested;
        for (int slot = 0; slot < 36 && remaining > 0; slot++) {
            ItemStack item = player.getInventory().getItem(slot);
            if (getAmmoType(item) != type) continue;
            int take = Math.min(item.getAmount(), remaining);
            int left = item.getAmount() - take;
            if (left <= 0) player.getInventory().setItem(slot, null);
            else item.setAmount(left);
            remaining -= take;
        }
        return requested - remaining;
    }

    private void startReload(Player player) {
        UUID id = player.getUniqueId();
        if (reloadTasks.containsKey(id)) {
            player.sendMessage(Component.text("Перезарядка уже идёт.", NamedTextColor.YELLOW));
            return;
        }
        ItemStack held = player.getInventory().getItemInMainHand();
        Weapon weapon = getWeapon(held);
        if (weapon == null) {
            player.sendMessage(Component.text("Возьми оружие в основную руку.", NamedTextColor.RED));
            return;
        }
        int loaded = getLoaded(held);
        int needed = weapon.magazine - loaded;
        if (needed <= 0) {
            player.sendMessage(Component.text("Магазин уже полный.", NamedTextColor.YELLOW));
            return;
        }
        if (countAmmo(player, weapon.ammo) <= 0) {
            player.sendMessage(Component.text("Нет боеприпасов: " + weapon.ammo.displayName, NamedTextColor.RED));
            return;
        }

        player.sendMessage(Component.text("Перезарядка " + weapon.displayName + "...", NamedTextColor.YELLOW));
        player.getWorld().playSound(player.getLocation(),
                "rustweapons:weapon." + weapon.id + ".reload",
                SoundCategory.PLAYERS, 0.9f, 1.0f);
        BukkitTask task = Bukkit.getScheduler().runTaskLater(this, () -> {
            reloadTasks.remove(id);
            if (!player.isOnline()) return;
            ItemStack current = player.getInventory().getItemInMainHand();
            if (getWeapon(current) != weapon) {
                player.sendMessage(Component.text("Перезарядка отменена: оружие больше не в руке.", NamedTextColor.RED));
                return;
            }
            int currentLoaded = getLoaded(current);
            int consumed = consumeAmmo(player, weapon.ammo, weapon.magazine - currentLoaded);
            if (consumed <= 0) {
                player.sendMessage(Component.text("Боеприпасов больше нет. Перезарядка отменена.", NamedTextColor.RED));
                return;
            }
            setLoaded(current, weapon, currentLoaded + consumed);
            player.getInventory().setItemInMainHand(current);
            player.playSound(player.getLocation(), Sound.ITEM_CROSSBOW_LOADING_END, 0.8f, 1.0f);
            player.sendMessage(Component.text("Готово: " + (currentLoaded + consumed) + "/" + weapon.magazine, NamedTextColor.GREEN));
        }, weapon.reloadTicks);
        reloadTasks.put(id, task);
    }

    @EventHandler
    public void onWeaponUse(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND) return;
        if (event.getAction() != Action.RIGHT_CLICK_AIR && event.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        ItemStack item = event.getItem();
        Weapon weapon = getWeapon(item);
        if (weapon == null) return;

        event.setCancelled(true);
        Player player = event.getPlayer();
        UUID playerId = player.getUniqueId();
        long now = System.currentTimeMillis();
        if (now < nextShotAt.getOrDefault(playerId, 0L)) return;
        nextShotAt.put(playerId, now + weapon.cooldownTicks * 50L);

        int loaded = getLoaded(item);
        if (loaded <= 0) {
            player.playSound(player.getLocation(), Sound.BLOCK_DISPENSER_FAIL, 0.5f, 0.8f);
            player.sendMessage(Component.text("Нет патронов. Используй /rw reload.", NamedTextColor.RED));
            return;
        }
        setLoaded(item, weapon, loaded - 1);
        player.getInventory().setItemInMainHand(item);
        player.swingMainHand();
        fire(player, weapon);
    }

    private void fire(Player player, Weapon weapon) {
        World world = player.getWorld();
        Vector baseDirection = player.getEyeLocation().getDirection().normalize();
        Vector origin = player.getEyeLocation().toVector().add(baseDirection.clone().multiply(0.35));
        world.playSound(player.getLocation(),
                "rustweapons:weapon." + weapon.id + ".shot",
                SoundCategory.PLAYERS, 1.0f, weapon.pitch);
        world.spawnParticle(Particle.SMOKE, origin.getX(), origin.getY(), origin.getZ(), 2, 0.025, 0.025, 0.025, 0.005);

        for (int pellet = 0; pellet < weapon.pellets; pellet++) {
            Vector direction = baseDirection.clone();
            if (weapon.spread > 0) {
                direction.add(new Vector(random.nextGaussian() * weapon.spread,
                        random.nextGaussian() * weapon.spread,
                        random.nextGaussian() * weapon.spread)).normalize();
            }
            RayTraceResult trace = world.rayTrace(player.getEyeLocation(), direction, weapon.range,
                    FluidCollisionMode.NEVER, false, 0.12,
                    entity -> entity instanceof LivingEntity && !entity.getUniqueId().equals(player.getUniqueId()));
            Vector end = trace == null
                    ? player.getEyeLocation().toVector().add(direction.clone().multiply(weapon.range))
                    : trace.getHitPosition();
            drawTracer(world, origin, end);
            if (trace != null && trace.getHitEntity() instanceof LivingEntity target) {
                target.damage(weapon.damage, player);
                Vector hit = trace.getHitPosition();
                world.spawnParticle(Particle.CRIT, hit.getX(), hit.getY(), hit.getZ(), 5, 0.12, 0.12, 0.12, 0.08);
            }
        }
    }

    private void drawTracer(World world, Vector start, Vector end) {
        Vector delta = end.clone().subtract(start);
        double distance = delta.length();
        if (distance < 0.01) return;
        Vector step = delta.normalize().multiply(0.55);
        Vector point = start.clone();
        for (double travelled = 0; travelled < distance; travelled += 0.55) {
            world.spawnParticle(Particle.CRIT, point.getX(), point.getY(), point.getZ(), 1, 0, 0, 0, 0);
            point.add(step);
        }
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        String url = getConfig().getString("resource-pack.url", "").trim();
        if (url.isEmpty()) return;
        Bukkit.getScheduler().runTaskLater(this, () -> {
            if (event.getPlayer().isOnline()) event.getPlayer().setResourcePack(url);
        }, 20L);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        UUID id = event.getPlayer().getUniqueId();
        nextShotAt.remove(id);
        BukkitTask task = reloadTasks.remove(id);
        if (task != null) task.cancel();
    }

    private enum AmmoType {
        RIFLE("rifle", "Винтовочные патроны 5.56"),
        PISTOL("pistol", "Пистолетные патроны"),
        SHELL("shell", "Дробовые патроны 12 калибра");
        private final String id;
        private final String displayName;
        AmmoType(String id, String displayName) { this.id = id; this.displayName = displayName; }
        private static AmmoType byId(String id) {
            for (AmmoType type : values()) if (type.id.equalsIgnoreCase(id)) return type;
            return null;
        }
    }

    private enum Weapon {
        SEMI("semi", "Semi-Automatic Rifle", AmmoType.RIFLE, 16, 8.0, 7, 32, 90, 1, 0.004, 1.10f),
        AK("ak", "Assault Rifle", AmmoType.RIFLE, 30, 5.2, 3, 42, 78, 1, 0.018, 0.92f),
        THOMPSON("thompson", "Thompson", AmmoType.PISTOL, 20, 4.5, 4, 38, 58, 1, 0.025, 1.00f),
        SMG("smg", "Custom SMG", AmmoType.PISTOL, 24, 3.7, 2, 35, 48, 1, 0.032, 1.18f),
        PUMP("pump", "Pump Shotgun", AmmoType.SHELL, 6, 1.35, 17, 40, 22, 8, 0.105, 0.78f),
        DOUBLE("double", "Double Barrel", AmmoType.SHELL, 2, 1.45, 20, 32, 17, 12, 0.17, 0.70f),
        BOLT("bolt", "Bolt Action Rifle", AmmoType.RIFLE, 4, 17.0, 27, 50, 120, 1, 0.001, 0.62f),
        M92("m92", "M92 Pistol", AmmoType.PISTOL, 15, 5.0, 5, 35, 45, 1, 0.012, 1.08f);
        private final String id;
        private final String displayName;
        private final AmmoType ammo;
        private final int magazine;
        private final double damage;
        private final int cooldownTicks;
        private final int reloadTicks;
        private final double range;
        private final int pellets;
        private final double spread;
        private final float pitch;
        Weapon(String id, String displayName, AmmoType ammo, int magazine, double damage, int cooldownTicks, int reloadTicks,
               double range, int pellets, double spread, float pitch) {
            this.id = id; this.displayName = displayName; this.ammo = ammo; this.magazine = magazine;
            this.damage = damage; this.cooldownTicks = cooldownTicks; this.reloadTicks = reloadTicks; this.range = range;
            this.pellets = pellets; this.spread = spread; this.pitch = pitch;
        }
        private static Weapon byId(String id) {
            for (Weapon weapon : values()) if (weapon.id.equalsIgnoreCase(id)) return weapon;
            return null;
        }
    }
}
