package com.hackathon.hardwarecontroller.protocol;

import java.util.Optional;

/**
 * Parses one line of the CSV wire protocol into a HardwarePacket.
 *
 * Expected format: "node_id,picked_up,left_click,right_click"
 * e.g. "1,1,0,0"
 *
 * This is the ONLY place that knows about the wire format. If you switch
 * to JSON later, this is the only class that needs to change -- everything
 * downstream (SerialManager, GameController, node handlers) only ever sees
 * a HardwarePacket.
 */
public final class PacketParser {

    private static final int EXPECTED_FIELDS = 4;

    private PacketParser() {
    }

    public static Optional<HardwarePacket> parse(String rawLine) {
        if (rawLine == null) {
            return Optional.empty();
        }

        String line = rawLine.trim();
        if (line.isEmpty()) {
            return Optional.empty();
        }

        String[] parts = line.split(",");
        if (parts.length != EXPECTED_FIELDS) {
            return Optional.empty();
        }

        try {
            int nodeId = Integer.parseInt(parts[0].trim());
            boolean pickedUp = parseBoolFlag(parts[1]);
            boolean leftClick = parseBoolFlag(parts[2]);
            boolean rightClick = parseBoolFlag(parts[3]);
            return Optional.of(new HardwarePacket(nodeId, pickedUp, leftClick, rightClick));
        } catch (NumberFormatException e) {
            // Malformed line (e.g. torn during boot, garbage on the wire).
            // Drop it silently -- another valid packet arrives in ~100ms.
            return Optional.empty();
        }
    }

    private static boolean parseBoolFlag(String field) {
        return "1".equals(field.trim());
    }
}
