package com.hackathon.hardwarecontroller.controller;

import com.hackathon.hardwarecontroller.action.ActionQueue;
import com.hackathon.hardwarecontroller.protocol.HardwarePacket;
import com.hackathon.hardwarecontroller.serial.PacketListener;
import com.hackathon.hardwarecontroller.state.HardwareState;
import com.hackathon.hardwarecontroller.state.NodeState;

/**
 * Receives parsed packets (called from the serial thread) and dispatches
 * them to the appropriate NodeHandler. This class itself never touches
 * Minecraft state -- it only routes, and the handlers it calls only ever
 * enqueue Runnables for the client thread.
 */
public class GameController implements PacketListener {

    private final HardwareState hardwareState;
    private final ActionQueue actionQueue;
    private final NodeHandlerRegistry registry;

    public GameController(HardwareState hardwareState, ActionQueue actionQueue, NodeHandlerRegistry registry) {
        this.hardwareState = hardwareState;
        this.actionQueue = actionQueue;
        this.registry = registry;
    }

    @Override
    public void onPacket(HardwarePacket packet) {
        // Called on the serial thread.
        NodeHandler handler = registry.get(packet.nodeId());
        if (handler == null) {
            return; // Unknown / not-yet-mapped node id -- ignore gracefully.
        }

        NodeState state = hardwareState.getOrCreate(packet.nodeId());
        handler.handlePacket(packet, state, actionQueue);
    }
}
