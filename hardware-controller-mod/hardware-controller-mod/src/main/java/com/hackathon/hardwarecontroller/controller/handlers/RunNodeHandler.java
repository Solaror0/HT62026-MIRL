package com.hackathon.hardwarecontroller.controller.handlers;

import com.hackathon.hardwarecontroller.action.ActionQueue;
import com.hackathon.hardwarecontroller.controller.NodeHandler;
import com.hackathon.hardwarecontroller.protocol.HardwarePacket;
import com.hackathon.hardwarecontroller.state.NodeState;
import net.minecraft.client.MinecraftClient;

/**
 * node_id == HardwareControllerMod.NODE_ID_RUN (CV hand-pump "running in
 * place" detection). Replaces the earlier lean-forward-to-move node.
 *
 *   picked_up   -> 1 while hand motion is above the "moving" speed
 *                  threshold
 *   left_click  -> 1 while it's above the (higher) "sprinting" speed
 *                  threshold. Only meaningful alongside picked_up=1 -- see
 *                  detect_hand_running() in cv_client_example.py, which
 *                  never sets this without also setting picked_up.
 *   right_click -> unused, always 0
 *
 * Like ShieldNodeHandler's block key, we hold the real keys down
 * (forwardKey, sprintKey) rather than issuing one-shot move/sprint
 * commands, so Minecraft's own tick loop keeps handling movement physics
 * correctly (sneaking, swimming, stamina, etc. all still work as normal).
 */
public class RunNodeHandler implements NodeHandler {

    @Override
    public void handlePacket(HardwarePacket packet, NodeState state, ActionQueue actionQueue) {
        boolean moving = packet.pickedUp();
        boolean sprinting = moving && packet.leftClick();

        boolean movingChanged = moving != state.isHeldByHardware();
        boolean sprintingChanged = sprinting != state.isSprinting();

        state.setHeldByHardware(moving);
        state.setSprinting(sprinting);

        if (movingChanged) {
            actionQueue.enqueue(() -> setForwardKeyPressed(moving));
        }
        if (sprintingChanged) {
            actionQueue.enqueue(() -> setSprintKeyPressed(sprinting));
        }
    }

    // ---- runs on the Minecraft client thread ----

    private static void setForwardKeyPressed(boolean pressed) {
        MinecraftClient.getInstance().options.forwardKey.setPressed(pressed);
    }

    private static void setSprintKeyPressed(boolean pressed) {
        MinecraftClient.getInstance().options.sprintKey.setPressed(pressed);
    }
}