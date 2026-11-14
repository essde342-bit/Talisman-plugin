package ru.essde.talisman;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.inventory.EquipmentSlotGroup;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class TalismanPlugin extends JavaPlugin {

    private NamespacedKey talismanTypeKey;

    @Override
    public void onEnable() {
        talismanTypeKey = new NamespacedKey(this, "talisman_type");

        Objects.requireNonNull(getCommand("talisman"), "Command 'talisman' is missing from plugin.yml")
                .setExecutor(new TalismanCommand());

        getLogger().info("TalismanPlugin enabled. Fury Talisman is ready.");
    }

    /**
     * Creates the Fury Talisman.
     *
     * Its modifiers are attached to the OFFHAND equipment slot, so Minecraft
     * applies them automatically only while the item is held in the offhand.
     */
    private ItemStack createFuryTalisman() {
        ItemStack item = new ItemStack(Material.TOTEM_OF_UNDYING);
        ItemMeta meta = Objects.requireNonNull(item.getItemMeta());

        meta.displayName(
                Component.text("Талисман Ярости", NamedTextColor.RED)
                        .decorate(TextDecoration.BOLD)
                        .decoration(TextDecoration.ITALIC, false)
        );

        meta.lore(List.of(
                Component.text("Древний тотем, наполненный яростью.", NamedTextColor.DARK_GRAY)
                        .decoration(TextDecoration.ITALIC, false),
                Component.empty(),
                Component.text("Эффекты в левой руке:", NamedTextColor.GRAY)
                        .decoration(TextDecoration.ITALIC, false),
                Component.text("+3 к урону", NamedTextColor.RED)
                        .decoration(TextDecoration.ITALIC, false),
                Component.text("+15% к скорости атаки", NamedTextColor.GOLD)
                        .decoration(TextDecoration.ITALIC, false),
                Component.text("-1 сердечко максимального здоровья", NamedTextColor.RED)
                        .decoration(TextDecoration.ITALIC, false),
                Component.empty(),
                Component.text("Работает только в левой руке", NamedTextColor.DARK_PURPLE)
                        .decoration(TextDecoration.ITALIC, false)
        ));

        // A harmless enchantment gives the totem its enchanted glint.
        meta.addEnchant(Enchantment.UNBREAKING, 1, true);
        meta.addItemFlags(ItemFlag.HIDE_ENCHANTS);

        // Stable PDC marker: the name/lore alone cannot identify a talisman.
        meta.getPersistentDataContainer().set(
                talismanTypeKey,
                PersistentDataType.STRING,
                "fury"
        );

        // Flat +3 attack damage. This stacks with the main-hand weapon's damage
        // and with damage bonuses from enchantments such as Sharpness.
        meta.addAttributeModifier(
                Attribute.ATTACK_DAMAGE,
                new AttributeModifier(
                        new NamespacedKey(this, "fury_attack_damage"),
                        3.0,
                        AttributeModifier.Operation.ADD_NUMBER,
                        EquipmentSlotGroup.OFFHAND
                )
        );

        // Multiply the resulting attack speed by 1.15 (a true +15% modifier).
        meta.addAttributeModifier(
                Attribute.ATTACK_SPEED,
                new AttributeModifier(
                        new NamespacedKey(this, "fury_attack_speed"),
                        0.15,
                        AttributeModifier.Operation.MULTIPLY_SCALAR_1,
                        EquipmentSlotGroup.OFFHAND
                )
        );

        // -2 health points = -1 Minecraft heart.
        meta.addAttributeModifier(
                Attribute.MAX_HEALTH,
                new AttributeModifier(
                        new NamespacedKey(this, "fury_max_health"),
                        -2.0,
                        AttributeModifier.Operation.ADD_NUMBER,
                        EquipmentSlotGroup.OFFHAND
                )
        );

        item.setItemMeta(meta);
        return item;
    }

    @SuppressWarnings("unused")
    private boolean isFuryTalisman(ItemStack item) {
        if (item == null || item.getType() != Material.TOTEM_OF_UNDYING || !item.hasItemMeta()) {
            return false;
        }

        String type = item.getItemMeta()
                .getPersistentDataContainer()
                .get(talismanTypeKey, PersistentDataType.STRING);

        return "fury".equals(type);
    }

    private void giveTalisman(Player target) {
        ItemStack talisman = createFuryTalisman();
        Map<Integer, ItemStack> overflow = target.getInventory().addItem(talisman);

        // Do not delete the talisman when the inventory is full.
        overflow.values().forEach(leftover ->
                target.getWorld().dropItemNaturally(target.getLocation(), leftover)
        );
    }

    private final class TalismanCommand implements TabExecutor {

        @Override
        public boolean onCommand(
                CommandSender sender,
                Command command,
                String label,
                String[] args
        ) {
            if (args.length == 0 || (args.length == 1 && args[0].equalsIgnoreCase("fury"))) {
                if (!(sender instanceof Player player)) {
                    sender.sendMessage(Component.text(
                            "Из консоли используй: /talisman give <игрок> fury",
                            NamedTextColor.RED
                    ));
                    return true;
                }

                giveTalisman(player);
                player.sendMessage(Component.text(
                        "Ты получил Талисман Ярости! Возьми его в левую руку.",
                        NamedTextColor.GREEN
                ));
                return true;
            }

            if (args[0].equalsIgnoreCase("give")) {
                if (!sender.hasPermission("talisman.admin")) {
                    sender.sendMessage(Component.text("Недостаточно прав.", NamedTextColor.RED));
                    return true;
                }

                if (args.length < 2 || args.length > 3
                        || (args.length == 3 && !args[2].equalsIgnoreCase("fury"))) {
                    sender.sendMessage(Component.text(
                            "Использование: /talisman give <игрок> [fury]",
                            NamedTextColor.RED
                    ));
                    return true;
                }

                Player target = getServer().getPlayerExact(args[1]);
                if (target == null) {
                    sender.sendMessage(Component.text(
                            "Игрок должен быть онлайн.",
                            NamedTextColor.RED
                    ));
                    return true;
                }

                giveTalisman(target);
                target.sendMessage(Component.text(
                        "Ты получил Талисман Ярости! Возьми его в левую руку.",
                        NamedTextColor.GREEN
                ));
                sender.sendMessage(Component.text(
                        "Талисман Ярости выдан игроку " + target.getName() + ".",
                        NamedTextColor.GREEN
                ));
                return true;
            }

            sender.sendMessage(Component.text(
                    "Использование: /talisman [fury] или /talisman give <игрок> [fury]",
                    NamedTextColor.RED
            ));
            return true;
        }

        @Override
        public List<String> onTabComplete(
                CommandSender sender,
                Command command,
                String alias,
                String[] args
        ) {
            List<String> suggestions = new ArrayList<>();

            if (args.length == 1) {
                suggestions.add("fury");
                if (sender.hasPermission("talisman.admin")) {
                    suggestions.add("give");
                }
            } else if (args.length == 2 && args[0].equalsIgnoreCase("give")
                    && sender.hasPermission("talisman.admin")) {
                getServer().getOnlinePlayers().stream()
                        .map(Player::getName)
                        .forEach(suggestions::add);
            } else if (args.length == 3 && args[0].equalsIgnoreCase("give")
                    && sender.hasPermission("talisman.admin")) {
                suggestions.add("fury");
            }

            String prefix = args[args.length - 1].toLowerCase();
            return suggestions.stream()
                    .filter(value -> value.toLowerCase().startsWith(prefix))
                    .toList();
        }
    }
}
