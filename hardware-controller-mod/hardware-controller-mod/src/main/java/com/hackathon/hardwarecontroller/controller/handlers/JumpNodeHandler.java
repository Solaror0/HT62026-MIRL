package com.hackathon.hardwarecontroller.controller.handlers;

import com.hackathon.hardwarecontroller.action.ActionQueue;
import com.hackathon.hardwarecontroller.controller.NodeHandler;
import com.hackathon.hardwarecontroller.protocol.HardwarePacket;
import com.hackathon.hardwarecontroller.state.NodeState;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;

/**
 * node_id == HardwareControllerMod.NODE_ID_JUMP (CV vertical head-bob detection).
 *
 * Reuses the picked_up field as a momentary trigger: the CV script sends a
 * brief 0 -> 1 -> 0 blip when it sees a fast-enough upward landmark
 * movement (see detect_jump() in cv_client_example.py). We only care about
 * the rising edge, exactly like the sword's left_click handling -- this
 * ensures one bob produces exactly one jump, not one jump per packet for
 * as long as the CV script happens to report it as true.
 *
 * We call player.jump() directly instead of toggling
 * client.options.jumpKey, for the same reason SwordNodeHandler calls
 * attackEntity()/attackBlock() directly instead of toggling attackKey: we
 * fire from ActionQueue at END_CLIENT_TICK, which is after Minecraft's own
 * per-tick input-polling loop already ran, so a key flip here wouldn't be
 * observed until a tick later (and, worse, might land on an unrelated
 * later tick and jump at the wrong moment). Calling jump() directly has no
 * dependency on tick ordering.
 *
 * We still gate on isOnGround() ourselves, since jump() has no such check
 * built in -- vanilla only ever calls it when the jump key is held AND the
 * player is grounded (or swimming), and skipping that check here would let
 * a rapid string of bobs jump repeatedly mid-air.
 */
public class JumpNodeHandler implements NodeHandler {

    @Override
    public void handlePacket(HardwarePacket packet, NodeState state, ActionQueue actionQueue) {
        boolean jumpSignal = packet.pickedUp();
        boolean risingEdge = state.consumePickedUpRisingEdge(jumpSignal);

        if (risingEdge) {
            actionQueue.enqueue(JumpNodeHandler::triggerJump);
        }
    }

    // ---- runs on the Minecraft client thread ----

    private static void triggerJump() {
        ClientPlayerEntity player = MinecraftClient.getInstance().player;
        if (player == null) {
            return;
        }

        if (player.isOnGround()) {
            player.jump();
        }
    }
}