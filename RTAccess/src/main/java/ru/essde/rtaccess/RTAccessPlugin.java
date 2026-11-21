package ru.essde.rtaccess;

import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
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
import org.bukkit.event.block.BlockBurnEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockFromToEvent;
import org.bukkit.event.block.BlockIgniteEvent;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.BlockPistonRetractEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.block.BlockRedstoneEvent;
import org.bukkit.event.block.BlockSpreadEvent;
import org.bukkit.event.entity.EntityChangeBlockEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.player.PlayerBucketEmptyEvent;
import org.bukkit.event.player.PlayerBucketFillEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

public final class RTAccessPlugin extends JavaPlugin implements Listener, TabExecutor {

    private static final long DOOR_OPEN_TICKS = 5L * 60L * 20L;
    private static final String ITEM_CARD = "blue_keycard";
    private static final String ITEM_DOOR = "rt_door";
    private static final Pattern REGION_NAME = Pattern.compile("[a-zA-Z0-9_-]{1,32}");

    private NamespacedKey itemTypeKey;
    private NamespacedKey privateWandKey;
    private NamespacedKey protectedBlockItemKey;

    private final Set<DoorKey> customDoors = new HashSet<>();
    private final Map<DoorKey, BukkitTask> closeTasks = new HashMap<>();

    private final Set<BlockKey> protectedBlocks = new HashSet<>();
    private final Map<String, PrivateRegion> privateRegions = new HashMap<>();
    private final Map<UUID, Selection> selections = new HashMap<>();

    @Override
    public void onEnable() {
        itemTypeKey = new NamespacedKey(this, "rt_item_type");
        privateWandKey = new NamespacedKey(this, "rt_private_wand");
        protectedBlockItemKey = new NamespacedKey(this, "rt_protected_block_item");

        loadDoors();
        loadProtectionData();

        Objects.requireNonNull(getCommand("rtaccess"), "Command 'rtaccess' is missing from plugin.yml")
                .setExecutor(this);
        Objects.requireNonNull(getCommand("rtaccess")).setTabCompleter(this);

        Objects.requireNonNull(getCommand("rt_block"), "Command 'rt_block' is missing from plugin.yml")
                .setExecutor(this);
        Objects.requireNonNull(getCommand("rt_block")).setTabCompleter(this);

        Objects.requireNonNull(getCommand("rt_private"), "Command 'rt_private' is missing from plugin.yml")
                .setExecutor(this);
        Objects.requireNonNull(getCommand("rt_private")).setTabCompleter(this);

        getServer().getPluginManager().registerEvents(this, this);
        Bukkit.getScheduler().runTask(this, this::closeDoorsInLoadedChunks);

        getLogger().info("RTAccess enabled. Doors: " + customDoors.size()
                + ", protected blocks: " + protectedBlocks.size()
                + ", private regions: " + privateRegions.size());
    }

    @Override
    public void onDisable() {
        for (BukkitTask task : closeTasks.values()) {
            task.cancel();
        }
        closeTasks.clear();
        closeDoorsInLoadedChunks();
        saveDoors();
        saveProtectionData();
    }

    // ---------------------------------------------------------------------
    // Custom items
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

        org.bukkit.inventory.meta.components.CustomModelDataComponent modelData =
                meta.getCustomModelDataComponent();
        modelData.setStrings(List.of("blue_keycard"));
        meta.setCustomModelDataComponent(modelData);

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

