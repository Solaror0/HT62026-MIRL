"""
Example client for the Hardware Controller mod's CV TCP server.

This is scaffolding, not detection logic -- it shows how to get bytes from
your webcam pipeline to the Fabric mod. The actual armor / item detection
(color thresholding, contour analysis, whatever you land on) goes in
detect_armor() and detect_golden_apple() below. Head/hand tracking (look-
to-turn, hand-crossing-to-run/sprint, movement-spike-to-jump) is implemented
with MediaPipe's Pose Landmarker task.

Why Pose Landmarker instead of Face/Hand Landmarker: at a distance from the
camera, eye-corner and fingertip landmarks shrink to just a few pixels and
get noisy fast. Pose Landmarker is built to track a full body at range --
shoulders, ears, nose, and wrists stay large and stable even when you're
standing well back from the webcam -- and it gives all three signals (turn,
jump, run) from a single model call per frame instead of two separate
models.

Wire protocol (same as the ESP32 serial link):
    node_id,picked_up,left_click,right_click\n
(the mod's HardwarePacket format has 4 fields.)

Node IDs (must match HardwareControllerMod.java):
    10 = armor detected        -> picked_up flag only, left/right unused
    11 = golden apple detected -> picked_up flag only, left/right unused
    12 = turn (head look)       -> picked_up unused; left_click = looking
                                    left, right_click = looking right
                                    (see LeanNodeHandler.java -- mod-side
                                    name is unchanged, it's still just the
                                    "turn" input channel)
    13 = run / sprint           -> picked_up = moving (two wrists crossing
                                    each other above the move threshold),
                                    left_click = also sprinting (above the
                                    higher sprint threshold), right_click
                                    unused (see RunNodeHandler.java)
    14 = vertical bob / jump    -> picked_up = momentary 0->1->0 blip when
                                    the body moves up faster than its own
                                    recent normal, right/left unused
                                    (see JumpNodeHandler.java)

Requirements:
    pip install opencv-python mediapipe

NOTE ON MEDIAPIPE VERSIONS: the old `mp.solutions.*` API has been removed
from recent mediapipe wheels (0.10.3x on Python 3.12/3.13 in particular --
`mp.solutions` doesn't exist at all there). This uses the newer Tasks API
(`mp.tasks.vision.PoseLandmarker`) instead, which needs a model file. It's
downloaded automatically to this folder on first run (~5-30MB depending on
POSE_MODEL_VARIANT below) -- if your machine can't reach
storage.googleapis.com, grab it manually from the URL printed on first run
and drop it next to this script.

POSE_MODEL_VARIANT: "lite" is the fastest and is the right choice for this
use case (you mostly need coarse shoulder/wrist/nose positions, not fine
detail) -- "full" or "heavy" trade speed for accuracy if lite proves too
jittery for you in practice.

Run this AFTER Minecraft is up and the mod has started the TCP server
(look for "CV TCP server listening on 127.0.0.1:5005" in the game log).
This script auto-reconnects if Minecraft isn't up yet or restarts.

DEBUG WINDOW: with SHOW_DEBUG_WINDOW = True (the default), a preview
window pops up showing the tracked shoulder/ear/nose/wrist points plus
live threshold readouts, so you can see exactly what's triggering what
while tuning. Press 'q' with that window focused to quit, or 'n' while
looking at the screen to (re)calibrate the look-turn neutral point.
"""

import math
import os
import socket
import time
import urllib.request

import cv2
import mediapipe as mp
from mediapipe.tasks import python as mp_python
from mediapipe.tasks.python import vision as mp_vision

HOST = "127.0.0.1"
PORT = 5005  # must match CV_TCP_PORT in HardwareControllerMod.java

NODE_ID_ARMOR = 10
NODE_ID_GOLDEN_APPLE = 11
NODE_ID_LEAN = 12
NODE_ID_RUN = 13
NODE_ID_JUMP = 14

SEND_INTERVAL_SECONDS = 1 / 60  # ~60Hz

SHOW_DEBUG_WINDOW = True

