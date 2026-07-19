package com.hackathon.hardwarecontroller.controller.handlers;

import com.hackathon.hardwarecontroller.HardwareControllerMod;
import com.hackathon.hardwarecontroller.action.ActionQueue;
import com.hackathon.hardwarecontroller.controller.NodeHandler;
import com.hackathon.hardwarecontroller.protocol.HardwarePacket;
import com.hackathon.hardwarecontroller.state.NodeState;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.text.Text;

/**
 * node_id == HardwareControllerMod.NODE_ID_CV_ARMOR.
 *
 * picked_up here is "detected" (set by the blue-blob check in
 * cv_client_example.py's detect_armor()), and drives an auto-equip the same
 * shape as SwordNodeHandler's picked_up handling:
 *  - false -> true: find a diamond chestplate anywhere in the player's
 *    inventory and equip it, remembering whatever was in the chest slot
 *    before so it can be restored later.
 *  - true -> false: put the diamond chestplate back where it came from,
 *    and restore whatever was equipped before.
 *
 * Scoped to just the chestplate, matching the physical prop. If you add a
 * helmet/leggings/boots prop later, duplicate this for
 * EquipmentSlot.HEAD/LEGS/FEET (or generalize it to loop over a
 * slot->item mapping -- not worth the abstraction for one piece yet).
 * left_click / right_click are unused.
 *
 * CAVEAT: this mutates the player's inventory/equipment directly on the
 * client without an explicit sync packet -- there's no simple "equip
 * armor" C2S packet the way there is for hotbar selection. Same shortcut
 * ShieldNodeHandler takes for its offhand swap. Fine for a local
 * singleplayer demo; for real multiplayer correctness, swap this for an
 * interactionManager-driven "right-click the chestplate to equip it"
 * flow instead (see SwordNodeHandler#triggerAttack for the pattern of
 * calling the real interaction manager instead of faking client state).
 *
 * NOTE: verify player.equipStack(...) resolves against your exact Yarn
 * mappings build -- like UpdateSelectedSlotC2SPacket in SwordNodeHandler,
 * this kind of method occasionally gets renamed between versions.
 */
public class ArmorNodeHandler implements NodeHandler {

    @Override
    public void handlePacket(HardwarePacket packet, NodeState state, ActionQueue actionQueue) {
        boolean detected = packet.pickedUp();
        boolean changed = detected != state.isHeldByHardware();
        state.setHeldByHardware(detected);

        if (!changed) {
            return;
        }

        if (detected) {
            actionQueue.enqueue(() -> onArmorDetected(state));
        } else {
            actionQueue.enqueue(() -> onArmorNoLongerDetected(state));
        }
    }

    // ---- everything below here runs on the Minecraft client thread ----

    private static void onArmorDetected(NodeState state) {
        ClientPlayerEntity player = MinecraftClient.getInstance().player;
        if (player == null) {
            return;
        }

        ItemStack currentChest = player.getEquippedStack(EquipmentSlot.CHEST);
        if (currentChest.getItem() == Items.DIAMOND_CHESTPLATE) {
            return; // Already wearing one -- nothing to do.
        }

        int chestplateSlot = findDiamondChestplateInInventory(player);
        if (chestplateSlot < 0) {
            player.sendMessage(Text.literal("[Hardware] No diamond chestplate in inventory to auto-equip."), true);
            return;
        }

        PlayerInventory inventory = player.getInventory();
        ItemStack chestplateStack = inventory.getStack(chestplateSlot);

        // Put whatever we're replacing back where the chestplate came
        // from, then equip the chestplate. Order matters so we never end
        // up with two copies of the same stack floating around.
        inventory.setStack(chestplateSlot, currentChest);
        player.equipStack(EquipmentSlot.CHEST, chestplateStack);

        state.setSlotBeforeAutoEquip(chestplateSlot);
        state.setSavedItemStack(currentChest);
        state.setAutoEquipped(true);

        HardwareControllerMod.LOGGER.info("[Armor] Auto-equipped diamond chestplate from inventory slot {}", chestplateSlot);
    }

    private static void onArmorNoLongerDetected(NodeState state) {
        if (!state.isAutoEquipped()) {
            return; // We never equipped anything for this node, nothing to revert.
        }

        ClientPlayerEntity player = MinecraftClient.getInstance().player;
        if (player != null) {
            ItemStack chestplateStack = player.getEquippedStack(EquipmentSlot.CHEST);
            ItemStack previousChest = state.getSavedItemStack();
            int returnSlot = state.getSlotBeforeAutoEquip();

            player.equipStack(EquipmentSlot.CHEST, previousChest);

            if (returnSlot >= 0 && returnSlot < 36) {
                PlayerInventory inventory = player.getInventory();
                if (inventory.getStack(returnSlot).isEmpty()) {
                    inventory.setStack(returnSlot, chestplateStack);
                } else {
                    // Original slot got filled by something else in the
                    // meantime -- don't clobber it, just place the
                    // chestplate anywhere free (or drop it as a last resort).
                    if (!inventory.insertStack(chestplateStack)) {
                        player.dropItem(chestplateStack, false);
                    }
                }
            }
        }

        state.setAutoEquipped(false);
        state.setSlotBeforeAutoEquip(-1);
        state.setSavedItemStack(ItemStack.EMPTY);
    }

    private static int findDiamondChestplateInInventory(ClientPlayerEntity player) {
        PlayerInventory inventory = player.getInventory();
        // 0-35 covers the hotbar (0-8) and main storage (9-35); armor and
        // offhand live at higher indices in the combined view and are
        // deliberately excluded here.
        for (int slot = 0; slot < 36; slot++) {
            if (inventory.getStack(slot).getItem() == Items.DIAMOND_CHESTPLATE) {
                return slot;
            }
        }
        return -1;
    }
}