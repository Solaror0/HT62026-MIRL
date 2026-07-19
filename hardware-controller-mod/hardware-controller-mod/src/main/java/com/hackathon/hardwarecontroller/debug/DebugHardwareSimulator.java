package com.hackathon.hardwarecontroller.debug;

import com.hackathon.hardwarecontroller.HardwareControllerMod;
import com.hackathon.hardwarecontroller.controller.GameController;
import com.hackathon.hardwarecontroller.protocol.HardwarePacket;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.text.Text;
import org.lwjgl.glfw.GLFW;

/**
 * DEV/TEST ONLY. Lets you exercise the full pipeline (GameController ->
 * NodeHandler -> ActionQueue -> client tick) from the keyboard, with no
 * serial connection, ESP32, or CV script needed.
 *
 * Injects packets the exact same way SerialManager / TcpPacketServer would
 * -- straight into GameController.onPacket(...) -- so if it works here, it
 * works with real hardware/CV input too.
 *
 * Default keys (F6-F11 -- chosen because every keyboard has function keys,
 * unlike numpad, which laptops often lack):
 *   F6  - toggle simulated sword picked up / put down
 *   F7  - fire one simulated sword swing (attack)
 *   F8  - toggle simulated CV armor detection
 *   F9  - toggle simulated CV golden-apple detection
 *   F10 - toggle simulated shield picked up / put down
 *   F11 - HOLD to simulate right_click on the shield (raises the block
 *         while held, lowers it the instant you let go) -- unlike the
 *         other keys, this one is read as a live hold each tick rather
 *         than a single toggle, since that's what the real prop reports.
 *
 * Consider removing this class, or gating registration behind a debug
 * config flag, before a real demo -- it's a direct backdoor into the
 * hardware-event pipeline.
 */
public class DebugHardwareSimulator {

    private final GameController gameController;

    private boolean simulatedSwordHeld = false;
    private boolean simulatedArmorDetected = false;
    private boolean simulatedGoldenAppleDetected = false;
    private boolean simulatedShieldHeld = false;
    private boolean simulatedShieldBlocking = false;

    private KeyBinding toggleSwordHeldKey;
    private KeyBinding triggerAttackKey;
    private KeyBinding toggleArmorDetectedKey;
    private KeyBinding toggleGoldenAppleDetectedKey;
    private KeyBinding toggleShieldHeldKey;
    private KeyBinding holdShieldBlockKey;

    public DebugHardwareSimulator(GameController gameController) {
        this.gameController = gameController;
    }

    public void register() {
        toggleSwordHeldKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.hardwarecontroller.debug_toggle_sword",
                InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_F6,
                "category.hardwarecontroller.debug"));

        triggerAttackKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.hardwarecontroller.debug_attack",
                InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_F7,
                "category.hardwarecontroller.debug"));

        toggleArmorDetectedKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.hardwarecontroller.debug_toggle_armor",
                InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_F8,
                "category.hardwarecontroller.debug"));

        toggleGoldenAppleDetectedKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.hardwarecontroller.debug_toggle_golden_apple",
                InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_F9,
                "category.hardwarecontroller.debug"));

        toggleShieldHeldKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.hardwarecontroller.debug_toggle_shield",
                InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_F10,
                "category.hardwarecontroller.debug"));

        holdShieldBlockKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.hardwarecontroller.debug_hold_shield_block",
                InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_F11,
                "category.hardwarecontroller.debug"));

        ClientTickEvents.END_CLIENT_TICK.register(this::onClientTick);
    }

    private void onClientTick(MinecraftClient client) {
        while (toggleSwordHeldKey.wasPressed()) {
            simulatedSwordHeld = !simulatedSwordHeld;
            send(HardwareControllerMod.NODE_ID_SWORD, simulatedSwordHeld, false, false);
            notify(client, "Sword " + (simulatedSwordHeld ? "PICKED UP" : "PUT DOWN") + " (simulated)");
        }

        while (triggerAttackKey.wasPressed()) {
            // Send press then release so NodeState's rising-edge detector
            // sees a real 0->1->0 transition, just like a real hardware
            // click. Without the release, previousLeftClick stays true
            // forever and every press after the first is a no-op.
            send(HardwareControllerMod.NODE_ID_SWORD, simulatedSwordHeld, true, false);
            send(HardwareControllerMod.NODE_ID_SWORD, simulatedSwordHeld, false, false);
            notify(client, "Attack triggered (simulated)");
        }

        while (toggleArmorDetectedKey.wasPressed()) {
            simulatedArmorDetected = !simulatedArmorDetected;
            send(HardwareControllerMod.NODE_ID_CV_ARMOR, simulatedArmorDetected, false, false);
            notify(client, "CV armor detection " + (simulatedArmorDetected ? "ON" : "OFF") + " (simulated)");
        }

        while (toggleGoldenAppleDetectedKey.wasPressed()) {
            simulatedGoldenAppleDetected = !simulatedGoldenAppleDetected;
            send(HardwareControllerMod.NODE_ID_CV_GOLDEN_APPLE, simulatedGoldenAppleDetected, false, false);
            notify(client, "CV golden apple detection "
                    + (simulatedGoldenAppleDetected ? "ON" : "OFF") + " (simulated)");
        }

        while (toggleShieldHeldKey.wasPressed()) {
            simulatedShieldHeld = !simulatedShieldHeld;
            send(HardwareControllerMod.NODE_ID_SHIELD, simulatedShieldHeld, false, simulatedShieldBlocking);
            notify(client, "Shield " + (simulatedShieldHeld ? "PICKED UP" : "PUT DOWN") + " (simulated)");
        }

        // Live hold, not a toggle: send a new packet only when the actual
        // held-down state changes, same as the real prop's button would.
        boolean blockKeyDown = holdShieldBlockKey.isPressed();
        if (blockKeyDown != simulatedShieldBlocking) {
            simulatedShieldBlocking = blockKeyDown;
            send(HardwareControllerMod.NODE_ID_SHIELD, simulatedShieldHeld, false, simulatedShieldBlocking);
            notify(client, "Shield block " + (simulatedShieldBlocking ? "ON" : "OFF") + " (simulated)");
        }
    }

    private void send(int nodeId, boolean pickedUp, boolean leftClick, boolean rightClick) {
        gameController.onPacket(new HardwarePacket(nodeId, pickedUp, leftClick, rightClick));
    }

    private void notify(MinecraftClient client, String message) {
        if (client.player != null) {
            client.player.sendMessage(Text.literal("[Debug] " + message), true);
        }
    }
}