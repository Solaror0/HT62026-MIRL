# Minecraft IRL
<img width="1685" height="933" alt="fd436036-ac04-464b-b787-129b8c901c02" src="https://github.com/user-attachments/assets/e483498d-f2bf-469a-987a-aede3ff599fb" />

**Play Minecraft with your body.** Cardboard props, motion sensors, and computer vision replace your keyboard and mouse entirely.

🏆 Built at [Hack the 6ix 2026](https://hackthe6ix.com) | 🚀 [Devpost](https://devpost.com/software/mirl-minecraft-irl)

[![Minecraft IRL Demo](https://img.youtube.com/vi/vkHMMlXg5PI/maxresdefault.jpg)](https://youtu.be/vkHMMlXg5PI)

*Click the thumbnail to watch the demo*

---

## What it does

Minecraft IRL turns your body and a set of handheld cardboard props into the controller for Minecraft, running a custom Java mod we built during the hackathon.

### Props (ESP32 + IMU)

| Prop | Real-world action | In-game result |
|------|-------------------|----------------|
| ⚔️ Sword | Swing it | Character attacks |
| 🛡️ Shield | Raise it | Character blocks |
| 🏹 Bow | Draw and release | Fires an arrow |

**Auto-equip:** pick up any prop and the game instantly switches to it in your hotbar. Put down the sword, grab the bow, and the game just follows along.

### Full-body computer vision

- **Sprint:** run in place (pump your arms) and your character sprints
- **Jump:** jump in real life and your character jumps in-game
- **Look around:** facial pose estimation tracks your head, so turning your head IRL turns the in-game camera

Everything works simultaneously: sprint toward a mob, jump, turn to face it, grab your sword, and swing. No keyboard. No mouse.

## How it works

```
┌─────────────┐     ┌─────────────┐     ┌─────────────┐
│ Sword ESP32 │     │ Shield ESP32│     │  Bow ESP32  │
│    + IMU    │     │    + IMU    │     │    + IMU    │
└──────┬──────┘     └──────┬──────┘     └──────┬──────┘
       └──────────────────┬┴──────────────────┬┘
                          ▼                   
                 ┌─────────────────┐    ┌──────────────┐
                 │     Laptop      │◄───│    Webcam    │
                 │ Gesture classify│    │ OpenCV: run, │
                 │ + auto-equip    │    │ jump, face   │
                 └────────┬────────┘    └──────────────┘
                          ▼
                 ┌─────────────────┐
                 │ Synthetic KB/   │
                 │ mouse inputs    │
                 └────────┬────────┘
                          ▼
                 ┌─────────────────┐
                 │   Minecraft     │
                 │ + custom Java   │
                 │      mod        │
                 └─────────────────┘
```

1. **Props:** Each cardboard prop houses an ESP32 dev board with an onboard IMU streaming accelerometer/gyro data to the laptop
2. **Gesture classification:** Custom classifiers distinguish a sword swing, a shield raise, and a bow draw-and-release from raw IMU data
3. **Auto-equip:** Pickup detection automatically selects the matching hotbar item when you grab a prop
4. **Computer vision:** An OpenCV pipeline runs three parallel tracks: arm-motion detection for sprinting, jump detection, and facial pose estimation for head-tracked camera control
5. **Game input:** Detected gestures and poses are translated into game actions in real time, driven through synthetic keyboard/mouse events and a custom Java mod we wrote for Minecraft

## Tech stack

- **Hardware:** ESP32 dev boards, onboard IMUs (accelerometer + gyro), cardboard props built during the hackathon
- **Computer vision:** OpenCV, facial pose estimation
- **Input injection:** Synthetic keyboard/mouse event generation
- **Game:** Minecraft (Java Edition) with a custom Java mod built during the hackathon

## Challenges

- **Gesture vs. noise:** A sword swing and an excited hand wave look similar to an IMU. Threshold tuning and debouncing stop the game from spamming attacks
- **Pickup detection:** Distinguishing picking up a prop (equip it) from bumping the table it sits on (do nothing)
- **Smooth head tracking:** Pose estimation needed heavy filtering to control the camera without jitter or nausea
- **Real-time performance:** Multiple CV tracks plus live gesture classification, all without dropping frames
- **Latency:** Body-to-game input has to feel instant or the illusion breaks
- **Cardboard engineering:** Props sturdy enough to survive repeated full-force demo swings, built in one weekend

## What's next

- [ ] Haptic feedback in the props (rumble on hit, tension on the bow)
- [ ] More items: pickaxe for mining, fishing rod, eating gestures
- [ ] Full skeletal pose tracking for crouching, swimming, and placing blocks
- [ ] Fully standalone setup with no laptop required

---

Built in one weekend at Hack the 6ix 2026.
