package ru.essde.rtmap;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

public final class RTMapPlugin extends JavaPlugin implements Listener, TabExecutor {

    private static final int INVENTORY_SIZE = 54;
    private static final int MAP_WIDTH = 9;
    private static final int MAP_HEIGHT = 5;
    private static final int PLAYER_MARKER_SLOT = 2 * MAP_WIDTH + 4;

    private final List<MapPoint> points = new ArrayList<>();
    private File pointsFile;
    private BukkitTask refreshTask;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        pointsFile = new File(getDataFolder(), "points.yml");
        loadPoints();

        registerCommand("rtmap");
        registerCommand("rtmap_add");
        registerCommand("rtmap_remove");
        registerCommand("rtmap_list");
        registerCommand("rtmap_reload");

        getServer().getPluginManager().registerEvents(this, this);

        long refreshTicks = Math.max(2L, getConfig().getLong("refresh-ticks", 10L));
        refreshTask = Bukkit.getScheduler().runTaskTimer(this, this::refreshOpenMaps,
                refreshTicks, refreshTicks);

        getLogger().info("RTMap enabled. Loaded " + points.size() + " point(s).");
    }

    @Override
    public void onDisable() {
        if (refreshTask != null) {
            refreshTask.cancel();
        }
        savePoints();
    }

    private void registerCommand(String name) {
        Objects.requireNonNull(getCommand(name), "Command '" + name + "' is missing from plugin.yml")
                .setExecutor(this);
        Objects.requireNonNull(getCommand(name)).setTabCompleter(this);
    }

    private void loadPoints() {
        points.clear();
        if (!getDataFolder().exists() && !getDataFolder().mkdirs()) {
            getLogger().warning("Could not create plugin data folder.");
            return;
        }
        if (!pointsFile.exists()) {
            try {
                if (!pointsFile.createNewFile()) {
                    getLogger().warning("Could not create points.yml.");
                    return;
                }
            } catch (IOException exception) {
                getLogger().severe("Could not create points.yml: " + exception.getMessage());
                return;
            }
        }

        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(pointsFile);
        if (yaml.getConfigurationSection("points") == null) {
            return;
        }

        for (String id : yaml.getConfigurationSection("points").getKeys(false)) {
            String path = "points." + id;
            String name = yaml.getString(path + ".name");
            String world = yaml.getString(path + ".world");
            if (name == null || world == null
                    || !yaml.contains(path + ".x") || !yaml.contains(path + ".z")) {
                getLogger().warning("Skipping incomplete point entry: " + id);
                continue;
            }
            points.add(new MapPoint(name, world,
                    yaml.getInt(path + ".x"), yaml.getInt(path + ".z")));
        }
    }

    private void savePoints() {
        if (pointsFile == null) {
            return;
        }
        YamlConfiguration yaml = new YamlConfiguration();
        for (int i = 0; i < points.size(); i++) {
            MapPoint point = points.get(i);
            String path = "points." + i;
            yaml.set(path + ".name", point.name());
            yaml.set(path + ".world", point.world());
            yaml.set(path + ".x", point.x());
            yaml.set(path + ".z", point.z());
        }
        try {
            yaml.save(pointsFile);
        } catch (IOException exception) {
            getLogger().severe("Could not save points.yml: " + exception.getMessage());
        }
    }

    private void refreshOpenMaps() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            Inventory top = player.getOpenInventory().getTopInventory();
            if (top.getHolder() instanceof MapHolder) {
                renderMap(player, top);
            }
        }
    }

    private void openMap(Player player) {
        MapHolder holder = new MapHolder();
        Inventory inventory = Bukkit.createInventory(holder, INVENTORY_SIZE,
                ChatColor.BLACK + "RT MAP");
        holder.setInventory(inventory);
        renderMap(player, inventory);
        player.openInventory(inventory);
    }

    private void renderMap(Player player, Inventory inventory) {
        ItemStack background = createItem(Material.BLACK_STAINED_GLASS_PANE,
                " ", List.of());
        for (int slot = 0; slot < INVENTORY_SIZE; slot++) {
            inventory.setItem(slot, background);
        }

        World world = player.getWorld();
        double centerX = player.getLocation().getX();
        double centerZ = player.getLocation().getZ();
        double radius = Math.max(64.0, getConfig().getDouble("map-radius", 1500.0));

        Map<Integer, List<MapPoint>> visiblePoints = new HashMap<>();
        for (MapPoint point : points) {
            if (!point.world().equals(world.getName())) {
                continue;
            }

            double deltaX = point.x() - centerX;
            double deltaZ = point.z() - centerZ;
            if (Math.abs(deltaX) > radius || Math.abs(deltaZ) > radius) {
                continue;
            }

            int column = (int) Math.floor(((deltaX + radius) / (radius * 2.0)) * MAP_WIDTH);
            int row = (int) Math.floor(((deltaZ + radius) / (radius * 2.0)) * MAP_HEIGHT);
            column = Math.max(0, Math.min(MAP_WIDTH - 1, column));
            row = Math.max(0, Math.min(MAP_HEIGHT - 1, row));
            int slot = row * MAP_WIDTH + column;
            visiblePoints.computeIfAbsent(slot, ignored -> new ArrayList<>()).add(point);
        }

        for (Map.Entry<Integer, List<MapPoint>> entry : visiblePoints.entrySet()) {
            if (entry.getKey() == PLAYER_MARKER_SLOT) {
                continue;
            }
            inventory.setItem(entry.getKey(), createPointMarker(entry.getValue(), centerX, centerZ));
        }

        List<MapPoint> underPlayer = visiblePoints.getOrDefault(PLAYER_MARKER_SLOT, List.of());
        inventory.setItem(PLAYER_MARKER_SLOT, createPlayerMarker(player, underPlayer));

        String direction = directionArrow(player.getLocation().getYaw());
        inventory.setItem(49, createItem(Material.COMPASS,
                ChatColor.AQUA + "Вы здесь: " + ChatColor.WHITE
                        + player.getLocation().getBlockX() + ", "
                        + player.getLocation().getBlockZ(),
                List.of(
                        ChatColor.GRAY + "Направление: " + ChatColor.WHITE + direction,
                        ChatColor.GRAY + "Мир: " + ChatColor.WHITE + world.getName(),
                        ChatColor.DARK_GRAY + "Синяя точка — ваше положение"
                )));

        inventory.setItem(53, createItem(Material.PAPER,
                ChatColor.WHITE + "RT-точки",
                List.of(
                        ChatColor.GRAY + "Белые точки — локации RT",
                        ChatColor.GRAY + "Наведите курсор на точку,",
                        ChatColor.GRAY + "чтобы увидеть название и координаты.",
                        ChatColor.DARK_GRAY + "Радиус: " + (int) radius + " блоков"
                )));
    }

    private ItemStack createPointMarker(List<MapPoint> mapped, double playerX, double playerZ) {
        List<MapPoint> ordered = mapped.stream()
                .sorted(Comparator.comparing(MapPoint::name, String.CASE_INSENSITIVE_ORDER))
                .toList();

        List<String> lore = new ArrayList<>();
        for (MapPoint point : ordered) {
            lore.add(ChatColor.GRAY + point.name());
            lore.add(ChatColor.DARK_GRAY + "X: " + point.x() + "  Z: " + point.z());
            if (lore.size() >= 9) {
                lore.add(ChatColor.DARK_GRAY + "и другие точки...");
                break;
            }
        }
        if (ordered.size() == 1) {
            MapPoint point = ordered.get(0);
            double distance = Math.sqrt(Math.pow(point.x() - playerX, 2)
                    + Math.pow(point.z() - playerZ, 2));
            lore.add(ChatColor.GRAY + "Расстояние: " + (int) distance + " блоков");
            return createItem(Material.WHITE_DYE,
                    ChatColor.WHITE + "● " + point.name(), lore);
        }

        lore.add(0, ChatColor.GRAY + "В этой ячейке: " + ordered.size());
        return createItem(Material.WHITE_DYE,
                ChatColor.WHITE + "● " + ordered.size() + " RT-точки", lore);
    }

    private ItemStack createPlayerMarker(Player player, List<MapPoint> nearbyPoints) {
        List<String> lore = new ArrayList<>();
        lore.add(ChatColor.GRAY + "X: " + player.getLocation().getBlockX()
                + "  Z: " + player.getLocation().getBlockZ());
        lore.add(ChatColor.GRAY + "Мир: " + player.getWorld().getName());
        lore.add(ChatColor.GRAY + "Смотрите: " + ChatColor.AQUA
                + directionArrow(player.getLocation().getYaw()));
        if (!nearbyPoints.isEmpty()) {
            lore.add(ChatColor.YELLOW + "RT-точка рядом:");
            for (MapPoint point : nearbyPoints) {
                lore.add(ChatColor.WHITE + point.name() + ChatColor.GRAY
                        + " (" + point.x() + ", " + point.z() + ")");
            }
        }
        return createItem(Material.BLUE_DYE,
                ChatColor.AQUA + "● Вы здесь", lore);
    }

    private String directionArrow(float yaw) {
        String[] directions = {"↑", "↗", "→", "↘", "↓", "↙", "←", "↖"};
        int index = Math.floorMod((int) Math.floor((yaw + 202.5f) / 45.0f), 8);
        return directions[index];
    }

    private ItemStack createItem(Material material, String name, List<String> lore) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        meta.setDisplayName(name);
        if (!lore.isEmpty()) {
            meta.setLore(lore);
        }
        item.setItemMeta(meta);
        return item;
    }

    @EventHandler
    public void onMapClick(InventoryClickEvent event) {
        if (event.getView().getTopInventory().getHolder() instanceof MapHolder) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onMapDrag(InventoryDragEvent event) {
        if (event.getView().getTopInventory().getHolder() instanceof MapHolder) {
            event.setCancelled(true);
        }
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        String name = command.getName().toLowerCase(Locale.ROOT);

        switch (name) {
            case "rtmap" -> {
                if (!(sender instanceof Player player)) {
                    sender.sendMessage(ChatColor.RED + "Эту команду может использовать только игрок.");
                    return true;
                }
                openMap(player);
                return true;
            }
            case "rtmap_add" -> {
                if (!sender.hasPermission("rtmap.admin")) {
                    sender.sendMessage(ChatColor.RED + "Недостаточно прав.");
                    return true;
                }
                if (!(sender instanceof Player player)) {
                    sender.sendMessage(ChatColor.RED + "Только игрок может добавить точку со своей позиции.");
                    return true;
                }
                if (args.length == 0) {
                    player.sendMessage(ChatColor.RED + "Использование: /rtmap_add <название>");
                    return true;
                }
                String pointName = String.join(" ", args).trim();
                if (pointName.length() > 40) {
                    player.sendMessage(ChatColor.RED + "Название не должно превышать 40 символов.");
                    return true;
                }
                if (points.stream().anyMatch(point -> point.name().equalsIgnoreCase(pointName))) {
                    player.sendMessage(ChatColor.RED + "Точка с таким названием уже существует.");
                    return true;
                }
                points.add(new MapPoint(pointName, player.getWorld().getName(),
                        player.getLocation().getBlockX(), player.getLocation().getBlockZ()));
                savePoints();
                player.sendMessage(ChatColor.GREEN + "RT-точка '" + pointName + "' добавлена. "
                        + ChatColor.GRAY + "X: " + player.getLocation().getBlockX()
                        + " Z: " + player.getLocation().getBlockZ());
                return true;
            }
            case "rtmap_remove" -> {
                if (!sender.hasPermission("rtmap.admin")) {
                    sender.sendMessage(ChatColor.RED + "Недостаточно прав.");
                    return true;
                }
                if (args.length == 0) {
                    sender.sendMessage(ChatColor.RED + "Использование: /rtmap_remove <название>");
                    return true;
                }
                String pointName = String.join(" ", args).trim();
                MapPoint found = points.stream()
                        .filter(point -> point.name().equalsIgnoreCase(pointName))
                        .findFirst().orElse(null);
                if (found == null) {
                    sender.sendMessage(ChatColor.RED + "RT-точка не найдена: " + pointName);
                    return true;
                }
                points.remove(found);
                savePoints();
                sender.sendMessage(ChatColor.GREEN + "RT-точка '" + found.name() + "' удалена.");
                return true;
            }
            case "rtmap_list" -> {
                if (!sender.hasPermission("rtmap.admin")) {
                    sender.sendMessage(ChatColor.RED + "Недостаточно прав.");
                    return true;
                }
                if (points.isEmpty()) {
                    sender.sendMessage(ChatColor.YELLOW + "RT-точек пока нет.");
                    return true;
                }
                sender.sendMessage(ChatColor.GOLD + "Сохранённые RT-точки (" + points.size() + "):");
                points.stream().sorted(Comparator.comparing(MapPoint::name,
                                String.CASE_INSENSITIVE_ORDER))
                        .forEach(point -> sender.sendMessage(ChatColor.GRAY + "• "
                                + ChatColor.WHITE + point.name() + ChatColor.DARK_GRAY
                                + " — " + point.world() + " [" + point.x() + ", " + point.z() + "]"));
                return true;
            }
            case "rtmap_reload" -> {
                if (!sender.hasPermission("rtmap.admin")) {
                    sender.sendMessage(ChatColor.RED + "Недостаточно прав.");
                    return true;
                }
                reloadConfig();
                loadPoints();
                sender.sendMessage(ChatColor.GREEN + "RTMap перезагружен. Точек: " + points.size());
                return true;
            }
            default -> {
                return false;
            }
        }
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (command.getName().equalsIgnoreCase("rtmap_remove") && args.length == 1) {
            String prefix = args[0].toLowerCase(Locale.ROOT);
            return points.stream().map(MapPoint::name)
                    .filter(pointName -> pointName.toLowerCase(Locale.ROOT).startsWith(prefix))
                    .toList();
        }
        return List.of();
    }

    private record MapPoint(String name, String world, int x, int z) {
    }

    private static final class MapHolder implements InventoryHolder {
        private Inventory inventory;

        private void setInventory(Inventory inventory) {
            this.inventory = inventory;
        }

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }
}
