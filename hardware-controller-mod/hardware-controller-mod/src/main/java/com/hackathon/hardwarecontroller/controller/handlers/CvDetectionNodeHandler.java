package com.hackathon.hardwarecontroller.controller.handlers;

import com.hackathon.hardwarecontroller.HardwareControllerMod;
import com.hackathon.hardwarecontroller.action.ActionQueue;
import com.hackathon.hardwarecontroller.controller.NodeHandler;
import com.hackathon.hardwarecontroller.protocol.HardwarePacket;
import com.hackathon.hardwarecontroller.state.NodeState;
import net.minecraft.client.MinecraftClient;
import net.minecraft.text.Text;

/**
 * PLACEHOLDER handler for CV-detected conditions (armor visible on camera,
 * holding a specific item, etc.).
 *
 * This only proves the pipeline end-to-end: it logs and shows an action-bar
 * message on state changes (picked_up field repurposed as "detected"). Swap
 * the TODO block below for real gameplay logic (equip armor, apply an
 * effect, whatever you land on) once your detection methodology is settled
 * -- everything upstream (TcpPacketServer, GameController, NodeState) is
 * already wired and won't need to change.
 *
 * left_click / right_click are unused for CV nodes for now.
 */
public class CvDetectionNodeHandler implements NodeHandler {

    private final String label;

    public CvDetectionNodeHandler(String label) {
        this.label = label;
    }

    @Override
    public void handlePacket(HardwarePacket packet, NodeState state, ActionQueue actionQueue) {
        boolean detected = packet.pickedUp();
        boolean changed = detected != state.isHeldByHardware();
        state.setHeldByHardware(detected);

        if (!changed) {
            return;
        }

        actionQueue.enqueue(() -> {
            HardwareControllerMod.LOGGER.info("[CV] {} -> {}", label, detected);

            MinecraftClient client = MinecraftClient.getInstance();
            if (client.player != null) {
                String message = "[CV] " + label + (detected ? " detected" : " no longer detected");
                client.player.sendMessage(Text.literal(message), true);
            }

            // TODO: replace with real gameplay logic, e.g.:
            //   if (detected) { equip armor pieces / apply effect / etc. }
            //   else { revert whatever the above did }
        });
    }
}
