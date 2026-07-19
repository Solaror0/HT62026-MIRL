package com.hackathon.hardwarecontroller.controller;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map;

/**
 * Maps node_id -> NodeHandler. This is the single place that knows which
 * physical prop each id represents.
 *
 * node_id values are still being finalized on the hardware side except for
 * the sword (1). Add new mappings here as they're confirmed -- everything
 * else (SerialManager, HardwareState, GameController) is already
 * id-agnostic and needs no changes.
 */
public class NodeHandlerRegistry {

    private static final Logger LOGGER = LoggerFactory.getLogger("HardwareController/Registry");

    private final Map<Integer, NodeHandler> handlersByNodeId = new HashMap<>();

    public void register(int nodeId, NodeHandler handler) {
        handlersByNodeId.put(nodeId, handler);
    }

    /** Returns the handler for this node id, or null if none is registered yet. */
    public NodeHandler get(int nodeId) {
        NodeHandler handler = handlersByNodeId.get(nodeId);
        if (handler == null) {
            // Don't crash on unknown/not-yet-finalized node ids -- just log
            // once per occurrence so it's visible during hackathon debugging.
            LOGGER.debug("No handler registered for node_id={} (packet ignored)", nodeId);
        }
        return handler;
    }
}