POSE_MODEL_VARIANT = "lite"  # "lite", "full", or "heavy" -- see note above

# --- Look-to-turn tuning ----------------------------------------------------
#
# Turning is now driven by head yaw (literally turning your head to look
# left/right) instead of shoulder roll (tilting your torso sideways).
# Shoulder roll turned out to be a bad control at distance: it requires
# reliably tilting your whole upper body, and small real tilts barely move
# the shoulder line, so it read as chopped/unreliable.
#
# Head yaw is read from the nose landmark's horizontal offset relative to
# the midpoint between the two ears, normalized by shoulder width (so the
# signal doesn't scale with how far you are from the camera). Facing the
# camera straight-on, the nose sits roughly centered between the ears;
# turning your head to either side shifts the nose off that center line.
# This still rides on the same coarse pose skeleton used for shoulders --
# not a fine facial mesh -- so it holds up at range the same way lean did.
#
# Same EMA-smoothing + hysteresis pattern as before, for the same reason:
# a single noisy frame shouldn't flip the state, and the enter/exit gap
# stops chatter right at the boundary.
#
# Two more knobs on top of that:
#
#   - LOOK_YAW_GAIN amplifies the raw offset before it's thresholded, so
#     you don't have to turn your head as far to reach the same threshold.
#     This is the direct "more sensitive" dial. Cranking it up also
#     amplifies landmark jitter though, so if it gets twitchy, pull the
#     thresholds up a bit rather than the gain back down.
#   - Calibration fixes a separate problem: "nose exactly centered between
#     ears" is only an approximation of YOUR straight-ahead, and depends
#     on head shape / camera angle / desk setup. If your natural resting
#     position doesn't read as ~0, you're burning part of your turn budget
#     just getting back to center. Press 'n' in the debug window while
#     looking at the screen to zero the baseline against wherever you're
#     actually sitting -- this alone often removes the need to look way
#     off-screen to trigger a turn.

LOOK_YAW_ENTER_RATIO = 0.08  # nose-offset/shoulder-width ratio needed to START a turn
LOOK_YAW_EXIT_RATIO = 0.04   # ratio it must fall back under to STOP
LOOK_YAW_GAIN = 1.8          # amplifies the raw ratio -- this is the main "sensitivity" dial
LOOK_SMOOTHING = 0.4         # EMA weight on each new reading; lower = smoother but laggier
LOOK_INVERT = True           # flip if turning your head right registers as "left" (webcam mirroring)

# --- Bob-to-jump tuning -----------------------------------------------------
#
# Same self-normalizing-baseline approach as before, just tracking the
# shoulder midpoint's y instead of the nose's y -- shoulders are a bigger,
# steadier target at range than a nose tip, and don't disappear if you tip
# your head back.

JUMP_MOVEMENT_MULTIPLIER = 3.0
JUMP_MIN_ABSOLUTE_DELTA = 0.015
JUMP_BASELINE_SMOOTHING = 0.2

# --- Wrist-crossing-to-run/sprint tuning ------------------------------------
#
# Same crossing-counter idea as before, just reading wrist landmarks
# straight out of the pose model instead of running a second hand-specific
# model. This is actually more reliable at distance: the hand landmarker
# has to recognize a hand *shape*, which falls apart once your hands are
# small in frame, while the pose model just needs to place a rough wrist
# point as part of the whole-body skeleton.

RUN_CROSSING_WINDOW_SECONDS = 1.0
MOVE_CROSSINGS_PER_SECOND_THRESHOLD = 1.0
SPRINT_CROSSINGS_PER_SECOND_THRESHOLD = 2.0

# The window above is what makes the rate estimate smooth, but it's also
# exactly why "moving" used to hang for up to a second after you actually
# stopped: a real stop is invisible to a windowed rate until the old
# crossings age out of the window, which takes the full window length.
# This fixes that by tracking time-since-last-crossing directly and
# force-clearing moving/sprinting the instant that gap gets implausibly
# long for someone still walking, instead of waiting on the window.
# Set a bit above the expected max gap between crossings at a normal walk
# pace (roughly the reciprocal of the move threshold) so it doesn't cut
# off a genuinely slow walk, but well under the full window so it doesn't
# just recreate the same hang.
RUN_STOP_TIMEOUT_SECONDS = 0.5

