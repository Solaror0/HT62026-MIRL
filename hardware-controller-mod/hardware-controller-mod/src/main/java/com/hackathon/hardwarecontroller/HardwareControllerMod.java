package com.hackathon.hardwarecontroller;

import com.hackathon.hardwarecontroller.action.ActionQueue;
import com.hackathon.hardwarecontroller.controller.GameController;
import com.hackathon.hardwarecontroller.controller.NodeHandlerRegistry;
import com.hackathon.hardwarecontroller.controller.handlers.BowNodeHandler;
import com.hackathon.hardwarecontroller.controller.handlers.CvDetectionNodeHandler;
import com.hackathon.hardwarecontroller.controller.handlers.JumpNodeHandler;
import com.hackathon.hardwarecontroller.controller.handlers.LeanNodeHandler;
import com.hackathon.hardwarecontroller.controller.handlers.RunNodeHandler;
import com.hackathon.hardwarecontroller.controller.handlers.SwordNodeHandler;
import com.hackathon.hardwarecontroller.controller.handlers.ShieldNodeHandler;
import com.hackathon.hardwarecontroller.debug.DebugHardwareSimulator;
import com.hackathon.hardwarecontroller.network.TcpPacketServer;
import com.hackathon.hardwarecontroller.serial.SerialManager;
import com.hackathon.hardwarecontroller.state.HardwareState;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Entry point. Wires together:
 *   SerialManager -> GameController -> NodeHandlerRegistry -> ActionQueue
 * and drains the ActionQueue once per client tick.
 */
public class HardwareControllerMod implements ClientModInitializer {

    public static final Logger LOGGER = LoggerFactory.getLogger("HardwareController");

    // TODO(hackathon): confirm final node_id values with the hardware team.
    // Sword is confirmed as 1. Add the rest here as they're locked in --
    // nothing else in the mod needs to change.
    public static final int NODE_ID_SWORD = 1;
    public static final int NODE_ID_SHIELD = 2;
    public static final int NODE_ID_BOW = 3;
    // public static final int NODE_ID_HEAD_TRACKER = 4;

    // CV-detected "virtual" nodes -- fed by TcpPacketServer instead of
    // serial, but flow through the exact same GameController pipeline.
    // Numbered starting at 10 to leave room for more physical props.
    public static final int NODE_ID_CV_ARMOR = 10;
    public static final int NODE_ID_CV_GOLDEN_APPLE = 11;
    public static final int NODE_ID_LEAN = 12;
    public static final int NODE_ID_RUN = 13;
    public static final int NODE_ID_JUMP = 14;

    // TODO(hackathon): set this to your master ESP32's actual port, or wire
    // up a config file / in-game command. Use SerialManager.listAvailablePorts()
    // to see what's connected if you're not sure of the name.
    private static final String SERIAL_PORT_NAME = "COM10";

    // Localhost-only port the CV script connects to. Change if it collides
    // with something else on your machine.
    private static final int CV_TCP_PORT = 5005;

    private final ActionQueue actionQueue = new ActionQueue();
    private final HardwareState hardwareState = new HardwareState();
    private final NodeHandlerRegistry registry = new NodeHandlerRegistry();

    private SerialManager serialManager;
    private TcpPacketServer cvTcpServer;

    @Override
    public void onInitializeClient() {
        registerNodeHandlers();

        GameController gameController = new GameController(hardwareState, actionQueue, registry);

        serialManager = new SerialManager(SERIAL_PORT_NAME, gameController);
        serialManager.start();
        LOGGER.info("HardwareController started. Available serial ports: {}",
                String.join(", ", SerialManager.listAvailablePorts()));

        // Same GameController, different transport -- CV script connects
        // here instead of over serial.
        cvTcpServer = new TcpPacketServer(CV_TCP_PORT, gameController);
        cvTcpServer.start();

        // DEV/TEST ONLY: lets you fake hardware/CV packets from the
        // keyboard. Remove or gate behind a debug flag before a real demo.
        new DebugHardwareSimulator(gameController).register();

        // Drain queued hardware-triggered actions once per client tick, on
        // the client thread -- this is the only place Minecraft state is
        // ever mutated as a result of hardware input.
        //
        // LeanNodeHandler.tick(...) is called alongside it rather than
        // through the ActionQueue: turning needs to keep happening every
        // tick for as long as a lean is held, not just once when it starts
        // (see LeanNodeHandler for details).
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            actionQueue.drainAndRun();
            LeanNodeHandler.tick(hardwareState.getOrCreate(NODE_ID_LEAN));
        });
    }

    private void registerNodeHandlers() {
        registry.register(NODE_ID_SWORD, new SwordNodeHandler());
        registry.register(NODE_ID_SHIELD, new ShieldNodeHandler());
        registry.register(NODE_ID_BOW, new BowNodeHandler());

        registry.register(NODE_ID_CV_ARMOR, new CvDetectionNodeHandler("Armor"));
        registry.register(NODE_ID_CV_GOLDEN_APPLE, new CvDetectionNodeHandler("Golden apple"));
        registry.register(NODE_ID_LEAN, new LeanNodeHandler());
        registry.register(NODE_ID_RUN, new RunNodeHandler());
        registry.register(NODE_ID_JUMP, new JumpNodeHandler());
    }
}