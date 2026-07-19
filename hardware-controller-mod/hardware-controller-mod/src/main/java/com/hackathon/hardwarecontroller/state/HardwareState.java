package com.hackathon.hardwarecontroller.state;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Holds the live NodeState for every hardware node the mod has seen so far.
 * Thread-safe: written from the serial thread, read from the client thread.
 */
public class HardwareState {

    private final Map<Integer, NodeState> nodes = new ConcurrentHashMap<>();

    /** Returns the NodeState for this node id, creating one on first contact. */
    public NodeState getOrCreate(int nodeId) {
        return nodes.computeIfAbsent(nodeId, id -> new NodeState());
    }
}
