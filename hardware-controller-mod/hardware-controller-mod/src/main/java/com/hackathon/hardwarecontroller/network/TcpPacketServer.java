package com.hackathon.hardwarecontroller.network;

import com.hackathon.hardwarecontroller.protocol.HardwarePacket;
import com.hackathon.hardwarecontroller.protocol.PacketParser;
import com.hackathon.hardwarecontroller.serial.PacketListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Local-machine-only TCP server that accepts packets from a companion
 * process -- e.g. a Python CV script -- using the SAME CSV wire protocol as
 * the serial link: "node_id,picked_up,left_click,right_click\n".
 *
 * This lets software-detected events (armor visible on camera, holding a
 * golden apple, etc.) plug into the exact same GameController / NodeHandler
 * pipeline as physical ESP32 props. Nothing downstream needs to know or
 * care whether a packet came from serial or from CV.
 *
 * Bound to 127.0.0.1 only (loopback) -- never reachable from the network.
 * Accepts one CV client connection at a time; if it disconnects, goes back
 * to waiting for a new one (handy for restarting your Python script while
 * testing without restarting Minecraft).
 */
public class TcpPacketServer {

    private static final Logger LOGGER = LoggerFactory.getLogger("HardwareController/CV");

    private final int port;
    private final PacketListener listener;
    private final AtomicBoolean running = new AtomicBoolean(false);

    private Thread acceptThread;
    private volatile ServerSocket serverSocket;

    public TcpPacketServer(int port, PacketListener listener) {
        this.port = port;
        this.listener = listener;
    }

    public void start() {
        if (!running.compareAndSet(false, true)) {
            LOGGER.warn("TcpPacketServer already started, ignoring duplicate start()");
            return;
        }
        acceptThread = new Thread(this::acceptLoop, "hardware-controller-cv-tcp");
        acceptThread.setDaemon(true);
        acceptThread.start();
    }

    public void stop() {
        running.set(false);
        ServerSocket s = this.serverSocket;
        if (s != null) {
            try {
                s.close();
            } catch (IOException ignored) {
                // Closing an already-dead socket, safe to ignore.
            }
        }
        if (acceptThread != null) {
            acceptThread.interrupt();
        }
    }

    private void acceptLoop() {
        try {
            serverSocket = new ServerSocket(port, 1, InetAddress.getLoopbackAddress());
            LOGGER.info("CV TCP server listening on 127.0.0.1:{}", port);
        } catch (IOException e) {
            LOGGER.error("Could not bind CV TCP server on port {}: {}", port, e.getMessage());
            return;
        }

        while (running.get()) {
            try (Socket client = serverSocket.accept()) {
                LOGGER.info("CV client connected from {}", client.getRemoteSocketAddress());
                handleClient(client);
            } catch (IOException e) {
                if (running.get()) {
                    LOGGER.warn("CV TCP accept loop error: {}", e.getMessage());
                }
            }
        }
    }

    private void handleClient(Socket client) throws IOException {
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(client.getInputStream(), StandardCharsets.US_ASCII))) {

            String line;
            while (running.get() && (line = reader.readLine()) != null) {
                Optional<HardwarePacket> packet = PacketParser.parse(line);
                if (packet.isPresent()) {
                    try {
                        listener.onPacket(packet.get());
                    } catch (Exception e) {
                        LOGGER.error("Error handling CV packet {}", packet.get(), e);
                    }
                } else {
                    LOGGER.debug("Ignoring malformed CV line: '{}'", line);
                }
            }
        } finally {
            LOGGER.info("CV client disconnected");
        }
    }
}
