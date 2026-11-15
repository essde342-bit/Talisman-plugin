package ru.essde.rtaccess;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.Bisected;
import org.bukkit.block.data.type.Door;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.block.BlockRedstoneEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.NamespacedKey;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

public final class RTAccessPlugin extends JavaPlugin implements Listener, TabExecutor {

    private static final long DOOR_OPEN_TICKS = 5L * 60L * 20L;
    private static final String ITEM_CARD = "blue_keycard";
    private static final String ITEM_DOOR = "rt_door";

    private NamespacedKey itemTypeKey;
    private final Set<DoorKey> customDoors = new HashSet<>();
    private final Map<DoorKey, BukkitTask> closeTasks = new HashMap<>();

    @Override
    public void onEnable() {
        itemTypeKey = new NamespacedKey(this, "rt_item_type");
        loadDoors();

        Objects.requireNonNull(getCommand("rtaccess"), "Command 'rtaccess' is missing from plugin.yml")
                .setExecutor(this);
        Objects.requireNonNull(getCommand("rtaccess"))
                .setTabCompleter(this);

        getServer().getPluginManager().registerEvents(this, this);

        // Any door left open by a previous server session is closed at startup.
        Bukkit.getScheduler().runTask(this, this::closeDoorsInLoadedChunks);

        getLogger().info("RTAccess enabled. Registered custom doors: " + customDoors.size());
    }

    @Override
    public void onDisable() {
        for (BukkitTask task : closeTasks.values()) {
            task.cancel();
        }
        closeTasks.clear();
        closeDoorsInLoadedChunks();
        saveDoors();
    }

    // ---------------------------------------------------------------------
    // Items
    // ---------------------------------------------------------------------