# Pose Landmarker gives every landmark a per-frame visibility score (0-1,
# how likely the model thinks the point is actually visible/unoccluded)
# rather than an all-or-nothing "hand detected" flag. Biased low on
# purpose, same reasoning as before: a wrist that's technically visible
# but scored low still usually has a roughly-correct y-position, and
# undercounting a real crossing (character just doesn't move) is a worse
# failure than an occasional spurious extra crossing (character reads as
# moving very slightly more than it is).
WRIST_VISIBILITY_THRESHOLD = 0.1

POSE_DETECTION_CONFIDENCE = 0.3
POSE_TRACKING_CONFIDENCE = 0.3

# When a wrist's visibility drops below threshold for a frame or two
# (typically motion blur mid-swing, exactly when you most want the
# crossing counted), hold its last known y-position for this long rather
# than just skipping the frame. A crossing that happens "behind" the blur
# still gets caught once the wrist is visible again, instead of silently
# vanishing.
# When a hand fully leaves the frame (not just occluded/blurred while
# still in view), the pose model still guesses a landmark position based
# on body context, so visibility alone can't reliably tell "off-screen"
# apart from "briefly hard to see mid-swing" -- both can score low. What's
# different for a genuinely off-screen wrist is its normalized coordinate
# itself lands outside the visible frame. That's checked separately from
# WRIST_VISIBILITY_THRESHOLD above, and unlike a low-visibility blur it's
# NOT given the WRIST_HOLD_SECONDS grace period -- moving/sprinting stop
# immediately, since "no longer swinging because the hand left frame" is
# exactly what should be reported as motionless, not bridged over.
WRIST_OFFSCREEN_MARGIN = 0.0

WRIST_HOLD_SECONDS = 0.4


# --- MediaPipe Pose Landmarker setup -----------------------------------

_SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))

_POSE_MODEL_PATH = os.path.join(_SCRIPT_DIR, f"pose_landmarker_{POSE_MODEL_VARIANT}.task")
_POSE_MODEL_URL = (
    "https://storage.googleapis.com/mediapipe-models/pose_landmarker/"
    f"pose_landmarker_{POSE_MODEL_VARIANT}/float16/latest/pose_landmarker_{POSE_MODEL_VARIANT}.task"
)

# Pose Landmarker's 33-point BlazePose topology.
NOSE = 0
LEFT_EAR = 7
RIGHT_EAR = 8
LEFT_SHOULDER = 11
RIGHT_SHOULDER = 12
LEFT_WRIST = 15
RIGHT_WRIST = 16


def _ensure_model_downloaded(path: str, url: str) -> None:
    if os.path.exists(path):
        return
    print(f"Downloading model to {path} (one-time)...")
    urllib.request.urlretrieve(url, path)
    print("Model downloaded.")


def _create_pose_landmarker() -> mp_vision.PoseLandmarker:
    _ensure_model_downloaded(_POSE_MODEL_PATH, _POSE_MODEL_URL)
    options = mp_vision.PoseLandmarkerOptions(
        base_options=mp_python.BaseOptions(model_asset_path=_POSE_MODEL_PATH),
        running_mode=mp_vision.RunningMode.VIDEO,
        num_poses=1,
        min_pose_detection_confidence=POSE_DETECTION_CONFIDENCE,
        min_pose_presence_confidence=POSE_DETECTION_CONFIDENCE,
        min_tracking_confidence=POSE_TRACKING_CONFIDENCE,
    )
    return mp_vision.PoseLandmarker.create_from_options(options)


pose_landmarker = _create_pose_landmarker()
_stream_start_time = time.monotonic()

_prev_shoulder_mid_y = None
_movement_baseline = JUMP_MIN_ABSOLUTE_DELTA
_last_jump_delta = 0.0

