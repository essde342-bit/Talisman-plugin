package ru.essde.corpse;

import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Interaction;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.world.ChunkLoadEvent;
import io.papermc.paper.event.packet.PlayerChunkLoadEvent;
import io.papermc.paper.event.packet.PlayerChunkUnloadEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.event.PacketListenerAbstract;
import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.protocol.entity.data.EntityData;
import com.github.retrooper.packetevents.protocol.entity.data.EntityDataTypes;
import com.github.retrooper.packetevents.protocol.entity.pose.EntityPose;
import com.github.retrooper.packetevents.protocol.entity.type.EntityTypes;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.protocol.player.GameMode;
import com.github.retrooper.packetevents.protocol.player.UserProfile;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientInteractEntity;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerDestroyEntities;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityEquipment;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityMetadata;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerPlayerInfoRemove;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerPlayerInfoUpdate;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSpawnEntity;
import io.github.retrooper.packetevents.util.SpigotConversionUtil;

import java.nio.charset.StandardCharsets;
import java.util.EnumSet;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
public final class CorpsePlugin extends JavaPlugin implements Listener, CommandExecutor {

    private static final int INVENTORY_SIZE = 54;
    private static final int DEFAULT_LIFETIME_SECONDS = 1800;
    private static final byte ALL_SKIN_LAYERS = 0x7F;

    private final Map<UUID, CorpseRecord> corpses = new HashMap<>();
    private final Map<Integer, UUID> fakeEntityToCorpse = new ConcurrentHashMap<>();
    private PacketListenerAbstract interactionPacketListener;

    private NamespacedKey corpseIdKey;
    private NamespacedKey corpseRoleKey;
    private File corpsesFile;
    private int lifetimeSeconds;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        corpseIdKey = new NamespacedKey(this, "corpse_id");
        corpseRoleKey = new NamespacedKey(this, "corpse_role");
        corpsesFile = new File(getDataFolder(), "corpses.yml");
        lifetimeSeconds = Math.max(60,
                getConfig().getInt("corpse-lifetime-seconds", DEFAULT_LIFETIME_SECONDS));

        getServer().getPluginManager().registerEvents(this, this);
        registerInteractionPacketListener();
        if (getCommand("corpses") != null) {
            getCommand("corpses").setExecutor(this);
        }

