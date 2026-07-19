package com.hackathon.hardwarecontroller.controller;

import com.hackathon.hardwarecontroller.action.ActionQueue;
import com.hackathon.hardwarecontroller.protocol.HardwarePacket;
import com.hackathon.hardwarecontroller.state.NodeState;

/**
 * Converts packets from ONE kind of hardware node (sword, shield, bow, ...)
 * into Minecraft actions.
 *
 * Implementations run on the SERIAL thread. They must never touch Minecraft
 * state directly -- instead, build a Runnable that does so and hand it to
 * {@code actionQueue.enqueue(...)}, which will run it on the client thread.
 *
 * To add a new prop: implement this interface and register it with
 * NodeHandlerRegistry in HardwareControllerMod. Nothing else needs to change.
 */
public interface NodeHandler {
    void handlePacket(HardwarePacket packet, NodeState state, ActionQueue actionQueue);
}
