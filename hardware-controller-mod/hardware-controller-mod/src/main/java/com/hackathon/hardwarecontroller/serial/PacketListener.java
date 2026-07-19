package com.hackathon.hardwarecontroller.serial;

import com.hackathon.hardwarecontroller.protocol.HardwarePacket;

/**
 * Callback invoked on the serial reader thread whenever a valid packet is
 * decoded. Implementations MUST NOT touch Minecraft client state directly --
 * see GameController, which only ever queues Runnables for the client thread.
 */
@FunctionalInterface
public interface PacketListener {
    void onPacket(HardwarePacket packet);
}
