package com.hackathon.hardwarecontroller.controller.handlers;

import com.hackathon.hardwarecontroller.action.ActionQueue;
import com.hackathon.hardwarecontroller.controller.NodeHandler;
import com.hackathon.hardwarecontroller.protocol.HardwarePacket;
import com.hackathon.hardwarecontroller.state.NodeState;
import net.minecraft.client.MinecraftClient;

/**
 * node_id == HardwareControllerMod.NODE_ID_FORWARD (CV forward-lean detection).
 *
 * Reuses the picked_up field as a hold signal: 1 while the CV script thinks
 * you're leaning forward, 0 otherwise. left_click/right_click are unused
 * (always sent as 0).
 *
 * Like ShieldNodeHandler's block key, we hold client.options.forwardKey
 * down for as long as picked_up is true rather than issuing a one-shot
 * "move" command -- Minecraft's own tick loop checks forwardKey.isPressed()
 * every tick to build the movement vector (sprinting, sneaking, swimming,
 * etc. all keep working correctly this way), so mirroring a held key is
 * simpler and more correct than trying to reimplement movement ourselves.
 */
public class ForwardLeanNodeHandler implements NodeHandler {

    @Override
    public void handlePacket(HardwarePacket packet, NodeState state, ActionQueue actionQueue) {
        boolean leaningForward = packet.pickedUp();
        boolean changed = leaningForward != state.isHeldByHardware();
        state.setHeldByHardware(leaningForward);

        if (changed) {
            actionQueue.enqueue(() -> setForwardKeyPressed(leaningForward));
        }
    }

    // ---- runs on the Minecraft client thread ----

    private static void setForwardKeyPressed(boolean pressed) {
        MinecraftClient.getInstance().options.forwardKey.setPressed(pressed);
    }
}