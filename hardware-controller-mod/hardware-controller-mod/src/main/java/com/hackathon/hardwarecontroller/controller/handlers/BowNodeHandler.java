package com.hackathon.hardwarecontroller.controller.handlers;

import com.hackathon.hardwarecontroller.action.ActionQueue;
import com.hackathon.hardwarecontroller.controller.NodeHandler;
import com.hackathon.hardwarecontroller.protocol.HardwarePacket;
import com.hackathon.hardwarecontroller.state.NodeState;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.BowItem;
import net.minecraft.item.ItemStack;
import net.minecraft.network.packet.c2s.play.UpdateSelectedSlotC2SPacket;
import net.minecraft.text.Text;

/**
 * node_id == HardwareControllerMod.NODE_ID_BOW (3).
 *
 * The bow's pickup sensor (picked_up field) isn't wired up yet -- per the
 * hardware team, that IMU circuit is currently open -- so both actions are
 * collapsed onto right_click instead:
 *
 *  - The FIRST right_click rising edge, while the bow isn't yet equipped,
 *    is consumed purely as "pick up the bow": auto-equips a bow from the
 *    hotbar into the main hand. It does NOT also start a draw -- that
 *    press is swallowed entirely, so a single tap just equips.
 *  - Every right_click AFTER that (once equipped) is mirrored straight
 *    onto client.options.useKey, exactly like ShieldNodeHandler mirrors
 *    the block key: press to start drawing, release to fire. Minecraft's
 *    own tick loop handles the actual charge-up/release physics for as
 *    long as useKey stays held for as long as the real trigger is held --
 *    same reasoning as the shield (a one-shot interactItem() call gets
 *    canceled again almost immediately, since vanilla notices the real
 *    mouse button isn't actually down).
 *
 * picked_up and left_click are both ignored for now (reserved -- wire
 * picked_up in once the pickup sensor is connected, and prefer it over
 * this right_click-based fallback at that point).
 *
 * There's currently no "put the bow down" signal, so once auto-equipped it
 * just stays equipped. Add a put-down path here once one exists, mirroring
 * SwordNodeHandler#onSwordPutDown.
 */
public class BowNodeHandler implements NodeHandler {

    @Override
    public void handlePacket(HardwarePacket packet, NodeState state, ActionQueue actionQueue) {
        boolean rightClickSignal = packet.rightClick();
        boolean risingEdge = state.consumeRightClickRisingEdge(rightClickSignal);

        if (risingEdge && !state.isHeldByHardware()) {
            // First press since not-equipped: this is the pickup click.
            // Mark equipped now (on the serial thread) so a rapid second
            // press can't race this check before the queued equip runs.
            state.setHeldByHardware(true);
            actionQueue.enqueue(() -> onBowPickedUp(state));
            return;
        }

        if (!state.isHeldByHardware()) {
            return; // Not equipped yet, and this wasn't a pickup edge.
        }

        boolean wantsDraw = rightClickSignal;
        boolean drawChanged = wantsDraw != state.isDrawingBow();
        state.setDrawingBow(wantsDraw);

        if (drawChanged) {
            actionQueue.enqueue(() -> setDrawKeyPressed(wantsDraw));
        }

        // picked_up, left_click: reserved for future use (see class doc).
    }

    // ---- everything below here runs on the Minecraft client thread ----

    private static void onBowPickedUp(NodeState state) {
        ClientPlayerEntity player = MinecraftClient.getInstance().player;
        if (player == null) {
            return;
        }

        int bowSlot = findBowSlotInHotbar(player);
        if (bowSlot < 0) {
            player.sendMessage(Text.literal("[Hardware] No bow in hotbar to auto-equip."), true);
            return;
        }

        int currentSlot = player.getInventory().selectedSlot;
        if (currentSlot != bowSlot) {
            state.setSlotBeforeAutoEquip(currentSlot);
            setSelectedSlot(player, bowSlot);
            state.setAutoEquipped(true);
        }
    }

    private static void setDrawKeyPressed(boolean pressed) {
        MinecraftClient.getInstance().options.useKey.setPressed(pressed);
    }

    private static int findBowSlotInHotbar(ClientPlayerEntity player) {
        PlayerInventory inventory = player.getInventory();
        for (int slot = 0; slot < 9; slot++) {
            ItemStack stack = inventory.getStack(slot);
            if (stack.getItem() instanceof BowItem) {
                return slot;
            }
        }
        return -1;
    }

    private static void setSelectedSlot(ClientPlayerEntity player, int slot) {
        player.getInventory().selectedSlot = slot;

        // Keep the server informed of the hotbar change (matters in
        // multiplayer / survival) -- same pattern as SwordNodeHandler.
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.getNetworkHandler() != null) {
            client.getNetworkHandler().sendPacket(new UpdateSelectedSlotC2SPacket(slot));
        }
    }
}