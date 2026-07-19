package com.hackathon.hardwarecontroller.state;

/**
 * Tracks the latest known state for a single hardware node (one prop).
 *
 * Fields are volatile because they're written from the serial thread and
 * may be read from the client thread when a queued action runs.
 */
public class NodeState {

    private volatile boolean heldByHardware = false;
    private volatile boolean previousLeftClick = false;
    private volatile boolean previousRightClick = false;
    private volatile boolean previousPickedUp = false;

    /** True if this mod auto-equipped an item into the hotbar for this node. */
    private volatile boolean autoEquipped = false;

    /** Hotbar slot the player was on before we auto-switched, so we can restore it. */
    private volatile int slotBeforeAutoEquip = -1;

    /** Tracks whether we've currently got the block/use key held down for this node. */
    private volatile boolean blocking = false;

    /** Tracks whether we've currently got the sprint key held down for this node. */
    private volatile boolean sprinting = false;

    /** Tracks whether we've currently got the bow's draw (use) key held down for this node. */
    private volatile boolean drawingBow = false;

    /**
     * Current lean direction for the lean/turn node: -1 = leaning left,
     * 0 = no lean (or both flags set, which we treat as ambiguous/none),
     * +1 = leaning right. Read once per client tick to apply rotation.
     */
    private volatile int leanDirection = 0;

    public boolean isHeldByHardware() {
        return heldByHardware;
    }

    public void setHeldByHardware(boolean heldByHardware) {
        this.heldByHardware = heldByHardware;
    }

    public boolean isAutoEquipped() {
        return autoEquipped;
    }

    public void setAutoEquipped(boolean autoEquipped) {
        this.autoEquipped = autoEquipped;
    }

    public int getSlotBeforeAutoEquip() {
        return slotBeforeAutoEquip;
    }

    public void setSlotBeforeAutoEquip(int slot) {
        this.slotBeforeAutoEquip = slot;
    }

    public boolean isBlocking() {
        return blocking;
    }

    public void setBlocking(boolean blocking) {
        this.blocking = blocking;
    }

    public boolean isSprinting() {
        return sprinting;
    }

    public void setSprinting(boolean sprinting) {
        this.sprinting = sprinting;
    }

    public boolean isDrawingBow() {
        return drawingBow;
    }

    public void setDrawingBow(boolean drawingBow) {
        this.drawingBow = drawingBow;
    }

    public int getLeanDirection() {
        return leanDirection;
    }

    public void setLeanDirection(int leanDirection) {
        this.leanDirection = leanDirection;
    }

    /**
     * Rising-edge check for the left-click / swing flag. Packets arrive
     * roughly every 100ms, so if the hardware holds left_click=1 across
     * several consecutive packets, this ensures we only fire once, on the
     * 0 -> 1 transition, instead of attacking on every packet.
     *
     * @return true exactly once per swing (on the transition into "clicked").
     */
    public boolean consumeLeftClickRisingEdge(boolean currentLeftClick) {
        boolean isRisingEdge = currentLeftClick && !previousLeftClick;
        previousLeftClick = currentLeftClick;
        return isRisingEdge;
    }

    /**
     * Same idea as consumeLeftClickRisingEdge, but for right_click -- used
     * by the bow node to tell "first press since not-equipped" (the
     * pickup click) apart from "still holding since we were already
     * equipped" (an ongoing draw). See BowNodeHandler.
     *
     * @return true exactly once per 0 -> 1 transition of right_click.
     */
    public boolean consumeRightClickRisingEdge(boolean currentRightClick) {
        boolean isRisingEdge = currentRightClick && !previousRightClick;
        previousRightClick = currentRightClick;
        return isRisingEdge;
    }

    /**
     * Same idea as consumeLeftClickRisingEdge, but for the picked_up field --
     * used by nodes where "picked_up" is repurposed as a momentary trigger
     * (e.g. the jump node, where the CV script reports a vertical head-bob
     * as a brief true/false blip rather than a held state). Keeping this
     * separate from previousLeftClick means a node can use both a rising
     * edge on left_click AND one on picked_up without them interfering.
     *
     * @return true exactly once per 0 -> 1 transition of picked_up.
     */
    public boolean consumePickedUpRisingEdge(boolean currentPickedUp) {
        boolean isRisingEdge = currentPickedUp && !previousPickedUp;
        previousPickedUp = currentPickedUp;
        return isRisingEdge;
    }
}