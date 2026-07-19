package com.hackathon.hardwarecontroller.protocol;

/**
 * A single decoded update from a hardware node (sword, shield, bow, etc.).
 *
 * Wire format (CSV, one line per packet):
 *   node_id,picked_up,left_click,right_click\n
 * Example:
 *   1,1,0,0\n
 *
 * This is intentionally a plain data record with no Minecraft dependencies,
 * so it can be unit-tested / reused outside the mod if needed.
 */
public record HardwarePacket(int nodeId, boolean pickedUp, boolean leftClick, boolean rightClick) {
}
