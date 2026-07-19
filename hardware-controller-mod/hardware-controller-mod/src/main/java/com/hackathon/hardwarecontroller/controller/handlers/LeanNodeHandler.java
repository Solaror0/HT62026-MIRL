package com.hackathon.hardwarecontroller.controller.handlers;

import com.hackathon.hardwarecontroller.action.ActionQueue;
import com.hackathon.hardwarecontroller.controller.NodeHandler;
import com.hackathon.hardwarecontroller.protocol.HardwarePacket;
import com.hackathon.hardwarecontroller.state.NodeState;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;

/**
 * node_id == HardwareControllerMod.NODE_ID_LEAN (CV body-lean detection).
 *
 * Reuses the existing 4-field packet instead of extending the wire format:
 *   picked_up   -> unused, always send 0
 *   left_click  -> 1 while the CV script thinks you're leaning left
 *   right_click -> 1 while the CV script thinks you're leaning right
 * (Both true at once is treated as "no lean" rather than guessing.)
 *
 * Unlike sword/shield, this does NOT enqueue a one-shot Runnable on change --
 * turning needs to keep happening every tick for as long as the lean is
 * held, not just once when it starts. So handlePacket() only records the
 * direction (cheap volatile write, safe from the serial thread); the actual
 * player.setYaw() call happens once per client tick via tick(), which
 * HardwareControllerMod calls from its existing END_CLIENT_TICK listener
 * (client thread only -- safe to touch player state directly there).
 */
public class LeanNodeHandler implements NodeHandler {

    /** Degrees to turn per client tick while a lean is active. Tune to taste. */
    private static final float TURN_SPEED_DEGREES_PER_TICK = 3.0f;

    @Override
    public void handlePacket(HardwarePacket packet, NodeState state, ActionQueue actionQueue) {
        boolean leanLeft = packet.leftClick();
        boolean leanRight = packet.rightClick();

        int direction;
        if (leanLeft == leanRight) {
            direction = 0; // neither, or both (ambiguous) -> don't turn
        } else {
            direction = leanLeft ? -1 : 1;
        }

        state.setLeanDirection(direction);
    }

    /**
     * Call once per client tick. Must run on the client thread (it touches
     * player state directly, with no ActionQueue involved, since it needs
     * to happen continuously rather than in response to a single packet).
     */
    public static void tick(NodeState state) {
        int direction = state.getLeanDirection();
        if (direction == 0) {
            return;
        }

        ClientPlayerEntity player = MinecraftClient.getInstance().player;
        if (player == null) {
            return;
        }

        player.setYaw(player.getYaw() + direction * TURN_SPEED_DEGREES_PER_TICK);
    }
}