    private ItemStack createPrivateWand() {
        ItemStack item = new ItemStack(Material.WOODEN_AXE);
        ItemMeta meta = item.getItemMeta();
        meta.setDisplayName("§d§lRT Private Wand");
        meta.setLore(List.of(
                "§7ЛКМ по блоку — первая точка.",
                "§7ПКМ по блоку — вторая точка.",
                "§8Затем: /rt_private create <название>"
        ));
        meta.getPersistentDataContainer().set(privateWandKey, PersistentDataType.BYTE, (byte) 1);
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

    private boolean isPrivateWand(ItemStack item) {
        if (item == null || item.getType() != Material.WOODEN_AXE || !item.hasItemMeta()) {
            return false;
        }
        Byte marker = item.getItemMeta().getPersistentDataContainer()
                .get(privateWandKey, PersistentDataType.BYTE);
        return marker != null && marker == (byte) 1;
    }

    private boolean isProtectedBlockItem(ItemStack item) {
        if (item == null || item.getType().isAir() || !item.hasItemMeta()) {
            return false;
        }
        Byte marker = item.getItemMeta().getPersistentDataContainer()
                .get(protectedBlockItemKey, PersistentDataType.BYTE);
        return marker != null && marker == (byte) 1;
    }

    private void markProtectedBlockItem(ItemStack item, boolean marked) {
        ItemMeta meta = item.getItemMeta();
        if (marked) {
            meta.getPersistentDataContainer().set(protectedBlockItemKey, PersistentDataType.BYTE, (byte) 1);
            List<String> lore = meta.hasLore() ? new ArrayList<>(meta.getLore()) : new ArrayList<>();
            String protectionLore = "§c§lRT Block §7— защита при установке";
            if (!lore.contains(protectionLore)) {
                lore.add(protectionLore);
            }
            meta.setLore(lore);
        } else {
            meta.getPersistentDataContainer().remove(protectedBlockItemKey);
            if (meta.hasLore()) {
                List<String> lore = new ArrayList<>(meta.getLore());
                lore.removeIf(line -> line.equals("§c§lRT Block §7— защита при установке"));
                meta.setLore(lore);
            }
        }
        item.setItemMeta(meta);
    }

    private Set<BlockKey> getPlacedStructureKeys(Block block) {
        Set<BlockKey> keys = new HashSet<>();
        keys.add(BlockKey.from(block));

        if (block.getBlockData() instanceof Bisected bisected) {
            Block otherHalf = block.getRelative(
                    bisected.getHalf() == Bisected.Half.BOTTOM ? BlockFace.UP : BlockFace.DOWN);
            if (otherHalf.getType() == block.getType()
                    && otherHalf.getBlockData() instanceof Bisected) {
                keys.add(BlockKey.from(otherHalf));
            }
        }

        if (block.getBlockData() instanceof org.bukkit.block.data.type.Bed bed) {
            BlockFace otherDirection = bed.getPart()
                    == org.bukkit.block.data.type.Bed.Part.FOOT
                    ? bed.getFacing() : bed.getFacing().getOppositeFace();
            Block otherHalf = block.getRelative(otherDirection);
            if (otherHalf.getType() == block.getType()
                    && otherHalf.getBlockData() instanceof org.bukkit.block.data.type.Bed) {
                keys.add(BlockKey.from(otherHalf));
            }
        }

        return keys;
    }

    private void giveItem(Player target, ItemStack item) {
        Map<Integer, ItemStack> overflow = target.getInventory().addItem(item);
        overflow.values().forEach(leftover ->
                target.getWorld().dropItemNaturally(target.getLocation(), leftover));
    }

    // ---------------------------------------------------------------------
    // Private wand selection
    // ---------------------------------------------------------------------

    private boolean handlePrivateWand(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND
                || (event.getAction() != Action.LEFT_CLICK_BLOCK
                && event.getAction() != Action.RIGHT_CLICK_BLOCK)
                || !isPrivateWand(event.getPlayer().getInventory().getItemInMainHand())) {
            return false;
        }

        Block clicked = event.getClickedBlock();
        if (clicked == null) {
            return false;
        }

        event.setCancelled(true);

        SelectionPoint point = SelectionPoint.from(clicked);
        Player player = event.getPlayer();

        if (event.getAction() == Action.LEFT_CLICK_BLOCK) {
            setSelectionPoint(player, true, point);
            player.sendMessage("§d[RT Private] §aПервая точка: §f"
                    + point.x + ", " + point.y + ", " + point.z);
        } else {
            setSelectionPoint(player, false, point);
            player.sendMessage("§d[RT Private] §aВторая точка: §f"
                    + point.x + ", " + point.y + ", " + point.z);
        }
        return true;
    }