        loadCorpses();
        for (Player viewer : Bukkit.getOnlinePlayers()) {
            for (CorpseRecord corpse : corpses.values()) {
                spawnCorpseForViewerIfNearby(corpse, viewer);
            }
        }
        getLogger().info("CorpsePlugin enabled. Loaded corpses: " + corpses.size());
    }

    @Override
    public void onDisable() {
        if (interactionPacketListener != null) {
            PacketEvents.getAPI().getEventManager().unregisterListener(interactionPacketListener);
            interactionPacketListener = null;
        }
        for (CorpseRecord corpse : corpses.values()) {
            if (corpse.expireTask != null) {
                corpse.expireTask.cancel();
            }
            for (Player viewer : Bukkit.getOnlinePlayers()) {
                hideCorpseFromViewer(corpse, viewer);
            }
        }
        saveCorpses();
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPlayerDeath(PlayerDeathEvent event) {
        if (event.getKeepInventory()) {
            return;
        }

        Player player = event.getPlayer();
        List<ItemStack> drops = new ArrayList<>();
        for (ItemStack item : event.getDrops()) {
            if (!isEmpty(item)) {
                drops.add(item.clone());
            }
        }

        CorpseRecord corpse = new CorpseRecord(
                UUID.randomUUID(),
                player.getUniqueId(),
                player.getName(),
                player.getWorld().getName(),
                player.getLocation().getX(),
                player.getLocation().getY(),
                player.getLocation().getZ(),
                player.getLocation().getYaw(),
                System.currentTimeMillis(),
                player.getInventory().getHeldItemSlot(),
                newFakeEntityId()
        );

        PlayerInventory inventory = player.getInventory();
        corpse.helmet = cloneItem(inventory.getHelmet());
        corpse.chestplate = cloneItem(inventory.getChestplate());
        corpse.leggings = cloneItem(inventory.getLeggings());
        corpse.boots = cloneItem(inventory.getBoots());
        corpse.offhand = cloneItem(inventory.getItemInOffHand());
        corpse.mainHand = cloneItem(inventory.getItemInMainHand());

        createCorpseInventory(corpse);
        int slot = 0;
        for (ItemStack item : drops) {
            if (slot < INVENTORY_SIZE) {
                corpse.inventory.setItem(slot++, item);
            } else {
                player.getWorld().dropItemNaturally(player.getLocation(), item);
            }
        }

        // Move all normal death drops into the shared corpse inventory.
        event.getDrops().clear();

        corpses.put(corpse.id, corpse);
        spawnVisuals(corpse);
        updateModel(corpse);
        for (Player viewer : Bukkit.getOnlinePlayers()) {
            spawnCorpseForViewerIfNearby(corpse, viewer);
        }
        scheduleDespawn(corpse);
        saveCorpses();
    }

    private void createCorpseInventory(CorpseRecord corpse) {
        CorpseInventoryHolder holder = new CorpseInventoryHolder(corpse.id);
        corpse.inventory = Bukkit.createInventory(
                holder, INVENTORY_SIZE, "§8Труп: §f" + corpse.ownerName);
        holder.setInventory(corpse.inventory);
    }

    // The visible body is a client-side player entity using the classic Steve model.
    // The server-side Interaction entity remains as a fallback click target.
    private void spawnVisuals(CorpseRecord corpse) {
        World world = Bukkit.getWorld(corpse.worldName);
        if (world == null) {
            getLogger().warning("World '" + corpse.worldName + "' is not loaded for corpse " + corpse.id);
            return;
        }

        int chunkX = ((int) Math.floor(corpse.x)) >> 4;
        int chunkZ = ((int) Math.floor(corpse.z)) >> 4;
        Chunk chunk = world.getChunkAt(chunkX, chunkZ);

        Entity oldBody = findTaggedEntity(chunk, corpse.id, "body");
        if (oldBody != null) {
            oldBody.remove();
        }
        Entity oldLabel = findTaggedEntity(chunk, corpse.id, "label");
        if (oldLabel != null) {
            oldLabel.remove();
        }

        Entity hitboxEntity = findTaggedEntity(chunk, corpse.id, "hitbox");
        if (hitboxEntity instanceof Interaction interaction) {
            corpse.hitbox = interaction;
        } else {
            Location hitboxLocation = corpse.location().add(0, 0.05, 0);
            corpse.hitbox = world.spawn(hitboxLocation, Interaction.class, interaction -> {
                interaction.setInteractionWidth(1.15f);
                interaction.setInteractionHeight(0.8f);
                interaction.setResponsive(true);
                interaction.setPersistent(true);
                interaction.setInvulnerable(true);
                interaction.setSilent(true);
                tagEntity(interaction, corpse.id, "hitbox");
            });
        }
    }

    private Entity findTaggedEntity(Chunk chunk, UUID corpseId, String role) {
        Entity found = null;
        for (Entity entity : chunk.getEntities()) {
            if (!isCorpseEntity(entity)) {
                continue;
            }
            String storedId = entity.getPersistentDataContainer()
                    .get(corpseIdKey, PersistentDataType.STRING);
            String storedRole = entity.getPersistentDataContainer()
                    .get(corpseRoleKey, PersistentDataType.STRING);
            if (!corpseId.toString().equals(storedId) || !role.equals(storedRole)) {
                continue;
            }
            if (found == null) {
                found = entity;
            } else {
                entity.remove();
            }
        }
        return found;
    }

    private void tagEntity(Entity entity, UUID corpseId, String role) {
        entity.getPersistentDataContainer().set(corpseIdKey, PersistentDataType.STRING, corpseId.toString());
        entity.getPersistentDataContainer().set(corpseRoleKey, PersistentDataType.STRING, role);
    }

    private boolean isCorpseEntity(Entity entity) {
        return entity.getPersistentDataContainer().has(corpseIdKey, PersistentDataType.STRING);
    }

    private UUID getCorpseId(Entity entity) {
        String raw = entity.getPersistentDataContainer().get(corpseIdKey, PersistentDataType.STRING);
        if (raw == null) {
            return null;
        }
        try {
            return UUID.fromString(raw);
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    private void updateModel(CorpseRecord corpse) {
        fakeEntityToCorpse.put(corpse.fakeEntityId, corpse.id);
        if (corpse.inventory == null) {
            return;
        }
        for (UUID viewerId : new ArrayList<>(corpse.visibleTo)) {
            Player viewer = Bukkit.getPlayer(viewerId);
            if (viewer != null && viewer.isOnline()) {
                sendCorpseEquipment(corpse, viewer);
            } else {
                corpse.visibleTo.remove(viewerId);
            }
        }
    }

    private void sendCorpseEquipment(CorpseRecord corpse, Player viewer) {
        List<com.github.retrooper.packetevents.protocol.player.Equipment> equipment = List.of(
                new com.github.retrooper.packetevents.protocol.player.Equipment(
                        com.github.retrooper.packetevents.protocol.player.EquipmentSlot.MAIN_HAND,
                        toPacketItem(stillLootable(corpse, corpse.mainHand) ? corpse.mainHand : null)),
                new com.github.retrooper.packetevents.protocol.player.Equipment(
                        com.github.retrooper.packetevents.protocol.player.EquipmentSlot.OFF_HAND,
                        toPacketItem(stillLootable(corpse, corpse.offhand) ? corpse.offhand : null)),
                new com.github.retrooper.packetevents.protocol.player.Equipment(
                        com.github.retrooper.packetevents.protocol.player.EquipmentSlot.HELMET,
                        toPacketItem(stillLootable(corpse, corpse.helmet) ? corpse.helmet : null)),
                new com.github.retrooper.packetevents.protocol.player.Equipment(
                        com.github.retrooper.packetevents.protocol.player.EquipmentSlot.CHEST_PLATE,
                        toPacketItem(stillLootable(corpse, corpse.chestplate) ? corpse.chestplate : null)),
                new com.github.retrooper.packetevents.protocol.player.Equipment(
                        com.github.retrooper.packetevents.protocol.player.EquipmentSlot.LEGGINGS,
                        toPacketItem(stillLootable(corpse, corpse.leggings) ? corpse.leggings : null)),
                new com.github.retrooper.packetevents.protocol.player.Equipment(
                        com.github.retrooper.packetevents.protocol.player.EquipmentSlot.BOOTS,
                        toPacketItem(stillLootable(corpse, corpse.boots) ? corpse.boots : null))
        );
        PacketEvents.getAPI().getPlayerManager().sendPacket(
                viewer, new WrapperPlayServerEntityEquipment(corpse.fakeEntityId, equipment));
    }

    private com.github.retrooper.packetevents.protocol.item.ItemStack toPacketItem(ItemStack item) {
        return isEmpty(item)
                ? com.github.retrooper.packetevents.protocol.item.ItemStack.EMPTY
                : SpigotConversionUtil.fromBukkitItemStack(item);
    }

    private boolean stillLootable(CorpseRecord corpse, ItemStack original) {
        if (isEmpty(original) || corpse.inventory == null) {
            return false;
        }
        for (ItemStack item : corpse.inventory.getContents()) {
            if (!isEmpty(item) && item.isSimilar(original)
                    && item.getAmount() >= original.getAmount()) {
                return true;
            }
        }
        return false;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onCorpseInteract(PlayerInteractEntityEvent event) {
        UUID corpseId = getCorpseId(event.getRightClicked());
        if (corpseId == null) {
            return;
        }
        event.setCancelled(true);
        openCorpse(event.getPlayer(), corpseId);
    }

    private void openCorpse(Player player, UUID corpseId) {
        CorpseRecord corpse = corpses.get(corpseId);
        if (corpse == null || corpse.inventory == null) {
            player.sendMessage("§cЭтот труп уже исчез.");
            return;
        }
        player.openInventory(corpse.inventory);
    }

    private void registerInteractionPacketListener() {
        interactionPacketListener = new PacketListenerAbstract() {
            @Override
            public void onPacketReceive(PacketReceiveEvent event) {
                if (!PacketType.Play.Client.INTERACT_ENTITY.equals(event.getPacketType())) {
                    return;
                }
                WrapperPlayClientInteractEntity packet = new WrapperPlayClientInteractEntity(event);
                UUID corpseId = fakeEntityToCorpse.get(packet.getEntityId());
                if (corpseId == null) {
                    return;
                }

                event.setCancelled(true);
                if (packet.getAction() != WrapperPlayClientInteractEntity.InteractAction.ATTACK) {
                    Player player = event.getPlayer();
                    Bukkit.getScheduler().runTask(CorpsePlugin.this, () -> {
                        if (player != null && player.isOnline()) {
                            openCorpse(player, corpseId);
                        }
                    });
                }
            }
        };
        PacketEvents.getAPI().getEventManager().registerListener(interactionPacketListener);
    }

    @EventHandler
    public void onPlayerChunkLoad(PlayerChunkLoadEvent event) {
        int chunkX = event.getChunk().getX();
        int chunkZ = event.getChunk().getZ();
        Player viewer = event.getPlayer();
        for (CorpseRecord corpse : corpses.values()) {
            if (isInChunk(corpse, event.getWorld().getName(), chunkX, chunkZ)) {
                spawnCorpseForViewer(corpse, viewer);
            }
        }
    }

    @EventHandler
    public void onPlayerChunkUnload(PlayerChunkUnloadEvent event) {
        int chunkX = event.getChunk().getX();
        int chunkZ = event.getChunk().getZ();
        Player viewer = event.getPlayer();
        for (CorpseRecord corpse : corpses.values()) {
            if (isInChunk(corpse, event.getWorld().getName(), chunkX, chunkZ)) {
                hideCorpseFromViewer(corpse, viewer);
            }
        }
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        UUID viewerId = event.getPlayer().getUniqueId();
        for (CorpseRecord corpse : corpses.values()) {
            corpse.visibleTo.remove(viewerId);
        }
    }

    private boolean isInChunk(CorpseRecord corpse, String worldName, int chunkX, int chunkZ) {
        return corpse.worldName.equals(worldName)
                && (((int) Math.floor(corpse.x)) >> 4) == chunkX
                && (((int) Math.floor(corpse.z)) >> 4) == chunkZ;
    }

    private void spawnCorpseForViewerIfNearby(CorpseRecord corpse, Player viewer) {
        World world = Bukkit.getWorld(corpse.worldName);
        if (world == null || viewer.getWorld() != world) {
            return;
        }
        double maxDistance = 192.0;
        if (viewer.getLocation().distanceSquared(corpse.location()) <= maxDistance * maxDistance) {
            spawnCorpseForViewer(corpse, viewer);
        }
    }

    private void spawnCorpseForViewer(CorpseRecord corpse, Player viewer) {
        if (!viewer.isOnline() || !corpse.visibleTo.add(viewer.getUniqueId())) {
            return;
        }

        UUID profileId = createSteveProfileId(corpse.id);
        UserProfile profile = new UserProfile(profileId, safeProfileName(corpse.ownerName));
        WrapperPlayServerPlayerInfoUpdate.PlayerInfo playerInfo =
                new WrapperPlayServerPlayerInfoUpdate.PlayerInfo(
                        profile, false, 0, GameMode.SURVIVAL, null, null, 0, true);

        PacketEvents.getAPI().getPlayerManager().sendPacket(viewer,
                new WrapperPlayServerPlayerInfoUpdate(
                        EnumSet.of(WrapperPlayServerPlayerInfoUpdate.Action.ADD_PLAYER,
                                WrapperPlayServerPlayerInfoUpdate.Action.UPDATE_LISTED),
                        playerInfo));

        Location modelLocation = corpse.location().add(0, 0.18, 0);
        com.github.retrooper.packetevents.protocol.world.Location packetLocation =
                new com.github.retrooper.packetevents.protocol.world.Location(
                        modelLocation.getX(), modelLocation.getY(), modelLocation.getZ(),
                        corpse.yaw, 0);

        PacketEvents.getAPI().getPlayerManager().sendPacket(viewer,
                new WrapperPlayServerSpawnEntity(corpse.fakeEntityId, profileId,
                        EntityTypes.PLAYER, packetLocation, corpse.yaw, 0, null));

        PacketEvents.getAPI().getPlayerManager().sendPacket(viewer,
                new WrapperPlayServerEntityMetadata(corpse.fakeEntityId, List.of(
                        new EntityData(6, EntityDataTypes.ENTITY_POSE, EntityPose.SWIMMING),
                        new EntityData(17, EntityDataTypes.BYTE, ALL_SKIN_LAYERS)
                )));
        sendCorpseEquipment(corpse, viewer);
    }

    private void hideCorpseFromViewer(CorpseRecord corpse, Player viewer) {
        if (!corpse.visibleTo.remove(viewer.getUniqueId())) {
            return;
        }
        PacketEvents.getAPI().getPlayerManager().sendPacket(viewer,
                new WrapperPlayServerDestroyEntities(corpse.fakeEntityId));
        PacketEvents.getAPI().getPlayerManager().sendPacket(viewer,
                new WrapperPlayServerPlayerInfoRemove(createSteveProfileId(corpse.id)));
    }

    private String safeProfileName(String ownerName) {
        if (ownerName == null || ownerName.isBlank()) {
            return "Corpse";
        }
        String cleaned = ownerName.replaceAll("[^A-Za-z0-9_]", "");
        if (cleaned.isBlank()) {
            cleaned = "Corpse";
        }
        return cleaned.substring(0, Math.min(16, cleaned.length()));
    }

    private UUID createSteveProfileId(UUID corpseId) {
        UUID profileId = UUID.nameUUIDFromBytes(
                ("CorpsePlugin:" + corpseId).getBytes(StandardCharsets.UTF_8));
        // An even UUID hash selects the classic wide-arm Steve default skin, not Alex.
        if ((profileId.hashCode() & 1) != 0) {
            profileId = new UUID(profileId.getMostSignificantBits(),
                    profileId.getLeastSignificantBits() ^ 1L);
        }
        return profileId;
    }

    private int newFakeEntityId() {
        int id;
        do {
            id = ThreadLocalRandom.current().nextInt(1_000_000_000, 2_000_000_000);
        } while (fakeEntityToCorpse.containsKey(id));
        return id;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void protectCorpse(EntityDamageEvent event) {
        if (isCorpseEntity(event.getEntity())) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onCorpseInventoryClick(InventoryClickEvent event) {
        scheduleSaveForView(event.getView().getTopInventory());
    }

    @EventHandler
    public void onCorpseInventoryDrag(InventoryDragEvent event) {
        scheduleSaveForView(event.getView().getTopInventory());
    }

    @EventHandler
    public void onCorpseInventoryClose(InventoryCloseEvent event) {
        scheduleSaveForView(event.getInventory());
    }

    private void scheduleSaveForView(Inventory inventory) {
        if (!(inventory.getHolder() instanceof CorpseInventoryHolder holder)) {
            return;
        }
        CorpseRecord corpse = corpses.get(holder.corpseId);
        if (corpse == null) {
            return;
        }
        Bukkit.getScheduler().runTask(this, () -> {
            if (corpses.containsKey(corpse.id)) {
                updateModel(corpse);
                saveCorpses();
            }
        });
    }

    // ---------------------------------------------------------------------
    // Persistent storage
    // ---------------------------------------------------------------------

    private void loadCorpses() {
        if (!corpsesFile.exists()) {
            return;
        }

        YamlConfiguration data = YamlConfiguration.loadConfiguration(corpsesFile);
        ConfigurationSection root = data.getConfigurationSection("corpses");
        if (root == null) {
            return;
        }

        long now = System.currentTimeMillis();
        for (String rawId : root.getKeys(false)) {
            try {
                UUID id = UUID.fromString(rawId);
                ConfigurationSection saved = root.getConfigurationSection(rawId);
                if (saved == null) {
                    continue;
                }

                String worldName = saved.getString("world", "");
                World world = Bukkit.getWorld(worldName);
                if (world == null) {
                    getLogger().warning("Skipping corpse " + rawId + ": world '"
                            + worldName + "' is not loaded.");
                    continue;
                }

                long createdAt = saved.getLong("created-at", now);
                if (now - createdAt >= lifetimeSeconds * 1000L) {
                    removeCorpseEntities(id, world, saved.getDouble("x"), saved.getDouble("z"));
                    continue;
                }

                CorpseRecord corpse = new CorpseRecord(
                        id,
                        UUID.fromString(saved.getString("owner-uuid", UUID.randomUUID().toString())),
                        saved.getString("owner-name", "Unknown"),
                        worldName,
                        saved.getDouble("x"),
                        saved.getDouble("y"),
                        saved.getDouble("z"),
                        (float) saved.getDouble("yaw"),
                        createdAt,
                        saved.getInt("held-slot", 0),
                        saved.getInt("fake-entity-id", 0) > 0
                                ? saved.getInt("fake-entity-id", 0) : newFakeEntityId()
                );

                createCorpseInventory(corpse);
                ConfigurationSection items = saved.getConfigurationSection("items");
                if (items != null) {
                    for (String rawSlot : items.getKeys(false)) {
                        try {
                            int slot = Integer.parseInt(rawSlot);
                            ItemStack item = items.getItemStack(rawSlot);
                            if (slot >= 0 && slot < INVENTORY_SIZE && !isEmpty(item)) {
                                corpse.inventory.setItem(slot, item);
                            }
                        } catch (NumberFormatException ignored) {
                            // Ignore invalid slot keys in a damaged data file.
                        }
                    }
                }

                ConfigurationSection model = saved.getConfigurationSection("model");
                if (model != null) {
                    corpse.helmet = cloneItem(model.getItemStack("helmet"));
                    corpse.chestplate = cloneItem(model.getItemStack("chestplate"));
                    corpse.leggings = cloneItem(model.getItemStack("leggings"));
                    corpse.boots = cloneItem(model.getItemStack("boots"));
                    corpse.mainHand = cloneItem(model.getItemStack("main-hand"));
                    corpse.offhand = cloneItem(model.getItemStack("offhand"));
                }

                corpses.put(id, corpse);
                fakeEntityToCorpse.put(corpse.fakeEntityId, corpse.id);
                spawnVisuals(corpse);
                updateModel(corpse);
                scheduleDespawn(corpse);
            } catch (Exception exception) {
                getLogger().warning("Could not load corpse '" + rawId + "': " + exception.getMessage());
            }
        }
        saveCorpses();
    }

    private void saveCorpses() {
        if (corpsesFile == null) {
            return;
        }

        YamlConfiguration data = new YamlConfiguration();
        for (CorpseRecord corpse : corpses.values()) {
            String base = "corpses." + corpse.id;
            data.set(base + ".owner-uuid", corpse.ownerId.toString());
            data.set(base + ".owner-name", corpse.ownerName);
            data.set(base + ".world", corpse.worldName);
            data.set(base + ".x", corpse.x);
            data.set(base + ".y", corpse.y);
            data.set(base + ".z", corpse.z);
            data.set(base + ".yaw", corpse.yaw);
            data.set(base + ".created-at", corpse.createdAt);
            data.set(base + ".held-slot", corpse.heldSlot);
            data.set(base + ".fake-entity-id", corpse.fakeEntityId);

            if (corpse.inventory != null) {
                for (int slot = 0; slot < INVENTORY_SIZE; slot++) {
                    ItemStack item = corpse.inventory.getItem(slot);
                    if (!isEmpty(item)) {
                        data.set(base + ".items." + slot, item.clone());
                    }
                }
            }

            saveItem(data, base + ".model.helmet", corpse.helmet);
            saveItem(data, base + ".model.chestplate", corpse.chestplate);
            saveItem(data, base + ".model.leggings", corpse.leggings);
            saveItem(data, base + ".model.boots", corpse.boots);
            saveItem(data, base + ".model.main-hand", corpse.mainHand);
            saveItem(data, base + ".model.offhand", corpse.offhand);
        }

        try {
            getDataFolder().mkdirs();
            data.save(corpsesFile);
        } catch (IOException exception) {
            getLogger().severe("Could not save corpses.yml: " + exception.getMessage());
        }
    }

    private void saveItem(YamlConfiguration data, String path, ItemStack item) {
        if (!isEmpty(item)) {
            data.set(path, item.clone());
        }
    }

    private void scheduleDespawn(CorpseRecord corpse) {
        if (corpse.expireTask != null) {
            corpse.expireTask.cancel();
        }
        long expiresAt = corpse.createdAt + lifetimeSeconds * 1000L;
        long remainingMillis = expiresAt - System.currentTimeMillis();
        if (remainingMillis <= 0) {
            removeCorpse(corpse.id);
            return;
        }
        long ticks = Math.max(1L, (remainingMillis + 49L) / 50L);
        corpse.expireTask = Bukkit.getScheduler().runTaskLater(
                this, () -> removeCorpse(corpse.id), ticks);
    }

    private void removeCorpse(UUID corpseId) {
        CorpseRecord corpse = corpses.remove(corpseId);
        if (corpse == null) {
            return;
        }
        fakeEntityToCorpse.remove(corpse.fakeEntityId);
        for (Player viewer : Bukkit.getOnlinePlayers()) {
            hideCorpseFromViewer(corpse, viewer);
        }
        if (corpse.expireTask != null) {
            corpse.expireTask.cancel();
        }

        World world = Bukkit.getWorld(corpse.worldName);
        if (world != null) {
            removeCorpseEntities(corpseId, world, corpse.x, corpse.z);
        }
        saveCorpses();
    }

    private void removeCorpseEntities(UUID corpseId, World world, double x, double z) {
        int chunkX = ((int) Math.floor(x)) >> 4;
        int chunkZ = ((int) Math.floor(z)) >> 4;
        for (Entity entity : world.getChunkAt(chunkX, chunkZ).getEntities()) {
            if (corpseId.equals(getCorpseId(entity))) {
                entity.remove();
            }
        }
    }

    @EventHandler
    public void onChunkLoad(ChunkLoadEvent event) {
        int chunkX = event.getChunk().getX();
        int chunkZ = event.getChunk().getZ();
        for (CorpseRecord corpse : new ArrayList<>(corpses.values())) {
            if (!corpse.worldName.equals(event.getWorld().getName())) {
                continue;
            }
            if ((((int) Math.floor(corpse.x)) >> 4) != chunkX
                    || (((int) Math.floor(corpse.z)) >> 4) != chunkZ) {
                continue;
            }
            Bukkit.getScheduler().runTask(this, () -> {
                if (corpses.containsKey(corpse.id)) {
                    spawnVisuals(corpse);
                }
            });
        }
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("corpse.admin")) {
            sender.sendMessage("§cНедостаточно прав. Нужно право corpse.admin.");
            return true;
        }

        if (args.length == 1 && args[0].equalsIgnoreCase("reload")) {
            reloadConfig();
            lifetimeSeconds = Math.max(60,
                    getConfig().getInt("corpse-lifetime-seconds", DEFAULT_LIFETIME_SECONDS));
            for (CorpseRecord corpse : corpses.values()) {
                scheduleDespawn(corpse);
            }
            sender.sendMessage("§aCorpsePlugin перезагружен. Время жизни трупа: "
                    + lifetimeSeconds + " сек.");
            return true;
        }

        if (args.length == 1 && args[0].equalsIgnoreCase("list")) {
            sender.sendMessage("§eАктивных трупов: §f" + corpses.size());
            return true;
        }

        if (args.length == 1 && args[0].equalsIgnoreCase("clear")) {
            for (UUID id : new ArrayList<>(corpses.keySet())) {
                removeCorpse(id);
            }
            sender.sendMessage("§aВсе трупы удалены.");
            return true;
        }

        sender.sendMessage("§6Команды CorpsePlugin:");
        sender.sendMessage("§e/corpses list §7— количество трупов");
        sender.sendMessage("§e/corpses reload §7— перечитать config.yml");
        sender.sendMessage("§e/corpses clear §7— удалить все трупы");
        return true;
    }

    private static ItemStack cloneItem(ItemStack item) {
        return isEmpty(item) ? null : item.clone();
    }

    private static boolean isEmpty(ItemStack item) {
        return item == null || item.getType().isAir() || item.getAmount() <= 0;
    }

    private static final class CorpseInventoryHolder implements InventoryHolder {
        private final UUID corpseId;
        private Inventory inventory;

        private CorpseInventoryHolder(UUID corpseId) {
            this.corpseId = corpseId;
        }

        private void setInventory(Inventory inventory) {
            this.inventory = inventory;
        }

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }

    private static final class CorpseRecord {
        private final UUID id;
        private final UUID ownerId;
        private final String ownerName;
        private final String worldName;
        private final double x;
        private final double y;
        private final double z;
        private final float yaw;
        private final long createdAt;
        private final int heldSlot;
        private final int fakeEntityId;
        private final Set<UUID> visibleTo = ConcurrentHashMap.newKeySet();

        private ItemStack helmet;
        private ItemStack chestplate;
        private ItemStack leggings;
        private ItemStack boots;
        private ItemStack mainHand;
        private ItemStack offhand;

        private Inventory inventory;
        private Interaction hitbox;
        private BukkitTask expireTask;

        private CorpseRecord(UUID id, UUID ownerId, String ownerName, String worldName,
                             double x, double y, double z, float yaw, long createdAt,
                             int heldSlot, int fakeEntityId) {
            this.id = id;
            this.ownerId = ownerId;
            this.ownerName = ownerName;
            this.worldName = worldName;
            this.x = x;
            this.y = y;
            this.z = z;
            this.yaw = yaw;
            this.createdAt = createdAt;
            this.heldSlot = heldSlot;
            this.fakeEntityId = fakeEntityId;
        }

        private Location location() {
            return new Location(Bukkit.getWorld(worldName), x, y, z, yaw, 0);
        }
    }
}
