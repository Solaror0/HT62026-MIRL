package com.hackathon.hardwarecontroller.action;

import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * The only bridge between the serial thread and Minecraft state.
 *
 * The serial thread (via node handlers) enqueues plain Runnables here. Once
 * per client tick, HardwareControllerMod drains the queue and executes each
 * Runnable on the Minecraft client thread. This is the mechanism that keeps
 * the serial thread from ever touching Minecraft state directly.
 */
public class ActionQueue {

    private final ConcurrentLinkedQueue<Runnable> queue = new ConcurrentLinkedQueue<>();

    /** Called from the serial thread (or anywhere): schedules work for the client thread. */
    public void enqueue(Runnable action) {
        queue.add(action);
    }

    /** Called ONLY from the client thread: runs every pending action. */
    public void drainAndRun() {
        Runnable action;
        while ((action = queue.poll()) != null) {
            action.run();
        }
    }
}