_prev_wrist_diff_sign = None
_wrist_crossing_times: list[float] = []

# Two persistent "slots" holding the last known y-position of each wrist,
# plus when each was last seen with visibility above threshold. Pose
# Landmarker's landmark indices (15/16) are already left/right-consistent
# frame to frame, unlike the old ad-hoc hand-landmarker slots, so this is
# mostly bookkeeping for the hold/expire behavior now rather than identity
# matching.
_wrist_slot_y = [None, None]
_wrist_slot_last_seen = [None, None]

_last_yaw_ratio = 0.0     # raw, latest frame -- debug display only
_smoothed_yaw_ratio = 0.0 # EMA-filtered, this is what actually gets thresholded
_currently_looking: str | None = None  # hysteresis state
_yaw_neutral_offset = 0.0 # calibrated "straight ahead" point, set via _calibrate_look_neutral()


def detect_armor(frame) -> bool:
    """TODO: replace with real detection (color threshold + contours is a reasonable start)."""
    return False


def detect_golden_apple(frame) -> bool:
    """TODO: same idea as detect_armor(), tuned for the golden apple's color/shape."""
    return False


def _get_pose_landmarks(frame, timestamp_ms: int):
    """Returns the 33 normalized landmarks for the first detected pose, or None."""
    rgb_frame = cv2.cvtColor(frame, cv2.COLOR_BGR2RGB)
    mp_image = mp.Image(image_format=mp.ImageFormat.SRGB, data=rgb_frame)
    result = pose_landmarker.detect_for_video(mp_image, timestamp_ms)
    if not result.pose_landmarks:
        return None
    return result.pose_landmarks[0]


def _shoulder_mid_y(landmarks) -> float:
    return (landmarks[LEFT_SHOULDER].y + landmarks[RIGHT_SHOULDER].y) / 2


_last_uncalibrated_yaw_ratio = 0.0  # before neutral-offset subtraction -- calibration reads this


def _calibrate_look_neutral() -> None:
    """Zeroes the look-turn baseline against wherever the head currently is. Call while looking at the screen."""
    global _yaw_neutral_offset
    _yaw_neutral_offset = _last_uncalibrated_yaw_ratio
    print(f"Look calibrated: neutral offset set to {_yaw_neutral_offset:+.3f}")


def detect_look(landmarks) -> str | None:
    """
    Returns "left", "right", or None, based on head yaw: how far the nose
    sits off-center relative to the midpoint between the two ears,
    normalized by shoulder width so the signal doesn't scale with distance
    from the camera. A calibrated neutral offset is subtracted first (see
    _calibrate_look_neutral), then LOOK_YAW_GAIN amplifies the result
    before it's EMA-smoothed and thresholded with hysteresis (separate
    enter/exit cutoffs) so the state doesn't chatter near the boundary.
    """
    global _last_yaw_ratio, _last_uncalibrated_yaw_ratio, _smoothed_yaw_ratio, _currently_looking

    nose = landmarks[NOSE]
    left_ear = landmarks[LEFT_EAR]
    right_ear = landmarks[RIGHT_EAR]
    left_shoulder = landmarks[LEFT_SHOULDER]
    right_shoulder = landmarks[RIGHT_SHOULDER]

    shoulder_width = abs(right_shoulder.x - left_shoulder.x)
    if shoulder_width < 1e-4:
        # Degenerate frame (shoulders on top of each other, bad pose read)
        # -- nothing sane to compute, just hold the last state.
        return _currently_looking

    ear_mid_x = (left_ear.x + right_ear.x) / 2
    uncalibrated_ratio = (nose.x - ear_mid_x) / shoulder_width
    _last_uncalibrated_yaw_ratio = uncalibrated_ratio

    raw_ratio = (uncalibrated_ratio - _yaw_neutral_offset) * LOOK_YAW_GAIN
    _last_yaw_ratio = raw_ratio

    _smoothed_yaw_ratio += LOOK_SMOOTHING * (raw_ratio - _smoothed_yaw_ratio)

    raw_direction = "right" if _smoothed_yaw_ratio > 0 else "left"
    magnitude = abs(_smoothed_yaw_ratio)

    if _currently_looking is None:
        # Not currently turned -- need the bigger ENTER ratio to start.
        if magnitude >= LOOK_YAW_ENTER_RATIO:
            _currently_looking = raw_direction
    else:
        # Currently turned -- only the smaller EXIT ratio is needed to
        # keep it turned; falling under it returns to "none".
        if magnitude < LOOK_YAW_EXIT_RATIO:
            _currently_looking = None
        else:
            _currently_looking = raw_direction

    if _currently_looking is None:
        return None

    looking_right = _currently_looking == "right"
    if LOOK_INVERT:
        looking_right = not looking_right

    return "right" if looking_right else "left"


