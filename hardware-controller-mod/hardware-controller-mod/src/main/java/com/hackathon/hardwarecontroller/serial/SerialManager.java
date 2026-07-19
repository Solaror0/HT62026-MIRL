package com.hackathon.hardwarecontroller.serial;

import com.fazecast.jSerialComm.SerialPort;
import com.hackathon.hardwarecontroller.protocol.HardwarePacket;
import com.hackathon.hardwarecontroller.protocol.PacketParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Owns the connection to the master ESP32 and a dedicated background thread
 * that continuously reads lines off it.
 *
 * IMPORTANT: this class never touches Minecraft state. It only parses lines
 * into HardwarePacket objects and forwards them to a PacketListener. What the
 * listener does with them (i.e. GameController) is responsible for keeping
 * Minecraft mutations on the client thread.
 */
public class SerialManager {

    private static final Logger LOGGER = LoggerFactory.getLogger("HardwareController/Serial");

    private static final int BAUD_RATE = 115200;
    private static final long RECONNECT_DELAY_MS = 2000L;

    private final String portName;
    private final PacketListener listener;

    private final AtomicBoolean running = new AtomicBoolean(false);
    private Thread readerThread;
    private volatile SerialPort port;

    /**
     * @param portName e.g. "COM5" on Windows or "/dev/ttyUSB0" on Linux/macOS.
     *                 Use {@link #listAvailablePorts()} to discover options.
     * @param listener called on the serial thread for every successfully
     *                 parsed packet.
     */
    public SerialManager(String portName, PacketListener listener) {
        this.portName = portName;
        this.listener = listener;
    }

    public static String[] listAvailablePorts() {
        SerialPort[] ports = SerialPort.getCommPorts();
        String[] names = new String[ports.length];
        for (int i = 0; i < ports.length; i++) {
            names[i] = ports[i].getSystemPortName();
        }
        return names;
    }

    /** Starts the background reader thread. Safe to call once. */
    public void start() {
        if (!running.compareAndSet(false, true)) {
            LOGGER.warn("SerialManager already started, ignoring duplicate start()");
            return;
        }
        readerThread = new Thread(this::runLoop, "hardware-controller-serial-reader");
        readerThread.setDaemon(true);
        readerThread.start();
    }

    /** Stops the reader thread and closes the port. Safe to call from any thread. */
    public void stop() {
        running.set(false);
        SerialPort currentPort = this.port;
        if (currentPort != null) {
            currentPort.closePort();
        }
        if (readerThread != null) {
            readerThread.interrupt();
        }
    }

    private void runLoop() {
        while (running.get()) {
            try {
                connectAndReadUntilFailure();
            } catch (Exception e) {
                LOGGER.warn("Serial read loop error, will retry in {} ms: {}", RECONNECT_DELAY_MS, e.getMessage());
            }

            if (running.get()) {
                sleepQuietly(RECONNECT_DELAY_MS);
            }
        }
    }

    private void connectAndReadUntilFailure() {
        SerialPort newPort = SerialPort.getCommPort(portName);
        newPort.setBaudRate(BAUD_RATE);
        // Semi-blocking read: returns as soon as data is available, but
        // doesn't hang forever if the ESP32 stops sending -- lets us notice
        // a dead connection and retry.
        newPort.setComPortTimeouts(SerialPort.TIMEOUT_READ_SEMI_BLOCKING, 1000, 0);

        if (!newPort.openPort()) {
            LOGGER.warn("Could not open serial port '{}'. Available ports: {}",
                    portName, String.join(", ", listAvailablePorts()));
            return;
        }

        this.port = newPort;
        LOGGER.info("Opened serial port '{}' at {} baud", portName, BAUD_RATE);

        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(newPort.getInputStream(), StandardCharsets.US_ASCII))) {

            String line;
            while (running.get() && (line = reader.readLine()) != null) {
                handleLine(line);
            }
        } catch (IOException e) {
            LOGGER.warn("Serial connection to '{}' dropped: {}", portName, e.getMessage());
        } finally {
            newPort.closePort();
            LOGGER.info("Closed serial port '{}'", portName);
        }
    }

    private void handleLine(String line) {
        Optional<HardwarePacket> packet = PacketParser.parse(line);
        if (packet.isPresent()) {
            try {
                listener.onPacket(packet.get());
            } catch (Exception e) {
                // A bug in downstream handling should never kill the reader
                // thread -- log and keep reading.
                LOGGER.error("Error handling packet {}", packet.get(), e);
            }
        } else {
            LOGGER.debug("Ignoring malformed line: '{}'", line);
        }
    }

    private static void sleepQuietly(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
