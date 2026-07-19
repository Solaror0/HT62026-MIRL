package com.hackathon.hardwarecontroller.controller.handlers;

import com.hackathon.hardwarecontroller.action.ActionQueue;
import com.hackathon.hardwarecontroller.controller.NodeHandler;
import com.hackathon.hardwarecontroller.protocol.HardwarePacket;
import com.hackathon.hardwarecontroller.state.NodeState;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.item.ShieldItem;
import net.minecraft.text.Text;

/**
 * node_id == 2 (Shield). Mirrors the sword's picked_up / click split:
 *  - picked_up 0->1: auto-equip a shield into the offhand (if one isn't
 *    already there), remembering what was there before. Does NOT start
 *    blocking by itself.
 *  - picked_up 1->0: release the block key if we were blocking, then
 *    restore whatever was in the offhand before we auto-equipped.
 *  - right_click: NOT edge-detected like the sword's left_click -- this is
 *    a hold signal (the prop reports "button currently down" every
 *    packet), so we just mirror it straight onto Minecraft's real useKey
 *    for as long as it's true. Only has any effect while picked_up.
 *
 * We hold client.options.useKey down rather than calling
 * interactItem()/stopUsingItem() ourselves, because Minecraft's own tick
 * loop checks useKey.isPressed() every tick to decide whether to keep
 * blocking -- a one-shot interactItem() gets canceled again almost
 * immediately since vanilla notices the real mouse button isn't actually
 * down. Holding the key mirrors a real player holding right-click, and
 * correctly falls through to the offhand shield if the main-hand item
 * (e.g. a sword) has no right-click action of its own.
 */
public class ShieldNodeHandler implements NodeHandler {

    @Override
    public void handlePacket(HardwarePacket packet, NodeState state, ActionQueue actionQueue) {
        boolean isHeld = packet.pickedUp();
        boolean heldChanged = isHeld != state.isHeldByHardware();
        state.setHeldByHardware(isHeld);

        boolean wantsBlock = isHeld && packet.rightClick();
        boolean blockChanged = wantsBlock != state.isBlocking();
        state.setBlocking(wantsBlock);

        // Ordering matters: equip before raising the key, release the key
        // before un-equipping, so we never swap the shield out of the
        // offhand while still "using" it.
        if (heldChanged && isHeld) {
            actionQueue.enqueue(() -> onShieldPickedUp(state));
        }
        if (blockChanged) {
            actionQueue.enqueue(() -> setBlockKeyPressed(wantsBlock));
        }
        if (heldChanged && !isHeld) {
            actionQueue.enqueue(() -> onShieldPutDown(state));
        }
    }

    // ---- everything below here runs on the Minecraft client thread ----

    private static void onShieldPickedUp(NodeState state) {
        ClientPlayerEntity player = MinecraftClient.getInstance().player;
        if (player == null) {
            return;
        }

        if (player.getOffHandStack().getItem() instanceof ShieldItem) {
            state.setAutoEquipped(false); // already had one there, nothing to restore later
            return;
        }

        int shieldSlot = findShieldSlotInHotbar(player);
        if (shieldSlot < 0) {
            player.sendMessage(Text.literal("[Hardware] No shield in hotbar to auto-equip."), true);
            return;
        }

        swapIntoOffhand(player, state, shieldSlot);
        state.setAutoEquipped(true);
    }

    private static void onShieldPutDown(NodeState state) {
        ClientPlayerEntity player = MinecraftClient.getInstance().player;
        if (player != null && state.isAutoEquipped()) {
            restoreFromOffhand(player, state);
        }
        state.setAutoEquipped(false);
    }

    private static void setBlockKeyPressed(boolean pressed) {
        MinecraftClient.getInstance().options.useKey.setPressed(pressed);
    }

    private static int findShieldSlotInHotbar(ClientPlayerEntity player) {
        PlayerInventory inventory = player.getInventory();
        for (int slot = 0; slot < 9; slot++) {
            if (inventory.getStack(slot).getItem() instanceof ShieldItem) {
                return slot;
            }
        }
        return -1;
    }

    private static void swapIntoOffhand(ClientPlayerEntity player, NodeState state, int hotbarSlot) {
        PlayerInventory inventory = player.getInventory();
        ItemStack shieldStack = inventory.getStack(hotbarSlot);
        ItemStack previousOffhand = inventory.getStack(PlayerInventory.OFF_HAND_SLOT);

        inventory.setStack(PlayerInventory.OFF_HAND_SLOT, shieldStack);
        inventory.setStack(hotbarSlot, previousOffhand);
        state.setSlotBeforeAutoEquip(hotbarSlot);
    }

    private static void restoreFromOffhand(ClientPlayerEntity player, NodeState state) {
        int hotbarSlot = state.getSlotBeforeAutoEquip();
        if (hotbarSlot < 0 || hotbarSlot >= 9) {
            return;
        }

        PlayerInventory inventory = player.getInventory();
        ItemStack shieldStack = inventory.getStack(PlayerInventory.OFF_HAND_SLOT);
        ItemStack previousHotbarItem = inventory.getStack(hotbarSlot);

        inventory.setStack(hotbarSlot, shieldStack);
        inventory.setStack(PlayerInventory.OFF_HAND_SLOT, previousHotbarItem);
        state.setSlotBeforeAutoEquip(-1);
    }
}