def detect_jump(landmarks) -> bool:
    """
    True for a single frame whenever the shoulder midpoint moves up more
    than JUMP_MOVEMENT_MULTIPLIER times its own recent-normal frame-to-
    frame movement (with an absolute floor so a very still baseline can't
    self-trigger on jitter). No velocity/dt or debounce counter needed --
    each frame is just compared against a running baseline.
    """
    global _prev_shoulder_mid_y, _movement_baseline, _last_jump_delta

    mid_y = _shoulder_mid_y(landmarks)
    jumped = False

    if _prev_shoulder_mid_y is not None:
        delta = _prev_shoulder_mid_y - mid_y  # positive = moved up
        _last_jump_delta = delta

        threshold = max(_movement_baseline * JUMP_MOVEMENT_MULTIPLIER, JUMP_MIN_ABSOLUTE_DELTA)
        if delta > threshold:
            jumped = True

        _movement_baseline += JUMP_BASELINE_SMOOTHING * (abs(delta) - _movement_baseline)

    _prev_shoulder_mid_y = mid_y
    return jumped


def _wrist_is_offscreen(landmark) -> bool:
    """True if this landmark's normalized position falls outside the visible frame."""
    return (
        landmark.x < -WRIST_OFFSCREEN_MARGIN
        or landmark.x > 1 + WRIST_OFFSCREEN_MARGIN
        or landmark.y < -WRIST_OFFSCREEN_MARGIN
        or landmark.y > 1 + WRIST_OFFSCREEN_MARGIN
    )


def _update_wrist_slots(landmarks, now: float) -> None:
    """
    Updates the two persistent wrist-position slots from this frame's pose
    result. Two different "can't currently trust this wrist" cases are
    handled differently:

      - Low visibility (occluded/blurred but still in frame): treated as
        "not seen this frame" and held at its last trusted value until
        WRIST_HOLD_SECONDS passes, then expires. This is what lets a
        crossing that happens "behind" motion blur still get caught.
      - Off-screen (coordinate outside the frame): dropped immediately,
        no hold grace period. There's no swing to bridge over here --
        the hand actually isn't there, so the slot goes empty right away
        and detect_wrist_running's off-screen check forces a full stop.
    """
    global _wrist_slot_y, _wrist_slot_last_seen

    for i, idx in enumerate((LEFT_WRIST, RIGHT_WRIST)):
        landmark = landmarks[idx]
        if _wrist_is_offscreen(landmark):
            _wrist_slot_y[i] = None
            _wrist_slot_last_seen[i] = None
        elif landmark.visibility >= WRIST_VISIBILITY_THRESHOLD:
            _wrist_slot_y[i] = landmark.y
            _wrist_slot_last_seen[i] = now
        elif _wrist_slot_last_seen[i] is not None and (now - _wrist_slot_last_seen[i]) > WRIST_HOLD_SECONDS:
            _wrist_slot_y[i] = None
            _wrist_slot_last_seen[i] = None


