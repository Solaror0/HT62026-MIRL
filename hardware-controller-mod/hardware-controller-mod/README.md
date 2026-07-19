# Hardware Controller (Fabric mod)

Bridges ESP32 hardware props (sword, shield, bow, head tracker, ...) into
Minecraft client actions, over a single USB serial connection to a master
ESP32. The mod never touches WiFi/ESP-NOW directly -- only serial.

## Wire protocol

CSV, one line per packet, newline-terminated:

```
node_id,picked_up,left_click,right_click\n
```

Example: `1,1,0,0\n` means node 1 (sword), currently held, no swing, no
right click.

`PacketParser` is the only class that knows this format -- if you switch to
JSON or anything else, that's the only file that changes.

## Master ESP32 side

The master should NOT forward the raw `struct_message` bytes. Convert to
text before writing to serial. Example:

```cpp
void onDataRecv(const uint8_t *mac, const uint8_t *incomingData, int len) {
    struct_message pkt;
    memcpy(&pkt, incomingData, sizeof(pkt));

    Serial.printf("%d,%d,%d,%d\n",
                  pkt.node_id, pkt.picked_up, pkt.left_click, pkt.right_click);
}
```

That's it -- one `Serial.printf` call per received ESP-NOW packet. Open the
Arduino serial monitor at any point and you'll see exactly what the mod
sees, which makes debugging during the hackathon much easier.

## Project layout

```
protocol/   HardwarePacket (data), PacketParser (CSV -> HardwarePacket)
serial/     SerialManager (owns the port + reader thread), PacketListener
state/      HardwareState (per-node state map), NodeState (per-node fields,
            incl. rising-edge detection for left_click)
controller/ GameController (dispatch), NodeHandlerRegistry (node_id -> handler),
            NodeHandler (interface), handlers/SwordNodeHandler (node_id 1)
action/     ActionQueue (serial thread -> client thread bridge)
```

Threading model:
- `SerialManager` runs a dedicated daemon thread that blocks on
  `BufferedReader.readLine()` and never touches Minecraft state.
- Parsed packets go to `GameController`, which looks up a `NodeHandler` and
  calls it -- still on the serial thread. Handlers only ever build
  `Runnable`s and hand them to `ActionQueue.enqueue(...)`.
- Once per client tick (`ClientTickEvents.END_CLIENT_TICK`), the mod drains
  `ActionQueue` and runs each action on the client thread. This is the only
  place Minecraft state is mutated.

## Adding a new prop (e.g. shield = node_id 2)

1. Confirm the `node_id` with the hardware team and add it as a constant in
   `HardwareControllerMod` (there's a commented-out block ready for this).
2. Create `controller/handlers/ShieldNodeHandler.java` implementing
   `NodeHandler`, following `SwordNodeHandler` as a template.
3. Register it: `registry.register(NODE_ID_SHIELD, new ShieldNodeHandler());`

Nothing in `SerialManager`, `PacketParser`, `HardwareState`, or
`GameController` needs to change.

## Testing without hardware

`DebugHardwareSimulator` (registered automatically in `HardwareControllerMod`)
injects fake packets straight into `GameController`, the same path
`SerialManager` / `TcpPacketServer` use -- so if behavior works via these
keys, it'll work with real hardware/CV input too. Default keybinds
(function keys, since laptops often lack a numpad):

- **F6** -- toggle simulated sword picked up / put down
- **F7** -- fire one simulated sword swing
- **F8** -- toggle simulated CV armor detection
- **F9** -- toggle simulated CV golden-apple detection

Rebind them in Options -> Controls -> "Hardware Controller (Debug)" if any
of these conflict with something on your system. Remove `DebugHardwareSimulator`'s
registration in `HardwareControllerMod.onInitializeClient()` (or gate it
behind a debug flag) before a real demo -- it's a direct backdoor into the
event pipeline.

## CV integration (armor / held-item detection)

Instead of a physical sensor, a companion Python process can watch the
webcam and report detections to the mod over a local TCP socket
(`TcpPacketServer`, `127.0.0.1:5005` by default) using the exact same CSV
protocol as serial. This means CV detections flow through the identical
`GameController` -> `NodeHandler` -> `ActionQueue` pipeline as physical
props -- nothing downstream cares which transport a packet came from.

Reserved node ids for this (see `HardwareControllerMod.java`):
- `10` = armor detected
- `11` = golden apple detected

`cv-client/cv_client_example.py` is a skeleton: it opens the webcam,
connects to the mod, and sends `10,<0|1>,0,0` / `11,<0|1>,0,0` lines at
~10Hz. `detect_armor()` and `detect_golden_apple()` are unimplemented --
that's where your color thresholding / contour work goes.
`pip install opencv-python`, then run it *after* Minecraft is running with
the mod loaded (watch the game log for `CV TCP server listening on
127.0.0.1:5005` first).

On the Java side, `CvDetectionNodeHandler` (registered for both node ids)
is currently a placeholder: it just logs and shows an action-bar message
when detection state changes ("[CV] Armor detected" / "no longer
detected"). It's intentionally not wired to any gameplay effect yet --
swap the `TODO` block in that class for real behavior (equip armor pieces,
apply an effect, etc.) once you've settled on what "detected" should
actually do in-game. Test it right now with the Numpad 3 / 4 debug keys
above, no camera needed.

## Setup

1. Set `SERIAL_PORT_NAME` in `HardwareControllerMod.java` to your master
   ESP32's port (e.g. `"COM5"` on Windows, `"/dev/ttyUSB0"` on Linux/macOS).
   If unsure, `SerialManager.listAvailablePorts()` logs available ports on
   startup.
2. `./gradlew build` (or open in IntelliJ IDEA with the Fabric/Loom plugin).
3. `./gradlew runClient` to launch a dev client with the mod loaded.

## Things worth double-checking

This targets Minecraft 1.21.1 with Fabric Loom + Yarn mappings. Two lines
depend on exact mapped names that can drift slightly between Yarn builds --
if either fails to resolve in your IDE, it's a quick fix, not a design
problem:
- `UpdateSelectedSlotC2SPacket` in `SwordNodeHandler.setSelectedSlot()`
  (keeps the server in sync when we auto-switch hotbar slots).
- `client.options.attackKey` in `SwordNodeHandler.triggerAttack()`.

Also note: Fabric has since introduced a newer date-versioned release line
(26.x) using Mojang-only mappings. This project intentionally stays on the
well-documented 1.21.1 / Yarn stack for hackathon stability; porting forward
later is straightforward if needed.