    private ItemStack createKeycard(int amount) {
        ItemStack item = new ItemStack(Material.PAPER, amount);
        ItemMeta meta = item.getItemMeta();
        meta.setDisplayName("§b§lСиняя ключ-карта");
        meta.setLore(List.of(
                "§7Специальный пропуск для дверей РТ.",
                "§7Нажми §fЛКМ §7по специальной двери.",
                "§8Не исчезает после использования."
        ));

        // Minecraft 1.21.4 resource-pack model selector:
        // assets/minecraft/items/paper.json selects this string value.
        org.bukkit.inventory.meta.components.CustomModelDataComponent modelData =
                meta.getCustomModelDataComponent();
        modelData.setStrings(List.of("blue_keycard"));
        meta.setCustomModelDataComponent(modelData);

        // Persistent data remains the server-side identity used by RTAccess.
        meta.getPersistentDataContainer().set(itemTypeKey, PersistentDataType.STRING, ITEM_CARD);
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack createDoorItem(int amount) {
        ItemStack item = new ItemStack(Material.IRON_DOOR, amount);
        ItemMeta meta = item.getItemMeta();
        meta.setDisplayName("§6§lСпециальная дверь РТ");
        meta.setLore(List.of(
                "§7Установи эту дверь в локации РТ.",
                "§7Открывается синей ключ-картой.",
                "§7После открытия закрывается через §f5 минут§7."
        ));
        meta.getPersistentDataContainer().set(itemTypeKey, PersistentDataType.STRING, ITEM_DOOR);
        item.setItemMeta(meta);
        return item;
    }

    private boolean isTaggedItem(ItemStack item, String expectedType) {
        if (item == null || item.getType().isAir() || !item.hasItemMeta()) {
            return false;
        }
        String type = item.getItemMeta().getPersistentDataContainer()
                .get(itemTypeKey, PersistentDataType.STRING);
        return expectedType.equals(type);
    }

    private void giveItem(Player target, ItemStack item) {
        Map<Integer, ItemStack> overflow = target.getInventory().addItem(item);
        overflow.values().forEach(leftover ->
                target.getWorld().dropItemNaturally(target.getLocation(), leftover));
    }

    // ---------------------------------------------------------------------
    // Door placement, opening and protection
    // ---------------------------------------------------------------------

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onCustomDoorPlace(BlockPlaceEvent event) {
        if (!isTaggedItem(event.getItemInHand(), ITEM_DOOR)) {
            return;
        }

        Block base = getBottomDoorBlock(event.getBlockPlaced());
        if (base == null || base.getType() != Material.IRON_DOOR) {
            event.setCancelled(true);
            event.getPlayer().sendMessage("§cСпециальную дверь РТ можно установить только как железную дверь.");
            return;
        }

        DoorKey key = DoorKey.from(base);
        customDoors.add(key);
        saveDoors();
        setDoorOpen(base, false);
        event.getPlayer().sendMessage("§aСпециальная дверь РТ установлена и зарегистрирована.");
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onDoorInteract(PlayerInteractEvent event) {
        if (event.getAction() != Action.LEFT_CLICK_BLOCK
                && event.getAction() != Action.RIGHT_CLICK_BLOCK) {
            return;
        }

        Block clicked = event.getClickedBlock();
        if (clicked == null) {
            return;
        }

        Block base = getBottomDoorBlock(clicked);
        if (base == null) {
            return;
        }

        DoorKey key = DoorKey.from(base);
        if (!customDoors.contains(key)) {
            return;
        }

        // Prevent normal interaction from bypassing the keycard requirement.
        event.setCancelled(true);

        if (event.getHand() != EquipmentSlot.HAND) {
            return;
        }

        Player player = event.getPlayer();
        if (event.getAction() == Action.LEFT_CLICK_BLOCK) {
            if (!isTaggedItem(player.getInventory().getItemInMainHand(), ITEM_CARD)) {
                player.sendMessage("§cДля открытия нужна синяя ключ-карта. Нажми ЛКМ по двери.");
                return;
            }
            openDoor(base, key, player);
        } else if (isTaggedItem(player.getInventory().getItemInMainHand(), ITEM_CARD)) {
            player.sendMessage("§eНажми ЛКМ по двери, чтобы использовать синюю ключ-карту.");
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onCustomDoorBreak(BlockBreakEvent event) {
        Block base = getBottomDoorBlock(event.getBlock());
        if (base != null && customDoors.contains(DoorKey.from(base))) {
            event.setCancelled(true);
            event.getPlayer().sendMessage("§cЭту дверь нельзя сломать обычным способом. Используй /rtaccess removedoor.");
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onEntityExplosion(EntityExplodeEvent event) {
        event.blockList().removeIf(this::isCustomDoorBlock);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBlockExplosion(BlockExplodeEvent event) {
        event.blockList().removeIf(this::isCustomDoorBlock);
    }

    @EventHandler
    public void onChunkLoad(ChunkLoadEvent event) {
        for (DoorKey key : customDoors) {
            if (key.world.equals(event.getWorld().getName())
                    && key.chunkX() == event.getChunk().getX()
                    && key.chunkZ() == event.getChunk().getZ()) {
                closeDoorNow(key);
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void blockRedstoneOpening(BlockRedstoneEvent event) {
        if (event.getNewCurrent() <= 0) {
            return;
        }

        Block poweredBlock = event.getBlock();
        if (isCustomDoorBlock(poweredBlock)) {
            event.setNewCurrent(0);
            return;
        }

        for (BlockFace face : List.of(
                BlockFace.UP, BlockFace.DOWN, BlockFace.NORTH,
                BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST)) {
            if (isCustomDoorBlock(poweredBlock.getRelative(face))) {
                // Iron doors can normally be powered by redstone. Suppress
                // direct redstone current next to our registered doors.
                event.setNewCurrent(0);
                return;
            }
        }
    }

    private void openDoor(Block base, DoorKey key, Player player) {
        if (closeTasks.containsKey(key)) {
            player.sendMessage("§eЭта дверь уже открыта. Она закроется по окончании 5 минут.");
            return;
        }

        setDoorOpen(base, true);
        player.sendMessage("§aДверь РТ открыта на 5 минут. Ключ-карта не потрачена.");

        BukkitTask task = Bukkit.getScheduler().runTaskLater(this, () -> {
            closeTasks.remove(key);
            closeDoorNow(key);
            World world = Bukkit.getWorld(key.world);
            if (world != null && world.isChunkLoaded(key.chunkX(), key.chunkZ())) {
                world.getPlayers().stream()
                        .filter(p -> p.getLocation().distanceSquared(
                                new org.bukkit.Location(world, key.x + 0.5, key.y + 0.5, key.z + 0.5)) <= 64.0)
                        .forEach(p -> p.sendMessage("§eДверь РТ автоматически закрылась."));
            }
        }, DOOR_OPEN_TICKS);
        closeTasks.put(key, task);
    }

    private void setDoorOpen(Block base, boolean open) {
        if (base == null || base.getType() != Material.IRON_DOOR) {
            return;
        }

        if (base.getBlockData() instanceof Door lower) {
            lower.setOpen(open);
            base.setBlockData(lower, false);
        }

        Block upperBlock = base.getRelative(org.bukkit.block.BlockFace.UP);
        if (upperBlock.getType() == Material.IRON_DOOR
                && upperBlock.getBlockData() instanceof Door upper) {
            upper.setOpen(open);
            upperBlock.setBlockData(upper, false);
        }
    }

    private void closeDoorNow(DoorKey key) {
        World world = Bukkit.getWorld(key.world);
        if (world == null || !world.isChunkLoaded(key.chunkX(), key.chunkZ())) {
            return;
        }

        Block base = world.getBlockAt(key.x, key.y, key.z);
        if (base.getType() == Material.IRON_DOOR) {
            setDoorOpen(base, false);
        }

        BukkitTask task = closeTasks.remove(key);
        if (task != null) {
            task.cancel();
        }
    }

    private void closeDoorsInLoadedChunks() {
        for (DoorKey key : customDoors) {
            closeDoorNow(key);
        }
    }

    private Block getBottomDoorBlock(Block block) {
        if (block.getType() != Material.IRON_DOOR || !(block.getBlockData() instanceof Door door)) {
            return null;
        }
        if (door.getHalf() == Bisected.Half.TOP) {
            Block below = block.getRelative(org.bukkit.block.BlockFace.DOWN);
            return below.getType() == Material.IRON_DOOR ? below : null;
        }
        return block;
    }

    private boolean isCustomDoorBlock(Block block) {
        Block base = getBottomDoorBlock(block);
        return base != null && customDoors.contains(DoorKey.from(base));
    }

    // ---------------------------------------------------------------------
    // Persistence
    // ---------------------------------------------------------------------

    private void loadDoors() {
        customDoors.clear();
        for (Map<?, ?> entry : getConfig().getMapList("doors")) {
            try {
                String world = String.valueOf(entry.get("world"));
                int x = ((Number) entry.get("x")).intValue();
                int y = ((Number) entry.get("y")).intValue();
                int z = ((Number) entry.get("z")).intValue();
                customDoors.add(new DoorKey(world, x, y, z));
            } catch (Exception exception) {
                getLogger().warning("Не удалось загрузить координаты двери: " + exception.getMessage());
            }
        }
    }

    private void saveDoors() {
        List<Map<String, Object>> serialized = new ArrayList<>();
        for (DoorKey key : customDoors) {
            Map<String, Object> entry = new HashMap<>();
            entry.put("world", key.world);
            entry.put("x", key.x);
            entry.put("y", key.y);
            entry.put("z", key.z);
            serialized.add(entry);
        }
        getConfig().set("doors", serialized);
        saveConfig();
    }

    // ---------------------------------------------------------------------
    // Admin commands
    // ---------------------------------------------------------------------

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("rtaccess.admin")) {
            sender.sendMessage("§cНедостаточно прав. Нужно право rtaccess.admin.");
            return true;
        }

        if (args.length == 0) {
            sendHelp(sender);
            return true;
        }

        if (args[0].equalsIgnoreCase("give")) {
            if (args.length < 3 || args.length > 4) {
                sender.sendMessage("§cИспользование: /rtaccess give <card|door> <игрок> [количество]");
                return true;
            }

            String type = args[1].toLowerCase();
            if (!type.equals("card") && !type.equals("door")) {
                sender.sendMessage("§cТип предмета: card или door.");
                return true;
            }

            Player target = Bukkit.getPlayerExact(args[2]);
            if (target == null) {
                sender.sendMessage("§cИгрок должен быть онлайн.");
                return true;
            }

            int amount = 1;
            if (args.length == 4) {
                try {
                    amount = Integer.parseInt(args[3]);
                } catch (NumberFormatException exception) {
                    sender.sendMessage("§cКоличество должно быть числом от 1 до 64.");
                    return true;
                }
            }
            if (amount < 1 || amount > 64) {
                sender.sendMessage("§cКоличество должно быть от 1 до 64.");
                return true;
            }

            ItemStack item = type.equals("card") ? createKeycard(amount) : createDoorItem(amount);
            giveItem(target, item);
            target.sendMessage(type.equals("card")
                    ? "§bТы получил синюю ключ-карту."
                    : "§6Ты получил специальную дверь РТ.");
            sender.sendMessage("§aПредмет выдан игроку " + target.getName() + ".");
            return true;
        }

        if (args[0].equalsIgnoreCase("removedoor")) {
            if (!(sender instanceof Player player)) {
                sender.sendMessage("§cКоманду нужно использовать в игре, глядя на специальную дверь.");
                return true;
            }

            Block target = player.getTargetBlockExact(6);
            Block base = target == null ? null : getBottomDoorBlock(target);
            if (base == null) {
                player.sendMessage("§cПосмотри на специальную железную дверь с расстояния до 6 блоков.");
                return true;
            }

            DoorKey key = DoorKey.from(base);
            if (!customDoors.remove(key)) {
                player.sendMessage("§cЭта дверь не зарегистрирована как дверь РТ.");
                return true;
            }

            BukkitTask task = closeTasks.remove(key);
            if (task != null) {
                task.cancel();
            }
            setDoorOpen(base, false);
            saveDoors();
            player.sendMessage("§aДверь удалена из системы РТ. Теперь её можно сломать обычным способом.");
            return true;
        }

        if (args[0].equalsIgnoreCase("reload")) {
            for (BukkitTask task : closeTasks.values()) {
                task.cancel();
            }
            closeTasks.clear();
            reloadConfig();
            loadDoors();
            closeDoorsInLoadedChunks();
            sender.sendMessage("§aСписок дверей РТ перезагружен. Зарегистрировано: " + customDoors.size());
            return true;
        }

        sendHelp(sender);
        return true;
    }

    private void sendHelp(CommandSender sender) {
        sender.sendMessage("§6Команды RTAccess:");
        sender.sendMessage("§e/rtaccess give card <игрок> [количество] §7— выдать синюю ключ-карту");
        sender.sendMessage("§e/rtaccess give door <игрок> [количество] §7— выдать специальную дверь РТ");
        sender.sendMessage("§e/rtaccess removedoor §7— снять защиту с двери, на которую смотришь");
        sender.sendMessage("§e/rtaccess reload §7— перечитать сохранённые двери");
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!sender.hasPermission("rtaccess.admin")) {
            return List.of();
        }

        if (args.length == 1) {
            return filter(List.of("give", "removedoor", "reload"), args[0]);
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("give")) {
            return filter(List.of("card", "door"), args[1]);
        }
        if (args.length == 3 && args[0].equalsIgnoreCase("give")) {
            return Bukkit.getOnlinePlayers().stream()
                    .map(Player::getName)
                    .filter(name -> name.toLowerCase().startsWith(args[2].toLowerCase()))
                    .toList();
        }
        if (args.length == 4 && args[0].equalsIgnoreCase("give")) {
            return filter(List.of("1", "2", "4", "8", "16", "32", "64"), args[3]);
        }
        return List.of();
    }

    private List<String> filter(List<String> values, String prefix) {
        return values.stream()
                .filter(value -> value.toLowerCase().startsWith(prefix.toLowerCase()))
                .toList();
    }

    private record DoorKey(String world, int x, int y, int z) {
        private static DoorKey from(Block block) {
            return new DoorKey(block.getWorld().getName(), block.getX(), block.getY(), block.getZ());
        }

        private int chunkX() {
            return x >> 4;
        }

        private int chunkZ() {
            return z >> 4;
        }
    }
}