def detect_wrist_running(landmarks):
    """
    Returns (moving, sprinting) based on how many times the two wrists'
    vertical positions have crossed each other in the trailing
    RUN_CROSSING_WINDOW_SECONDS. Briefly-occluded wrists (motion blur
    mid-swing) hold their last known position for WRIST_HOLD_SECONDS
    instead of being dropped, so a crossing that happens "behind" the
    blur still counts. A wrist that's actually off-screen skips that
    grace period entirely and forces an immediate stop -- see
    _update_wrist_slots and the off-screen check below.
    """
    global _prev_wrist_diff_sign, _wrist_crossing_times

    now = time.monotonic()
    _update_wrist_slots(landmarks, now)

    if _wrist_slot_y[0] is not None and _wrist_slot_y[1] is not None:
        sign = 1 if _wrist_slot_y[0] >= _wrist_slot_y[1] else -1
        if _prev_wrist_diff_sign is not None and sign != _prev_wrist_diff_sign:
            _wrist_crossing_times.append(now)
        _prev_wrist_diff_sign = sign

    cutoff = now - RUN_CROSSING_WINDOW_SECONDS
    _wrist_crossing_times = [t for t in _wrist_crossing_times if t >= cutoff]

    crossings_per_second = len(_wrist_crossing_times) / RUN_CROSSING_WINDOW_SECONDS
    moving = crossings_per_second >= MOVE_CROSSINGS_PER_SECOND_THRESHOLD
    sprinting = crossings_per_second >= SPRINT_CROSSINGS_PER_SECOND_THRESHOLD

    # The windowed rate above is what makes moving/sprinting smooth while
    # you're actually walking, but it's also what causes the "still moving
    # for a second after you stopped" hang -- a stop is invisible to it
    # until old crossings age out of the window. This catches a real stop
    # immediately: if it's been longer than RUN_STOP_TIMEOUT_SECONDS since
    # the last crossing, there's no live swinging happening right now,
    # full stop, regardless of what the window still remembers.
    if _wrist_crossing_times and (now - _wrist_crossing_times[-1]) > RUN_STOP_TIMEOUT_SECONDS:
        moving = False
        sprinting = False

    # Hard override: either wrist currently off-screen means an immediate
    # stop, no matter what the windowed rate or stop-timeout above say.
    if _wrist_is_offscreen(landmarks[LEFT_WRIST]) or _wrist_is_offscreen(landmarks[RIGHT_WRIST]):
        moving = False
        sprinting = False

    return moving, sprinting


