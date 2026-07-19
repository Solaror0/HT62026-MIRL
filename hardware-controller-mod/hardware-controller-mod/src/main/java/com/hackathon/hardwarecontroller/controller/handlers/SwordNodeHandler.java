package com.hackathon.hardwarecontroller.controller.handlers;

import com.hackathon.hardwarecontroller.action.ActionQueue;
import com.hackathon.hardwarecontroller.controller.NodeHandler;
import com.hackathon.hardwarecontroller.protocol.HardwarePacket;
import com.hackathon.hardwarecontroller.state.NodeState;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.item.SwordItem;
import net.minecraft.network.packet.c2s.play.UpdateSelectedSlotC2SPacket;
import net.minecraft.text.Text;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.EntityHitResult;
import net.minecraft.util.hit.HitResult;

/**
 * node_id == 1 (Sword). See the top-level spec for behavior:
 *  - picked_up 0->1: auto-equip a sword from the hotbar.
 *  - picked_up 1->0: restore whatever slot was selected before we switched.
 *  - left_click rising edge: exactly one attack.
 *  - right_click: ignored (reserved for future use).
 */
public class SwordNodeHandler implements NodeHandler {

    @Override
    public void handlePacket(HardwarePacket packet, NodeState state, ActionQueue actionQueue) {
        // Runs on the SERIAL thread. Only decide *what* to do here; the
        // actual Minecraft mutations happen inside the queued Runnables,
        // which execute on the client thread.

        boolean isHeld = packet.pickedUp();
        boolean heldChanged = isHeld != state.isHeldByHardware();
        state.setHeldByHardware(isHeld);

        if (heldChanged) {
            if (isHeld) {
                actionQueue.enqueue(() -> onSwordPickedUp(state));
            } else {
                actionQueue.enqueue(() -> onSwordPutDown(state));
            }
        }

        // Rising-edge only: see NodeState#consumeLeftClickRisingEdge for why.
        boolean swingDetected = state.consumeLeftClickRisingEdge(packet.leftClick());
        if (swingDetected && isHeld) {
            actionQueue.enqueue(SwordNodeHandler::triggerAttack);
        }

        // right_click: reserved for future functionality, ignore for now.
    }

    // ---- everything below here runs on the Minecraft client thread ----

    private static void onSwordPickedUp(NodeState state) {
        ClientPlayerEntity player = MinecraftClient.getInstance().player;
        if (player == null) {
            return;
        }

        int swordSlot = findSwordSlotInHotbar(player);
        if (swordSlot < 0) {
            player.sendMessage(Text.literal("[Hardware] No sword in hotbar to auto-equip."), true);
            return;
        }

        int currentSlot = player.getInventory().selectedSlot;
        if (currentSlot == swordSlot) {
            return; // Already selected, nothing to do.
        }

        state.setSlotBeforeAutoEquip(currentSlot);
        setSelectedSlot(player, swordSlot);
        state.setAutoEquipped(true);
    }

    private static void onSwordPutDown(NodeState state) {
        if (!state.isAutoEquipped()) {
            return; // We never switched slots for this pickup, nothing to restore.
        }

        ClientPlayerEntity player = MinecraftClient.getInstance().player;
        if (player != null) {
            int restoreSlot = state.getSlotBeforeAutoEquip();
            if (restoreSlot >= 0 && restoreSlot < 9) {
                setSelectedSlot(player, restoreSlot);
            }
        }

        state.setAutoEquipped(false);
        state.setSlotBeforeAutoEquip(-1);
    }

    private static int findSwordSlotInHotbar(ClientPlayerEntity player) {
        PlayerInventory inventory = player.getInventory();
        for (int slot = 0; slot < 9; slot++) {
            ItemStack stack = inventory.getStack(slot);
            if (stack.getItem() instanceof SwordItem) {
                return slot;
            }
        }
        return -1;
    }

    private static void setSelectedSlot(ClientPlayerEntity player, int slot) {
        player.getInventory().selectedSlot = slot;

        // Keep the server informed of the hotbar change (matters in
        // multiplayer / survival). NOTE: verify this class name against
        // your exact Yarn mappings build if it fails to resolve -- packet
        // class names occasionally get renamed between Minecraft versions.
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.getNetworkHandler() != null) {
            client.getNetworkHandler().sendPacket(new UpdateSelectedSlotC2SPacket(slot));
        }
    }

    private static void triggerAttack() {
        MinecraftClient client = MinecraftClient.getInstance();
        ClientPlayerEntity player = client.player;
        if (player == null) {
            return;
        }

        // NOTE: we previously simulated this by toggling
        // client.options.attackKey.setPressed(true/false), relying on
        // Minecraft's own "while (attackKey.wasPressed()) doAttack();"
        // loop to pick it up. That loop only runs during input handling
        // early in MinecraftClient.tick(), but we fire from
        // END_CLIENT_TICK (the end of the tick) via ActionQueue, so the
        // flip was never observed by that loop -- the message printed
        // (our own code ran fine) but no actual attack ever fired.
        //
        // Instead, do directly what doAttack() would do: swing the hand,
        // and if we're actually looking at something, tell the
        // interaction manager to hit it. This has no dependency on tick
        // ordering.
        HitResult target = client.crosshairTarget;
        if (target != null && target.getType() == HitResult.Type.ENTITY && client.interactionManager != null) {
            client.interactionManager.attackEntity(player, ((EntityHitResult) target).getEntity());
        } else if (target != null && target.getType() == HitResult.Type.BLOCK && client.interactionManager != null) {
            BlockHitResult blockHit = (BlockHitResult) target;
            client.interactionManager.attackBlock(blockHit.getBlockPos(), blockHit.getSide());
        }

        player.swingHand(Hand.MAIN_HAND);
        if (client.getNetworkHandler() != null) {
            client.getNetworkHandler().sendPacket(
                    new net.minecraft.network.packet.c2s.play.HandSwingC2SPacket(Hand.MAIN_HAND));
        }
    }
}
