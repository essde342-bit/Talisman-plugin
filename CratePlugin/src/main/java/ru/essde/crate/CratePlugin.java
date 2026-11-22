package ru.essde.crate;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Display;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Barrel;
import org.bukkit.block.Block;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.inventory.AnvilInventory;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.InventoryType;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.view.AnvilView;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

public final class CratePlugin extends org.bukkit.plugin.java.JavaPlugin implements Listener, CommandExecutor {

    private static final int EDITOR_SIZE = 54;
    private static final int LOOT_SLOT_COUNT = 27;
    private static final int TIMER_SLOT = 45;
    private static final int HELP_SLOT = 49;
    private static final int SAVE_SLOT = 53;
    private static final int MAX_TIMER_SECONDS = 604800;

    private final List<LootEntry> lootEntries = new ArrayList<>();
    private final Map<UUID, CrateData> crates = new LinkedHashMap<>();
    private final Map<UUID, TextDisplay> holograms = new HashMap<>();
    private final Map<UUID, PendingInput> pendingInputs = new HashMap<>();
    private final Random random = new Random();

    private File lootFile;
    private File cratesFile;
    private int timerSeconds = 300;

    @Override
    public void onEnable() {
        if (!getDataFolder().exists() && !getDataFolder().mkdirs()) {
            getLogger().severe("Could not create plugin data directory.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        lootFile = new File(getDataFolder(), "loot.yml");
        cratesFile = new File(getDataFolder(), "crates.yml");

        loadLoot();
        loadCrates();

        getServer().getPluginManager().registerEvents(this, this);
        getCommand("crate").setExecutor(this);
        getCommand("crate_create").setExecutor(this);

        Bukkit.getScheduler().runTaskTimer(this, this::tickCrates, 20L, 20L);
        Bukkit.getScheduler().runTask(this, this::restoreLoadedCrates);

        getLogger().info("CratePlugin enabled. Loaded " + crates.size() + " crate(s) and "
                + lootEntries.size() + " loot entry/entries.");
    }

    @Override
    public void onDisable() {
        saveLoot();
        saveCrates();
        pendingInputs.clear();
        holograms.clear();
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("This command can only be used by a player.");
            return true;
        }

        if (!player.hasPermission("crate.admin")) {
            player.sendMessage(Component.text("У тебя нет прав на управление крейтами.", NamedTextColor.RED));
            return true;
        }

        if (command.getName().equalsIgnoreCase("crate_create")) {
            if (args.length == 0) {
                player.sendMessage(Component.text("Использование: /crate_create <название>", NamedTextColor.YELLOW));
                return true;
            }
            createCrate(player, String.join(" ", args));
            return true;
        }

        if (args.length == 0 || args[0].equalsIgnoreCase("editor")) {
            openEditor(player);
            return true;
        }

        if (args[0].equalsIgnoreCase("remove")) {
            removeLookingAtCrate(player);
            return true;
        }

        if (args[0].equalsIgnoreCase("list")) {
            player.sendMessage(Component.text("Активных крейтов: " + crates.size(), NamedTextColor.GOLD));
            for (CrateData crate : crates.values()) {
                player.sendMessage(Component.text("- " + crate.name + " [" + crate.worldName + " "
                        + crate.x + ", " + crate.y + ", " + crate.z + "]", NamedTextColor.GRAY));
            }
            return true;
        }

        player.sendMessage(Component.text("Команды: /crate editor, /crate list, /crate remove, /crate_create <название>",
                NamedTextColor.YELLOW));
        return true;
    }

    private void createCrate(Player player, String name) {
        Block target = player.getTargetBlockExact(6);
        if (target == null || target.getType() != Material.BARREL) {
            player.sendMessage(Component.text("Смотри на бочку в пределах 6 блоков.", NamedTextColor.RED));
            return;
        }

        CrateData existing = findCrate(target);
        if (existing != null) {
            player.sendMessage(Component.text("Эта бочка уже является крейтом.", NamedTextColor.RED));
            return;
        }

        Barrel barrel = (Barrel) target.getState();
        barrel.getInventory().clear();
        barrel.update(true, false);

        UUID id = UUID.randomUUID();
        CrateData crate = new CrateData(
                id,
                target.getWorld().getName(),
                target.getX(),
                target.getY(),
                target.getZ(),
                name,
                System.currentTimeMillis() + timerSeconds * 1000L,
                false
        );

        crates.put(id, crate);
        saveCrates();
        ensureHologram(crate);
        updateHologram(crate);

        player.sendMessage(Component.text("Крейт «" + name + "» создан. Таймер: "
                + formatDuration(timerSeconds) + ".", NamedTextColor.GREEN));
    }

    private void removeLookingAtCrate(Player player) {
        Block target = player.getTargetBlockExact(6);
        if (target == null) {
            player.sendMessage(Component.text("Смотри на бочку крейта.", NamedTextColor.RED));
            return;
        }

        CrateData crate = findCrate(target);
        if (crate == null) {
            player.sendMessage(Component.text("На этот блок не зарегистрирован крейт.", NamedTextColor.RED));
            return;
        }

        removeCrate(crate, true);
        player.sendMessage(Component.text("Крейт удалён.", NamedTextColor.GREEN));
    }

    private void openEditor(Player player) {
        EditorHolder holder = new EditorHolder();
        Inventory inventory = Bukkit.createInventory(holder, EDITOR_SIZE, Component.text("Редактор крейта"));
        holder.inventory = inventory;

        for (int slot = 0; slot < LOOT_SLOT_COUNT; slot++) {
            if (slot < lootEntries.size()) {
                inventory.setItem(slot, createDisplayItem(lootEntries.get(slot)));
            }
        }

        ItemStack filler = makeItem(Material.GRAY_STAINED_GLASS_PANE, " ", List.of());
        for (int slot = 27; slot <= 44; slot++) {
            inventory.setItem(slot, filler);
        }

        inventory.setItem(TIMER_SLOT, makeItem(
                Material.CLOCK,
                "§eВремя до открытия",
                List.of(
                        "§7Сейчас: §f" + timerSeconds + " сек. §8(" + formatDuration(timerSeconds) + ")",
                        "§7Нажми, чтобы изменить время",
                        "§8Новое время применяется к новым крейтам"
                )
        ));

        inventory.setItem(HELP_SLOT, makeItem(
                Material.BOOK,
                "§bКак пользоваться",
                List.of(
                        "§7Добавить предмет: положи его на курсор",
                        "§7и нажми по пустому слоту",
                        "§eSHIFT + ЛКМ §7по предмету — изменить шанс",
                        "§cПКМ §7по предмету — удалить его",
                        "§7SHIFT + ЛКМ в своём инвентаре — добавить",
                        "§7Шанс каждого предмета проверяется отдельно"
                )
        ));

        inventory.setItem(SAVE_SLOT, makeItem(
                Material.EMERALD_BLOCK,
                "§aСохранить и закрыть",
                List.of("§7Все изменения сохраняются автоматически")
        ));

        player.openInventory(inventory);
    }

    private ItemStack createDisplayItem(LootEntry entry) {
        ItemStack display = entry.item.clone();
        ItemMeta meta = display.getItemMeta();
        if (meta != null) {
            List<Component> lore = meta.lore() == null ? new ArrayList<>() : new ArrayList<>(meta.lore());
            if (!lore.isEmpty()) {
                lore.add(Component.empty());
            }
            lore.add(Component.text("Шанс появления: " + entry.chance + "%", NamedTextColor.YELLOW));
            lore.add(Component.text("SHIFT + ЛКМ — изменить шанс", NamedTextColor.GRAY));
            lore.add(Component.text("ПКМ — удалить из списка", NamedTextColor.GRAY));
            meta.lore(lore);
            display.setItemMeta(meta);
        }
        return display;
    }

    private ItemStack makeItem(Material material, String name, List<String> loreLines) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(name);
            if (!loreLines.isEmpty()) {
                meta.setLore(loreLines);
            }
            item.setItemMeta(meta);
        }
        return item;
    }

    @EventHandler
    public void onEditorClick(InventoryClickEvent event) {
        Inventory top = event.getView().getTopInventory();

        if (top.getHolder() instanceof EditorHolder) {
            event.setCancelled(true);
            if (!(event.getWhoClicked() instanceof Player player) || event.getClickedInventory() == null) {
                return;
            }

            if (event.getClickedInventory() == top) {
                int slot = event.getRawSlot();

                if (slot >= 0 && slot < LOOT_SLOT_COUNT) {
                    handleLootSlotClick(player, event, slot);
                } else if (slot == TIMER_SLOT) {
                    openNumberInput(player, new PendingInput(InputType.TIMER, -1), timerSeconds);
                } else if (slot == SAVE_SLOT) {
                    saveLoot();
                    saveCrates();
                    player.closeInventory();
                    player.sendMessage(Component.text("Настройки крейтов сохранены.", NamedTextColor.GREEN));
                }
                return;
            }

            if (event.getClickedInventory() == player.getInventory()
                    && event.isShiftClick()
                    && event.getCurrentItem() != null
                    && !event.getCurrentItem().getType().isAir()) {
                addItemFromPlayerInventory(player, event);
            }
            return;
        }

        if (top instanceof AnvilInventory && pendingInputs.containsKey(event.getWhoClicked().getUniqueId())) {
            event.setCancelled(true);
            if (event.getRawSlot() == 2 && event.getWhoClicked() instanceof Player player) {
                handleNumberInput(player, event);
            }
        }
    }

    private void handleLootSlotClick(Player player, InventoryClickEvent event, int slot) {
        if (slot < lootEntries.size()) {
            LootEntry entry = lootEntries.get(slot);

            if (event.getClick() == ClickType.SHIFT_LEFT) {
                openNumberInput(player, new PendingInput(InputType.CHANCE, slot), entry.chance);
                return;
            }

            if (event.isRightClick()) {
                lootEntries.remove(slot);
                returnItem(player, entry.item.clone());
                saveLoot();
                reopenEditor(player);
                return;
            }

            player.sendMessage(Component.text("SHIFT + ЛКМ — изменить шанс; ПКМ — удалить предмет.",
                    NamedTextColor.YELLOW));
            return;
        }

        ItemStack cursor = event.getCursor();
        if (cursor == null || cursor.getType().isAir()) {
            return;
        }

        if (lootEntries.size() >= LOOT_SLOT_COUNT) {
            player.sendMessage(Component.text("В редакторе уже 27 предметов — больше не помещается в бочку.",
                    NamedTextColor.RED));
            return;
        }

        lootEntries.add(new LootEntry(cursor.clone(), 100));
        event.setCursor(null);
        saveLoot();
        reopenEditor(player);
    }

    private void addItemFromPlayerInventory(Player player, InventoryClickEvent event) {
        if (lootEntries.size() >= LOOT_SLOT_COUNT) {
            player.sendMessage(Component.text("В редакторе уже 27 предметов.", NamedTextColor.RED));
            return;
        }

        ItemStack source = event.getCurrentItem();
        if (source == null || source.getType().isAir()) {
            return;
        }

        lootEntries.add(new LootEntry(source.clone(), 100));
        event.setCurrentItem(null);
        saveLoot();
        reopenEditor(player);
    }

    private void returnItem(Player player, ItemStack item) {
        Map<Integer, ItemStack> leftovers = player.getInventory().addItem(item);
        for (ItemStack leftover : leftovers.values()) {
            player.getWorld().dropItemNaturally(player.getLocation(), leftover);
        }
    }

    private void reopenEditor(Player player) {
        Bukkit.getScheduler().runTask(this, () -> {
            if (player.isOnline()) {
                openEditor(player);
            }
        });
    }

    @EventHandler
    public void onEditorDrag(InventoryDragEvent event) {
        Inventory top = event.getView().getTopInventory();
        if (top.getHolder() instanceof EditorHolder) {
            for (int rawSlot : event.getRawSlots()) {
                if (rawSlot < top.getSize()) {
                    event.setCancelled(true);
                    return;
                }
            }
        }

        if (top instanceof AnvilInventory && pendingInputs.containsKey(event.getWhoClicked().getUniqueId())) {
            event.setCancelled(true);
        }
    }

    private void openNumberInput(Player player, PendingInput pending, int initialValue) {
        pendingInputs.put(player.getUniqueId(), pending);

        AnvilInventory anvil = (AnvilInventory) Bukkit.createInventory(player, InventoryType.ANVIL);
        ItemStack paper = new ItemStack(Material.PAPER);
        ItemMeta meta = paper.getItemMeta();
        if (meta != null) {
            meta.displayName(Component.text(String.valueOf(initialValue)));
            paper.setItemMeta(meta);
        }
        anvil.setFirstItem(paper);
        player.openInventory(anvil);

        Bukkit.getScheduler().runTask(this, () -> {
            if (player.isOnline() && player.getOpenInventory() instanceof AnvilView view) {
                view.setRepairCost(0);
                view.setRepairItemCountCost(0);
                view.setMaximumRepairCost(0);
            }
        });

        String prompt = pending.type == InputType.CHANCE
                ? "Введи шанс от 1 до 100 и забери результат наковальни."
                : "Введи время в секундах (1–604800) и забери результат наковальни.";
        player.sendMessage(Component.text(prompt, NamedTextColor.YELLOW));
    }

    private void handleNumberInput(Player player, InventoryClickEvent event) {
        PendingInput pending = pendingInputs.remove(player.getUniqueId());
        if (pending == null || !(event.getView() instanceof AnvilView anvilView)) {
            return;
        }

        String raw = anvilView.getRenameText();
        int value;
        try {
            value = Integer.parseInt(raw == null ? "" : raw.trim());
        } catch (NumberFormatException exception) {
            player.sendMessage(Component.text("Нужно ввести целое число.", NamedTextColor.RED));
            reopenEditor(player);
            return;
        }

        if (pending.type == InputType.CHANCE) {
            if (value < 1 || value > 100 || pending.slot < 0 || pending.slot >= lootEntries.size()) {
                player.sendMessage(Component.text("Шанс должен быть от 1 до 100.", NamedTextColor.RED));
                reopenEditor(player);
                return;
            }

            lootEntries.get(pending.slot).chance = value;
            saveLoot();
            player.sendMessage(Component.text("Шанс установлен: " + value + "%.", NamedTextColor.GREEN));
        } else {
            if (value < 1 || value > MAX_TIMER_SECONDS) {
                player.sendMessage(Component.text("Время должно быть от 1 до 604800 секунд.", NamedTextColor.RED));
                reopenEditor(player);
                return;
            }

            timerSeconds = value;
            saveLoot();
            player.sendMessage(Component.text("Время открытия новых крейтов: " + formatDuration(timerSeconds) + ".",
                    NamedTextColor.GREEN));
        }

        reopenEditor(player);
    }

    @EventHandler
    public void onAnvilClose(InventoryCloseEvent event) {
        UUID playerId = event.getPlayer().getUniqueId();
        if (event.getInventory() instanceof AnvilInventory && pendingInputs.remove(playerId) != null) {
            Bukkit.getScheduler().runTask(this, () -> {
                if (event.getPlayer() instanceof Player player && player.isOnline()) {
                    openEditor(player);
                }
            });
        }

        if (event.getInventory().getHolder() instanceof EditorHolder) {
            saveLoot();
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onBarrelInteract(PlayerInteractEvent event) {
        if (event.getClickedBlock() == null || event.getAction().name().equals("PHYSICAL")) {
            return;
        }

        CrateData crate = findCrate(event.getClickedBlock());
        if (crate == null || crate.opened) {
            return;
        }

        event.setCancelled(true);
        long remaining = Math.max(0L, (crate.endAtMillis - System.currentTimeMillis() + 999L) / 1000L);
        event.getPlayer().sendMessage(Component.text("Крейт «" + crate.name + "» ещё закрыт. Осталось: "
                + formatDuration(remaining) + ".", NamedTextColor.YELLOW));
    }

    @EventHandler(ignoreCancelled = true)
    public void onCrateBreak(BlockBreakEvent event) {
        CrateData crate = findCrate(event.getBlock());
        if (crate == null) {
            return;
        }

        if (!event.getPlayer().hasPermission("crate.admin")) {
            event.setCancelled(true);
            event.getPlayer().sendMessage(Component.text("Эту бочку нельзя сломать.", NamedTextColor.RED));
            return;
        }

        removeCrate(crate, false);
        event.getPlayer().sendMessage(Component.text("Крейт удалён.", NamedTextColor.GREEN));
    }

    @EventHandler
    public void onChunkLoad(ChunkLoadEvent event) {
        Chunk chunk = event.getChunk();
        Bukkit.getScheduler().runTask(this, () -> {
            for (CrateData crate : new ArrayList<>(crates.values())) {
                if (!crate.worldName.equals(chunk.getWorld().getName())
                        || (crate.x >> 4) != chunk.getX()
                        || (crate.z >> 4) != chunk.getZ()) {
                    continue;
                }

                if (!crate.opened && System.currentTimeMillis() >= crate.endAtMillis) {
                    unlockCrate(crate);
                } else {
                    ensureHologram(crate);
                    updateHologram(crate);
                }
            }
        });
    }

    private void tickCrates() {
        long now = System.currentTimeMillis();

        for (CrateData crate : new ArrayList<>(crates.values())) {
            World world = Bukkit.getWorld(crate.worldName);
            if (world == null || !world.isChunkLoaded(crate.x >> 4, crate.z >> 4)) {
                continue;
            }

            Block block = world.getBlockAt(crate.x, crate.y, crate.z);
            if (block.getType() != Material.BARREL) {
                removeCrate(crate, false);
                continue;
            }

            if (!crate.opened && now >= crate.endAtMillis) {
                unlockCrate(crate);
            } else {
                ensureHologram(crate);
                updateHologram(crate);
            }
        }
    }

    private void unlockCrate(CrateData crate) {
        World world = Bukkit.getWorld(crate.worldName);
        if (world == null || !world.isChunkLoaded(crate.x >> 4, crate.z >> 4)) {
            return;
        }

        Block block = world.getBlockAt(crate.x, crate.y, crate.z);
        if (block.getType() != Material.BARREL) {
            removeCrate(crate, false);
            return;
        }

        Barrel barrel = (Barrel) block.getState();
        Inventory inventory = barrel.getInventory();
        inventory.clear();

        for (LootEntry entry : lootEntries) {
            if (random.nextInt(100) + 1 <= entry.chance) {
                Map<Integer, ItemStack> leftovers = inventory.addItem(entry.item.clone());
                for (ItemStack leftover : leftovers.values()) {
                    world.dropItemNaturally(block.getLocation().add(0.5, 1.0, 0.5), leftover);
                }
            }
        }

        crate.opened = true;
        barrel.update(true, false);
        saveCrates();

        world.spawnParticle(org.bukkit.Particle.HAPPY_VILLAGER,
                block.getLocation().add(0.5, 1.0, 0.5), 18, 0.35, 0.25, 0.35, 0.02);
        world.playSound(block.getLocation(), org.bukkit.Sound.BLOCK_CHEST_OPEN, 0.8f, 1.0f);

        ensureHologram(crate);
        updateHologram(crate);
    }

    private void restoreLoadedCrates() {
        for (CrateData crate : new ArrayList<>(crates.values())) {
            World world = Bukkit.getWorld(crate.worldName);
            if (world == null || !world.isChunkLoaded(crate.x >> 4, crate.z >> 4)) {
                continue;
            }

            Block block = world.getBlockAt(crate.x, crate.y, crate.z);
            if (block.getType() != Material.BARREL) {
                removeCrate(crate, false);
                continue;
            }

            if (!crate.opened && System.currentTimeMillis() >= crate.endAtMillis) {
                unlockCrate(crate);
            } else {
                ensureHologram(crate);
                updateHologram(crate);
            }
        }
    }

    private void ensureHologram(CrateData crate) {
        World world = Bukkit.getWorld(crate.worldName);
        if (world == null || !world.isChunkLoaded(crate.x >> 4, crate.z >> 4)) {
            return;
        }

        String tag = hologramTag(crate.id);
        Chunk chunk = world.getChunkAt(crate.x >> 4, crate.z >> 4);

        for (Entity entity : chunk.getEntities()) {
            if (entity.getScoreboardTags().contains(tag) && entity instanceof TextDisplay display) {
                holograms.put(crate.id, display);
                return;
            }
        }

        Location location = new Location(world, crate.x + 0.5, crate.y + 1.35, crate.z + 0.5);
        TextDisplay display = world.spawn(location, TextDisplay.class, entity -> {
            entity.addScoreboardTag(tag);
            entity.setBillboard(Display.Billboard.CENTER);
            entity.setDefaultBackground(false);
            entity.setSeeThrough(true);
            entity.setShadowed(true);
            entity.setPersistent(true);
            entity.setInvulnerable(true);
            entity.setSilent(true);
            entity.setGravity(false);
            entity.setLineWidth(300);
            entity.setViewRange(1.5f);
        });
        holograms.put(crate.id, display);
    }

    private void updateHologram(CrateData crate) {
        TextDisplay display = holograms.get(crate.id);
        if (display == null || !display.isValid()) {
            ensureHologram(crate);
            display = holograms.get(crate.id);
        }
        if (display == null || !display.isValid()) {
            return;
        }

        Component title = Component.text(crate.name, NamedTextColor.GOLD);
        Component status;
        if (crate.opened) {
            status = Component.text("ОТКРЫТ", NamedTextColor.GREEN);
        } else {
            long seconds = Math.max(0L, (crate.endAtMillis - System.currentTimeMillis() + 999L) / 1000L);
            status = Component.text("Откроется через " + formatDuration(seconds), NamedTextColor.YELLOW);
        }
        display.text(title.append(Component.newline()).append(status));
    }

    private void removeCrate(CrateData crate, boolean restoreBarrelName) {
        crates.remove(crate.id);
        TextDisplay tracked = holograms.remove(crate.id);
        if (tracked != null && tracked.isValid()) {
            tracked.remove();
        }

        World world = Bukkit.getWorld(crate.worldName);
        if (world != null && world.isChunkLoaded(crate.x >> 4, crate.z >> 4)) {
            Chunk chunk = world.getChunkAt(crate.x >> 4, crate.z >> 4);
            String tag = hologramTag(crate.id);
            for (Entity entity : chunk.getEntities()) {
                if (entity.getScoreboardTags().contains(tag)) {
                    entity.remove();
                }
            }

            if (restoreBarrelName) {
                Block block = world.getBlockAt(crate.x, crate.y, crate.z);
                if (block.getType() == Material.BARREL) {
                    Barrel barrel = (Barrel) block.getState();
                    barrel.setCustomName(null);
                    barrel.update(true, false);
                }
            }
        }

        saveCrates();
    }

    private CrateData findCrate(Block block) {
        for (CrateData crate : crates.values()) {
            if (crate.worldName.equals(block.getWorld().getName())
                    && crate.x == block.getX()
                    && crate.y == block.getY()
                    && crate.z == block.getZ()) {
                return crate;
            }
        }
        return null;
    }

    private String hologramTag(UUID id) {
        return "voidcrate_" + id;
    }

    private String formatDuration(long totalSeconds) {
        long hours = totalSeconds / 3600;
        long minutes = (totalSeconds % 3600) / 60;
        long seconds = totalSeconds % 60;
        if (hours > 0) {
            return String.format(Locale.ROOT, "%02d:%02d:%02d", hours, minutes, seconds);
        }
        return String.format(Locale.ROOT, "%02d:%02d", minutes, seconds);
    }

    private void loadLoot() {
        if (!lootFile.exists()) {
            saveResource("loot.yml", false);
        }

        YamlConfiguration config = YamlConfiguration.loadConfiguration(lootFile);
        timerSeconds = Math.max(1, Math.min(MAX_TIMER_SECONDS, config.getInt("timer-seconds", 300)));
        lootEntries.clear();

        ConfigurationSection items = config.getConfigurationSection("items");
        if (items == null) {
            return;
        }

        for (String key : items.getKeys(false)) {
            ItemStack item = config.getItemStack("items." + key + ".item");
            int chance = config.getInt("items." + key + ".chance", 100);
            if (item != null && !item.getType().isAir() && chance >= 1 && chance <= 100
                    && lootEntries.size() < LOOT_SLOT_COUNT) {
                lootEntries.add(new LootEntry(item, chance));
            }
        }
    }

    private void saveLoot() {
        YamlConfiguration config = new YamlConfiguration();
        config.set("timer-seconds", timerSeconds);

        for (int i = 0; i < lootEntries.size(); i++) {
            LootEntry entry = lootEntries.get(i);
            config.set("items." + i + ".item", entry.item.clone());
            config.set("items." + i + ".chance", entry.chance);
        }

        try {
            config.save(lootFile);
        } catch (IOException exception) {
            getLogger().severe("Could not save loot.yml: " + exception.getMessage());
        }
    }

    private void loadCrates() {
        if (!cratesFile.exists()) {
            try {
                if (!cratesFile.createNewFile()) {
                    getLogger().warning("Could not create crates.yml.");
                }
            } catch (IOException exception) {
                getLogger().severe("Could not create crates.yml: " + exception.getMessage());
            }
        }

        YamlConfiguration config = YamlConfiguration.loadConfiguration(cratesFile);
        ConfigurationSection section = config.getConfigurationSection("crates");
        if (section == null) {
            return;
        }

        for (String key : section.getKeys(false)) {
            try {
                UUID id = UUID.fromString(key);
                String world = config.getString("crates." + key + ".world");
                if (world == null) {
                    continue;
                }
                CrateData crate = new CrateData(
                        id,
                        world,
                        config.getInt("crates." + key + ".x"),
                        config.getInt("crates." + key + ".y"),
                        config.getInt("crates." + key + ".z"),
                        config.getString("crates." + key + ".name", "Supply Crate"),
                        config.getLong("crates." + key + ".end-at", System.currentTimeMillis()),
                        config.getBoolean("crates." + key + ".opened", false)
                );
                crates.put(id, crate);
            } catch (IllegalArgumentException exception) {
                getLogger().warning("Skipping crate with invalid UUID: " + key);
            }
        }
    }

    private void saveCrates() {
        YamlConfiguration config = new YamlConfiguration();
        for (CrateData crate : crates.values()) {
            String path = "crates." + crate.id;
            config.set(path + ".world", crate.worldName);
            config.set(path + ".x", crate.x);
            config.set(path + ".y", crate.y);
            config.set(path + ".z", crate.z);
            config.set(path + ".name", crate.name);
            config.set(path + ".end-at", crate.endAtMillis);
            config.set(path + ".opened", crate.opened);
        }

        try {
            config.save(cratesFile);
        } catch (IOException exception) {
            getLogger().severe("Could not save crates.yml: " + exception.getMessage());
        }
    }

    private enum InputType {
        CHANCE,
        TIMER
    }

    private static final class PendingInput {
        private final InputType type;
        private final int slot;

        private PendingInput(InputType type, int slot) {
            this.type = type;
            this.slot = slot;
        }
    }

    private static final class LootEntry {
        private final ItemStack item;
        private int chance;

        private LootEntry(ItemStack item, int chance) {
            this.item = item.clone();
            this.chance = chance;
        }
    }

    private static final class CrateData {
        private final UUID id;
        private final String worldName;
        private final int x;
        private final int y;
        private final int z;
        private final String name;
        private final long endAtMillis;
        private boolean opened;

        private CrateData(UUID id, String worldName, int x, int y, int z,
                          String name, long endAtMillis, boolean opened) {
            this.id = id;
            this.worldName = worldName;
            this.x = x;
            this.y = y;
            this.z = z;
            this.name = name;
            this.endAtMillis = endAtMillis;
            this.opened = opened;
        }
    }

    private static final class EditorHolder implements InventoryHolder {
        private Inventory inventory;

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }
}