    private void setSelectionPoint(Player player, boolean first, SelectionPoint point) {
        UUID id = player.getUniqueId();
        Selection old = selections.get(id);
        SelectionPoint pos1 = old == null ? null : old.pos1;
        SelectionPoint pos2 = old == null ? null : old.pos2;

        if (first) {
            pos1 = point;
        } else {
            pos2 = point;
        }
        selections.put(id, new Selection(pos1, pos2));
    }

    private void setSelectionFromLook(Player player, boolean first) {
        Block target = player.getTargetBlockExact(6);
        if (target == null) {
            player.sendMessage("§cПосмотри на блок в пределах 6 блоков.");
            return;
        }

        SelectionPoint point = SelectionPoint.from(target);
        setSelectionPoint(player, first, point);        player.sendMessage((first ? "§d[RT Private] §aПервая" : "§d[RT Private] §aВторая")
                + " точка установлена: §f" + point.x + ", " + point.y + ", " + point.z);
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
        if (handlePrivateWand(event)) {
            return;
        }

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
            if (event.getPlayer().getGameMode() == GameMode.CREATIVE) {
                DoorKey key = DoorKey.from(base);
                customDoors.remove(key);
                BukkitTask task = closeTasks.remove(key);
                if (task != null) {
                    task.cancel();
                }
                saveDoors();
            } else {
                event.setCancelled(true);
                event.getPlayer().sendMessage("§cЭту дверь нельзя сломать. Используй синюю ключ-карту.");
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onDoorEntityExplosion(EntityExplodeEvent event) {
        event.blockList().removeIf(this::isCustomDoorBlock);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onDoorBlockExplosion(BlockExplodeEvent event) {
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

        Block upperBlock = base.getRelative(BlockFace.UP);
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
            Block below = block.getRelative(BlockFace.DOWN);
            return below.getType() == Material.IRON_DOOR ? below : null;
        }
        return block;
    }

    private boolean isCustomDoorBlock(Block block) {
        Block base = getBottomDoorBlock(block);
        return base != null && customDoors.contains(DoorKey.from(base));
    }

    // ---------------------------------------------------------------------
    // New protection: individual blocks and private cuboid regions
    // ---------------------------------------------------------------------

    private boolean isInsidePrivateRegion(Block block) {
        if (block == null) {
            return false;
        }
        String world = block.getWorld().getName();
        int x = block.getX();
        int y = block.getY();
        int z = block.getZ();
        for (PrivateRegion region : privateRegions.values()) {
            if (region.contains(world, x, y, z)) {
                return true;
            }
        }
        return false;
    }

    private boolean isProtected(Block block) {
        return block != null
                && (protectedBlocks.contains(BlockKey.from(block)) || isInsidePrivateRegion(block));
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onProtectedBlockBreak(BlockBreakEvent event) {
        BlockKey key = BlockKey.from(event.getBlock());
        boolean individuallyProtected = protectedBlocks.contains(key);
        if (!individuallyProtected && !isProtected(event.getBlock())) {
            return;
        }

        if (event.getPlayer().getGameMode() != GameMode.CREATIVE) {
            event.setCancelled(true);
            event.getPlayer().sendMessage("§cЭтот блок защищён системой RT Private.");
            return;
        }

        // Creative can remove RT Blocks. Remove both coordinates of a
        // multi-block structure (for example, a door or bed) to avoid stale data.
        boolean changed = false;
        for (BlockKey relatedKey : getPlacedStructureKeys(event.getBlock())) {
            changed |= protectedBlocks.remove(relatedKey);
        }
        if (changed) {
            saveProtectionData();
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onProtectedBlockPlace(BlockPlaceEvent event) {
        Player player = event.getPlayer();
        boolean creative = player.getGameMode() == GameMode.CREATIVE;
        boolean protectedItem = isProtectedBlockItem(event.getItemInHand());

        // Private regions reject block placement in every game mode.
        // Individually protected RT Block coordinates retain the creative bypass.
        if (isInsidePrivateRegion(event.getBlockPlaced())) {
            event.setCancelled(true);
            player.sendMessage("§cВнутри привата RT Private нельзя размещать блоки.");
            return;
        }
        if (!creative && protectedBlocks.contains(BlockKey.from(event.getBlockPlaced()))) {
            event.setCancelled(true);
            player.sendMessage("§cВ этой точке RT нельзя размещать блоки.");
            return;
        }

        // The PDC marker stays on the entire item stack, so every block placed
        // from that stack receives its own persistent protection entry.
        if (protectedItem) {
            Set<BlockKey> placedKeys = getPlacedStructureKeys(event.getBlockPlaced());
            if (protectedBlocks.addAll(placedKeys)) {
                saveProtectionData();
            }
            player.sendMessage("§aRT Block установлен: этот блок защищён от выживания, взрывов и поршней.");
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void protectEntityExplosions(EntityExplodeEvent event) {
        event.blockList().removeIf(this::isProtected);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void protectBlockExplosions(BlockExplodeEvent event) {
        event.blockList().removeIf(this::isProtected);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void protectPistonExtend(BlockPistonExtendEvent event) {
        if (isProtected(event.getBlock())) {            event.setCancelled(true);
            return;
        }
        for (Block moved : event.getBlocks()) {
            if (isProtected(moved) || isProtected(moved.getRelative(event.getDirection()))) {
                event.setCancelled(true);
                return;
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void protectPistonRetract(BlockPistonRetractEvent event) {
        if (isProtected(event.getBlock())) {
            event.setCancelled(true);
            return;
        }
        BlockFace pullDirection = event.getDirection().getOppositeFace();
        for (Block moved : event.getBlocks()) {
            if (isProtected(moved) || isProtected(moved.getRelative(pullDirection))) {
                event.setCancelled(true);
                return;
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void protectEntityBlockChanges(EntityChangeBlockEvent event) {
        if (isProtected(event.getBlock())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void protectBlockBurn(BlockBurnEvent event) {
        if (isProtected(event.getBlock())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void protectBlockIgnite(BlockIgniteEvent event) {
        if (isProtected(event.getBlock())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void protectBlockSpread(BlockSpreadEvent event) {
        if (isProtected(event.getBlock())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void protectFluidFlow(BlockFromToEvent event) {
        if (isProtected(event.getToBlock())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void protectBucketEmpty(PlayerBucketEmptyEvent event) {
        Block target = event.getBlockClicked().getRelative(event.getBlockFace());
        if (isInsidePrivateRegion(target)
                || (isProtected(target) && event.getPlayer().getGameMode() != GameMode.CREATIVE)) {
            event.setCancelled(true);
            event.getPlayer().sendMessage("§cВнутри привата RT Private нельзя размещать жидкости.");
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void protectBucketFill(PlayerBucketFillEvent event) {
        if (isProtected(event.getBlockClicked()) && event.getPlayer().getGameMode() != GameMode.CREATIVE) {
            event.setCancelled(true);
            event.getPlayer().sendMessage("§cНельзя забирать жидкости из защищённой зоны RT Private.");
        }
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

    private void loadProtectionData() {
        protectedBlocks.clear();
        privateRegions.clear();

        for (Map<?, ?> entry : getConfig().getMapList("protected-blocks")) {
            try {
                protectedBlocks.add(new BlockKey(
                        String.valueOf(entry.get("world")),
                        ((Number) entry.get("x")).intValue(),
                        ((Number) entry.get("y")).intValue(),
                        ((Number) entry.get("z")).intValue()
                ));
            } catch (Exception exception) {
                getLogger().warning("Не удалось загрузить защищённый блок: " + exception.getMessage());
            }
        }

        for (Map<?, ?> entry : getConfig().getMapList("private-regions")) {
            try {
                PrivateRegion region = new PrivateRegion(
                        String.valueOf(entry.get("name")).toLowerCase(),
                        String.valueOf(entry.get("world")),
                        ((Number) entry.get("min-x")).intValue(),
                        ((Number) entry.get("min-y")).intValue(),
                        ((Number) entry.get("min-z")).intValue(),
                        ((Number) entry.get("max-x")).intValue(),
                        ((Number) entry.get("max-y")).intValue(),
                        ((Number) entry.get("max-z")).intValue()
                );
                privateRegions.put(region.name, region);
            } catch (Exception exception) {
                getLogger().warning("Не удалось загрузить приват RT: " + exception.getMessage());
            }
        }
    }

    private void saveProtectionData() {
        List<Map<String, Object>> serializedBlocks = new ArrayList<>();
        for (BlockKey key : protectedBlocks) {
            Map<String, Object> entry = new HashMap<>();
            entry.put("world", key.world);
            entry.put("x", key.x);
            entry.put("y", key.y);
            entry.put("z", key.z);
            serializedBlocks.add(entry);
        }

        List<Map<String, Object>> serializedRegions = new ArrayList<>();
        for (PrivateRegion region : privateRegions.values()) {
            Map<String, Object> entry = new HashMap<>();
            entry.put("name", region.name);
            entry.put("world", region.world);
            entry.put("min-x", region.minX);
            entry.put("min-y", region.minY);
            entry.put("min-z", region.minZ);
            entry.put("max-x", region.maxX);
            entry.put("max-y", region.maxY);
            entry.put("max-z", region.maxZ);
            serializedRegions.add(entry);
        }

        getConfig().set("protected-blocks", serializedBlocks);
        getConfig().set("private-regions", serializedRegions);
        saveConfig();
    }

    // ---------------------------------------------------------------------
    // Commands
    // ---------------------------------------------------------------------

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("rtaccess.admin")) {
            sender.sendMessage("§cНедостаточно прав. Нужно право rtaccess.admin.");
            return true;
        }

        String commandName = command.getName().toLowerCase();
        if (commandName.equals("rt_block")) {
            handleRtBlockCommand(sender, args);
            return true;
        }
        if (commandName.equals("rt_private")) {
            handleRtPrivateCommand(sender, args);
            return true;
        }

        handleRtAccessCommand(sender, args);
        return true;
    }

    private void handleRtBlockCommand(CommandSender sender, String[] args) {
        if (args.length == 1 && args[0].equalsIgnoreCase("list")) {
            sender.sendMessage("§6Установленных защищённых RT Blocks: §e" + protectedBlocks.size());
            return;
        }

        if (!(sender instanceof Player player)) {
            sender.sendMessage("§cКоманду /rt_block нужно использовать в игре, держа блок в основной руке.");
            return;
        }

        if (args.length > 0 && (args[0].equalsIgnoreCase("unmark")
                || args[0].equalsIgnoreCase("off") || args[0].equalsIgnoreCase("clear"))) {
            ItemStack held = player.getInventory().getItemInMainHand();
            if (!isProtectedBlockItem(held)) {
                player.sendMessage("§eВ основной руке нет предмета с меткой RT Block.");
                return;
            }
            markProtectedBlockItem(held, false);
            player.getInventory().setItemInMainHand(held);
            player.sendMessage("§aМетка RT Block снята со всей стопки.");
            return;
        }

        if (args.length != 0) {
            player.sendMessage("§eИспользование: /rt_block — пометить блок в руке; /rt_block unmark — снять метку; /rt_block list — статистика.");
            return;
        }

        ItemStack held = player.getInventory().getItemInMainHand();
        if (held == null || held.getType().isAir() || !held.getType().isBlock()) {
            player.sendMessage("§cВозьми в основную руку любой блок и повтори /rt_block.");
            return;
        }

        if (isProtectedBlockItem(held)) {
            player.sendMessage("§eЭта стопка уже помечена RT Block. Каждый установленный блок будет защищён.");
            return;
        }

        markProtectedBlockItem(held, true);
        player.getInventory().setItemInMainHand(held);
        player.sendMessage("§aВся стопка §f" + held.getType().name()
                + " §aпомечена RT Block. Каждый блок из неё будет защищён после установки.");
    }

    private void handleRtPrivateCommand(CommandSender sender, String[] args) {
        if (args.length == 0 || args[0].equalsIgnoreCase("wand")) {
            if (!(sender instanceof Player player)) {
                sender.sendMessage("§cТопор RT Private можно получить только в игре.");
                return;
            }
            giveItem(player, createPrivateWand());
            player.sendMessage("§dRT Private: §fЛКМ — точка 1, ПКМ — точка 2.");
            player.sendMessage("§7После выбора введи: §e/rt_private create <название>");
            return;
        }

        String sub = args[0].toLowerCase();
        if (sub.equals("pos1") || sub.equals("pos2")) {
            if (!(sender instanceof Player player)) {
                sender.sendMessage("§cКоманду нужно использовать в игре.");
                return;
            }
            setSelectionFromLook(player, sub.equals("pos1"));
            return;
        }

        if (sub.equals("create")) {
            if (!(sender instanceof Player player)) {
                sender.sendMessage("§cСоздать приват можно только в игре после выбора двух точек.");
                return;
            }
            if (args.length != 2 || !REGION_NAME.matcher(args[1]).matches()) {
                player.sendMessage("§cИспользование: /rt_private create <название>");
                player.sendMessage("§7Название: латиница, цифры, дефис или подчёркивание; до 32 символов.");
                return;
            }

            Selection selection = selections.get(player.getUniqueId());
            if (selection == null || selection.pos1 == null || selection.pos2 == null) {
                player.sendMessage("§cСначала выбери две точки деревянным топором RT Private.");
                return;
            }
            if (!selection.pos1.world.equals(selection.pos2.world)) {
                player.sendMessage("§cОбе точки должны быть в одном мире.");
                return;
            }

            String name = args[1].toLowerCase();
            if (privateRegions.containsKey(name)) {
                player.sendMessage("§cПриват с таким названием уже существует.");
                return;
            }

            PrivateRegion region = PrivateRegion.from(name, selection.pos1, selection.pos2);
            privateRegions.put(name, region);
            saveProtectionData();            player.sendMessage("§aПриват §e" + name + " §aсоздан.");
            player.sendMessage("§7Границы: §f(" + region.minX + ", " + region.minY + ", " + region.minZ
                    + ") — (" + region.maxX + ", " + region.maxY + ", " + region.maxZ + ")");
            player.sendMessage("§7Все блоки внутри защищены. Изменять их можно только в креативе.");
            return;
        }

        if (sub.equals("delete") || sub.equals("remove")) {
            if (args.length != 2) {
                sender.sendMessage("§cИспользование: /rt_private delete <название>");
                return;
            }
            PrivateRegion removed = privateRegions.remove(args[1].toLowerCase());
            if (removed == null) {
                sender.sendMessage("§cПриват с таким названием не найден.");
                return;
            }
            saveProtectionData();
            sender.sendMessage("§aПриват §e" + removed.name + " §aудалён.");
            return;
        }

        if (sub.equals("list")) {
            if (privateRegions.isEmpty()) {
                sender.sendMessage("§eПриватов RT Private пока нет.");
                return;
            }
            sender.sendMessage("§6Приваты RT Private:");
            privateRegions.values().stream()
                    .sorted((a, b) -> a.name.compareToIgnoreCase(b.name))
                    .forEach(region -> sender.sendMessage("§e- " + region.name
                            + " §7(" + region.world + ")"));
            return;
        }

        if (sub.equals("info")) {
            if (!(sender instanceof Player player)) {
                sender.sendMessage("§cКоманду нужно использовать в игре.");
                return;
            }
            String world = player.getWorld().getName();
            int x = player.getLocation().getBlockX();
            int y = player.getLocation().getBlockY();
            int z = player.getLocation().getBlockZ();
            List<String> found = privateRegions.values().stream()
                    .filter(region -> region.contains(world, x, y, z))
                    .map(region -> region.name)
                    .sorted()
                    .toList();
            if (found.isEmpty()) {
                player.sendMessage("§eТы не находишься внутри привата RT Private.");
            } else {
                player.sendMessage("§aПриваты в этой точке: §e" + String.join(", ", found));
            }
            return;
        }

        if (sub.equals("clear")) {
            if (!(sender instanceof Player player)) {
                sender.sendMessage("§cКоманду нужно использовать в игре.");
                return;
            }
            selections.remove(player.getUniqueId());
            player.sendMessage("§aВыделение RT Private очищено.");
            return;
        }

        sender.sendMessage("§6Команды RT Private:");
        sender.sendMessage("§e/rt_private §7— получить деревянный топор выделения");
        sender.sendMessage("§e/rt_private pos1|pos2 §7— поставить точку по блоку, на который смотришь");
        sender.sendMessage("§e/rt_private create <название> §7— создать приват по двум точкам");
        sender.sendMessage("§e/rt_private delete <название> §7— удалить приват");
        sender.sendMessage("§e/rt_private list §7— список приватов");
        sender.sendMessage("§e/rt_private info §7— узнать приват в текущей точке");
        sender.sendMessage("§e/rt_private clear §7— сбросить выделение");
    }

    private void handleRtAccessCommand(CommandSender sender, String[] args) {
        if (args.length == 0) {
            sendHelp(sender);
            return;
        }

        if (args[0].equalsIgnoreCase("give")) {
            if (args.length < 3 || args.length > 4) {
                sender.sendMessage("§cИспользование: /rt give <card|door> <игрок> [количество]");
                return;
            }

            String type = args[1].toLowerCase();
            if (!type.equals("card") && !type.equals("door")) {
                sender.sendMessage("§cТип предмета: card или door.");
                return;
            }

            Player target = Bukkit.getPlayerExact(args[2]);
            if (target == null) {
                sender.sendMessage("§cИгрок должен быть онлайн.");
                return;
            }

            int amount = 1;
            if (args.length == 4) {
                try {
                    amount = Integer.parseInt(args[3]);
                } catch (NumberFormatException exception) {
                    sender.sendMessage("§cКоличество должно быть числом от 1 до 64.");
                    return;
                }
            }
            if (amount < 1 || amount > 64) {
                sender.sendMessage("§cКоличество должно быть от 1 до 64.");
                return;
            }

            ItemStack item = type.equals("card") ? createKeycard(amount) : createDoorItem(amount);
            giveItem(target, item);
            target.sendMessage(type.equals("card")
                    ? "§bТы получил синюю ключ-карту."
                    : "§6Ты получил специальную дверь РТ.");
            sender.sendMessage("§aПредмет выдан игроку " + target.getName() + ".");
            return;
        }

        if (args[0].equalsIgnoreCase("removedoor")) {
            if (!(sender instanceof Player player)) {
                sender.sendMessage("§cКоманду нужно использовать в игре, глядя на специальную дверь.");
                return;
            }

            Block target = player.getTargetBlockExact(6);
            Block base = target == null ? null : getBottomDoorBlock(target);
            if (base == null) {
                player.sendMessage("§cПосмотри на специальную железную дверь с расстояния до 6 блоков.");
                return;
            }

            DoorKey key = DoorKey.from(base);
            if (!customDoors.remove(key)) {
                player.sendMessage("§cЭта дверь не зарегистрирована как дверь РТ.");
                return;
            }

            BukkitTask task = closeTasks.remove(key);
            if (task != null) {
                task.cancel();
            }
            setDoorOpen(base, false);
            saveDoors();
            player.sendMessage("§aДверь удалена из системы РТ. Теперь её можно сломать обычным способом.");
            return;
        }

        if (args[0].equalsIgnoreCase("reload")) {
            for (BukkitTask task : closeTasks.values()) {
                task.cancel();
            }
            closeTasks.clear();
            reloadConfig();
            loadDoors();
            loadProtectionData();
            closeDoorsInLoadedChunks();
            sender.sendMessage("§aСистема RTAccess перезагружена. Дверей: " + customDoors.size()
                    + ", блоков: " + protectedBlocks.size()
                    + ", приватов: " + privateRegions.size());
            return;
        }

        sendHelp(sender);
    }

    private void sendHelp(CommandSender sender) {
        sender.sendMessage("§6Команды RTAccess:");
        sender.sendMessage("§e/rt give card <игрок> [количество] §7— выдать синюю ключ-карту");
        sender.sendMessage("§e/rt give door <игрок> [количество] §7— выдать специальную дверь РТ");
        sender.sendMessage("§e/rt removedoor §7— снять защиту с двери, на которую смотришь");
        sender.sendMessage("§e/rt reload §7— перезагрузить систему");
        sender.sendMessage("§e/rt_block §7— пометить блок в основной руке: каждый установленный блок будет защищён");
        sender.sendMessage("§e/rt_block unmark §7— снять метку со стопки в руке");
        sender.sendMessage("§e/rt_block list §7— количество установленных защищённых блоков");
        sender.sendMessage("§e/rt_private §7— получить топор для выделения привата");
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!sender.hasPermission("rtaccess.admin")) {
            return List.of();
        }

        String commandName = command.getName().toLowerCase();
        if (commandName.equals("rt_block")) {
            if (args.length == 1) {
                return filter(List.of("unmark", "off", "clear", "list"), args[0]);
            }
            return List.of();
        }

        if (commandName.equals("rt_private")) {
            if (args.length == 1) {
                return filter(List.of("wand", "pos1", "pos2", "create", "delete", "list", "info", "clear"), args[0]);
            }
            if (args.length == 2 && (args[0].equalsIgnoreCase("delete")
                    || args[0].equalsIgnoreCase("remove"))) {
                return privateRegions.keySet().stream()
                        .filter(name -> name.startsWith(args[1].toLowerCase()))
                        .sorted()
                        .toList();
            }
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

    // ---------------------------------------------------------------------
    // Coordinate records
    // ---------------------------------------------------------------------

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

    private record BlockKey(String world, int x, int y, int z) {
        private static BlockKey from(Block block) {
            return new BlockKey(block.getWorld().getName(), block.getX(), block.getY(), block.getZ());
        }
    }

    private record SelectionPoint(String world, int x, int y, int z) {
        private static SelectionPoint from(Block block) {
            return new SelectionPoint(block.getWorld().getName(), block.getX(), block.getY(), block.getZ());
        }
    }

    private record Selection(SelectionPoint pos1, SelectionPoint pos2) {
    }

    private record PrivateRegion(
            String name,
            String world,
            int minX,
            int minY,
            int minZ,
            int maxX,
            int maxY,
            int maxZ
    ) {
        private static PrivateRegion from(String name, SelectionPoint a, SelectionPoint b) {
            return new PrivateRegion(
                    name,
                    a.world,
                    Math.min(a.x, b.x),
                    Math.min(a.y, b.y),
                    Math.min(a.z, b.z),
                    Math.max(a.x, b.x),
                    Math.max(a.y, b.y),
                    Math.max(a.z, b.z)
            );
        }

        private boolean contains(String worldName, int x, int y, int z) {
            return world.equals(worldName)
                    && x >= minX && x <= maxX
                    && y >= minY && y <= maxY
                    && z >= minZ && z <= maxZ;
        }
    }
}