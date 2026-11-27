package ru.essde.rustweapons.client;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.text.Text;
import org.lwjgl.glfw.GLFW;

public final class RustWeaponsClient implements ClientModInitializer {
    private static KeyBinding reloadKey;

    @Override
    public void onInitializeClient() {
        // Register the serverbound payload codec before any attempt to send it.
        // Without this registration Minecraft 1.21.4 cannot encode the custom_payload packet.
        PayloadTypeRegistry.playC2S().register(ReloadPayload.ID, ReloadPayload.CODEC);

        reloadKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.rustweapons.reload",
                InputUtil.Type.KEYSYM,
                GLFW.GLFW_KEY_R,
                "category.rustweapons.controls"
        ));

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            while (reloadKey.wasPressed()) {
                // Do not trigger reload while a chat, inventory, or another screen is open.
                if (client.currentScreen != null || client.player == null || client.getNetworkHandler() == null) {
                    continue;
                }

                if (!ClientPlayNetworking.canSend(ReloadPayload.ID)) {
                    client.player.sendMessage(Text.literal("Этот сервер не поддерживает RustWeapons R-reload."), true);
                    continue;
                }

                ClientPlayNetworking.send(new ReloadPayload());
            }
        });
    }
}