def _draw_debug_overlay(frame, landmarks, look, moving, sprinting, jumped) -> bool:
    """Draws tracked points + live threshold readouts. Returns True if the user pressed 'q' to quit."""
    height, width = frame.shape[:2]

    def to_px(landmark):
        return int(landmark.x * width), int(landmark.y * height)

    if landmarks is not None:
        left_shoulder = to_px(landmarks[LEFT_SHOULDER])
        right_shoulder = to_px(landmarks[RIGHT_SHOULDER])
        nose = to_px(landmarks[NOSE])
        left_ear = to_px(landmarks[LEFT_EAR])
        right_ear = to_px(landmarks[RIGHT_EAR])
        cv2.circle(frame, left_shoulder, 5, (0, 255, 0), -1)
        cv2.circle(frame, right_shoulder, 5, (0, 255, 0), -1)
        cv2.line(frame, left_shoulder, right_shoulder, (0, 255, 0), 1)
        cv2.circle(frame, left_ear, 4, (0, 165, 255), -1)
        cv2.circle(frame, right_ear, 4, (0, 165, 255), -1)
        cv2.line(frame, left_ear, right_ear, (0, 165, 255), 1)
        cv2.circle(frame, nose, 5, (0, 200, 255), -1)

        wrist_colors = [(255, 0, 255), (255, 200, 0)]
        for i, idx in enumerate((LEFT_WRIST, RIGHT_WRIST)):
            landmark = landmarks[idx]
            color = wrist_colors[i] if landmark.visibility >= WRIST_VISIBILITY_THRESHOLD else (100, 100, 100)
            cv2.circle(frame, to_px(landmark), 6, color, -1)

    crossings_per_second = len(_wrist_crossing_times) / RUN_CROSSING_WINDOW_SECONDS
    now = time.monotonic()

    def slot_status(i: int, idx: int) -> str:
        if landmarks is not None and _wrist_is_offscreen(landmarks[idx]):
            return "OFFSCREEN"
        if _wrist_slot_y[i] is None:
            return "empty"
        age = now - _wrist_slot_last_seen[i]
        return "live" if age < 0.05 else f"held {age:.2f}s"

    lines = [
        f"Look: {look or 'none'}  (raw={_last_yaw_ratio:+.2f} smoothed={_smoothed_yaw_ratio:+.2f} ratio, "
        f"enter=+-{LOOK_YAW_ENTER_RATIO} exit=+-{LOOK_YAW_EXIT_RATIO} gain={LOOK_YAW_GAIN})",
        f"  neutral offset: {_yaw_neutral_offset:+.3f}  (press 'n' while looking at the screen to recalibrate)",
        f"Wrist slot0 (L): {slot_status(0, LEFT_WRIST)}  slot1 (R): {slot_status(1, RIGHT_WRIST)}",
        f"Crossings/s: {crossings_per_second:.1f} "
        f"(move>={MOVE_CROSSINGS_PER_SECOND_THRESHOLD}, sprint>={SPRINT_CROSSINGS_PER_SECOND_THRESHOLD})"
        f"   Moving: {moving}   Sprinting: {sprinting}",
        f"Jump delta: {_last_jump_delta:+.3f}  baseline: {_movement_baseline:.3f}  "
        f"thresh: {max(_movement_baseline * JUMP_MOVEMENT_MULTIPLIER, JUMP_MIN_ABSOLUTE_DELTA):.3f}   Jump: {jumped}",
    ]
    y = 20
    for line in lines:
        cv2.putText(frame, line, (10, y), cv2.FONT_HERSHEY_SIMPLEX, 0.5, (255, 255, 255), 1, cv2.LINE_AA)
        y += 20

    cv2.imshow("CV debug", frame)
    key = cv2.waitKey(1) & 0xFF
    if key == ord('n'):
        _calibrate_look_neutral()
    return key == ord('q')


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


def send_lean_packet(sock: socket.socket, lean: str | None) -> None:
    left_click = 1 if lean == "left" else 0
    right_click = 1 if lean == "right" else 0
    line = f"{NODE_ID_LEAN},0,{left_click},{right_click}\n"
    sock.sendall(line.encode("ascii"))


def send_run_packet(sock: socket.socket, moving: bool, sprinting: bool) -> None:
    line = f"{NODE_ID_RUN},{1 if moving else 0},{1 if sprinting else 0},0\n"
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

            timestamp_ms = int((time.monotonic() - _stream_start_time) * 1000)

            landmarks = _get_pose_landmarks(frame, timestamp_ms)
            if landmarks is not None:
                look = detect_look(landmarks)
                jumped = detect_jump(landmarks)
                moving, sprinting = detect_wrist_running(landmarks)
            else:
                look = None
                jumped = False
                moving, sprinting = False, False

            try:
                send_packet(sock, NODE_ID_ARMOR, armor_detected)
                send_packet(sock, NODE_ID_GOLDEN_APPLE, golden_apple_detected)
                send_lean_packet(sock, look)  # node 12 -- mod side still calls this "lean", now driven by head yaw
                send_run_packet(sock, moving, sprinting)
                send_packet(sock, NODE_ID_JUMP, jumped)
            except OSError as e:
                print(f"Lost connection to mod ({e}), reconnecting...")
                sock.close()
                sock = connect()

            if SHOW_DEBUG_WINDOW:
                quit_requested = _draw_debug_overlay(frame, landmarks, look, moving, sprinting, jumped)
                if quit_requested:
                    break

            time.sleep(SEND_INTERVAL_SECONDS)
    finally:
        cap.release()
        sock.close()
        cv2.destroyAllWindows()
        pose_landmarker.close()


if __name__ == "__main__":
    main()