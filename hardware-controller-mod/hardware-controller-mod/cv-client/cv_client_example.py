"""
Example client for the Hardware Controller mod's CV TCP server.

This is scaffolding, not detection logic -- it shows how to get bytes from
your webcam pipeline to the Fabric mod. The actual armor / item detection
(color thresholding, contour analysis, whatever you land on) goes in
detect_armor() and detect_golden_apple() below.

Wire protocol (same as the ESP32 serial link):
    node_id,detected,0,0\n
(the mod's HardwarePacket format has 4 fields; for CV we only use the first
two -- node_id and a 0/1 "detected" flag -- and always send 0,0 for the
unused left_click/right_click fields.)

Node IDs (must match HardwareControllerMod.java):
    10 = armor detected
    11 = golden apple detected

Requirements:
    pip install opencv-python

Run this AFTER Minecraft is up and the mod has started the TCP server
(look for "CV TCP server listening on 127.0.0.1:5005" in the game log).
This script auto-reconnects if Minecraft isn't up yet or restarts.
"""

import socket
import time

import cv2

HOST = "127.0.0.1"
PORT = 5005  # must match CV_TCP_PORT in HardwareControllerMod.java

NODE_ID_ARMOR = 10
NODE_ID_GOLDEN_APPLE = 11

SEND_INTERVAL_SECONDS = 0.1  # ~10Hz, similar cadence to the ESP32 packets


def detect_armor(frame) -> bool:
    """
    TODO: replace with real detection.

    Ideas to start from, per your message:
      - Color thresholding: convert to HSV (cv2.cvtColor(frame, cv2.COLOR_BGR2HSV)),
        threshold a color range associated with the armor prop (cv2.inRange),
        then check if enough pixels match (cv2.countNonZero) to call it "detected".
      - Contour mapping: cv2.findContours on a thresholded/edge-detected image,
        filter by contour area/shape to reject noise.
    Combining both (color mask -> contours on the mask -> area threshold) is a
    reasonable starting point and is more robust than color alone.
    """
    return False


def detect_golden_apple(frame) -> bool:
    """TODO: same idea as detect_armor(), tuned for the golden apple's color/shape."""
    return False


def connect() -> socket.socket:
    while True:
        try:
            sock = socket.create_connection((HOST, PORT), timeout=2)
            print(f"Connected to mod at {HOST}:{PORT}")
            return sock
        except OSError as e:
            print(f"Could not connect ({e}); is Minecraft running with the mod loaded? Retrying...")
            time.sleep(2)


def send_packet(sock: socket.socket, node_id: int, detected: bool) -> None:
    line = f"{node_id},{1 if detected else 0},0,0\n"
    sock.sendall(line.encode("ascii"))


def main() -> None:
    cap = cv2.VideoCapture(0)
    if not cap.isOpened():
        raise RuntimeError("Could not open webcam (index 0). Check your camera / index.")

    sock = connect()

    try:
        while True:
            ok, frame = cap.read()
            if not ok:
                print("Failed to read frame from webcam, retrying...")
                time.sleep(0.5)
                continue

            armor_detected = detect_armor(frame)
            golden_apple_detected = detect_golden_apple(frame)

            try:
                send_packet(sock, NODE_ID_ARMOR, armor_detected)
                send_packet(sock, NODE_ID_GOLDEN_APPLE, golden_apple_detected)
            except OSError as e:
                print(f"Lost connection to mod ({e}), reconnecting...")
                sock.close()
                sock = connect()

            # Uncomment while calibrating thresholds, to see what the camera sees:
            # cv2.imshow("CV debug", frame)
            # if cv2.waitKey(1) & 0xFF == ord('q'):
            #     break

            time.sleep(SEND_INTERVAL_SECONDS)
    finally:
        cap.release()
        sock.close()
        cv2.destroyAllWindows()


if __name__ == "__main__":
    main()
