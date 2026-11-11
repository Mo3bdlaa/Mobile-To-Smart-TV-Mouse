# Design: Android phone as a smart TV touchpad, keyboard, and mic

Status:
- **Implemented:** Bluetooth HID mode (option A) and **Wi-Fi mode with a companion app on
  Android TV** (option C, now the recommended mode). Real-world testing showed some TVs don't
  list a phone in their Bluetooth scan. See the README for how Wi-Fi mode works and is secured.
- **Out of scope for now:** the microphone. Sections 3 and 4 are kept for reference only.

## 1. Goal

Pair an Android phone with a smart TV and use the phone as:

- a **touchpad mouse** (move, click, right-click, scroll, drag)
- a **keyboard** (full text input plus Home/Back/arrows/media/volume keys)
- a **microphone** with push-to-talk and mute

The preferred setup is **Bluetooth pairing only, with nothing installed on the
TV**. A TV-side app is acceptable only where it adds something Bluetooth can't
provide.

## 2. The three ways a phone can control a TV

### A. Bluetooth HID: the phone acts as a Bluetooth keyboard and mouse (recommended core)

Since Android 9 (API 28), the public `BluetoothHidDevice` API lets an app
register the phone as a **Bluetooth HID peripheral**. When you pair it, the TV
sees an ordinary Bluetooth keyboard + mouse, which is the same thing it sees
when you pair a real wireless keyboard.

- **TV side:** nothing to install. You pair once from the TV's Bluetooth
  settings, and after that the phone reconnects to it.
- **Works with:** Android TV / Google TV (full cursor support), Samsung
  Tizen (has a built-in BT HID profile), and many LG webOS models. How well the
  mouse cursor works varies by brand and model, so test on your TV first.
- **Gives us:** mouse movement, buttons, wheel, keyboard keys, and "consumer
  control" keys (volume, play/pause, Home, Back).
- **Can't do:** audio. HID has no audio channel.

Known risks:

1. Some phone makers disable the HID Device profile in their firmware. Check
   your phone before building anything else (section 7).
2. Some TVs list only devices that announce themselves as keyboards or mice and
   hide phones. Test this early on your TV.
3. A HID keyboard sends **key positions, not characters**. The TV turns them
   into letters with *its own* keyboard layout, so non-Latin text (for example
   Arabic) only types correctly if the TV's keyboard layout matches. See
   section 5.

### B. The TV's own network remote protocol (Wi-Fi, still nothing installed on the TV)

Most smart TVs have a built-in remote-control protocol on the local network,
which official remote apps use. You pair once by entering a PIN the TV shows.

| TV platform | Protocol | Keys | Unicode text | Pointer | Voice |
|---|---|---|---|---|---|
| Android TV / Google TV | Android TV Remote v2 (TLS, ports 6466/6467) | yes | yes (IME) | no | yes: phone mic → TV's Google Assistant |
| LG webOS | SSAP over WebSocket (port 3000/3001) | yes | yes | yes (pointer socket) | no public API |
| Samsung Tizen | Remote WebSocket API (port 8001/8002) | yes | yes | model-dependent, needs testing | no public API |

These protocols are reverse-engineered or semi-documented, so they can change
with TV firmware updates. They are best used as **add-ons** to Bluetooth HID,
not as replacements.

### C. A companion app on the TV (only realistic on Android TV)

The TV app receives events over Wi-Fi (paired with a QR code or PIN) and
injects input with an AccessibilityService plus a cursor overlay. This is the
most capable option, but it's the extra load on the TV that you'd rather
avoid. Samsung and LG make sideloading hard, so this option is effectively
Android TV only.

## 3. The microphone: what's possible

There is no public Android API that lets a phone appear to a TV as a
**Bluetooth microphone** (headset/hands-free role). HID can't carry audio
either. So "pair over Bluetooth and the TV gets a new mic" isn't achievable
without root. What we can do instead:

| Option | How it works | Where it works | What it's good for |
|---|---|---|---|
| **M1. Dictation** | The phone converts speech to text (on-device recognizer) and "types" the text over HID | Everywhere HID works | Search boxes, URLs, messages |
| **M2. TV assistant voice** | Phone mic audio is sent through Android TV Remote v2 voice messages | Android TV / Google TV | "Hey Google"-style search and commands |
| **M3. Raw audio stream** | Phone mic is streamed over Wi-Fi to a companion TV app (option C) | Android TV with our app installed | Only usable inside our own TV app. Other TV apps such as video-call apps can't use it as a system mic without system privileges. |

In every option, **push-to-talk and mute are controls on the phone**: the mic
is only open while you hold the button or while the toggle is on.

My recommendation is to start with **M1**, which works on any TV, and add
**M2** if your TV runs Google TV. Real voice calls through the TV (M3) are a
very different project.

## 4. Recommended architecture

```
┌─────────────────────────── Phone app (Kotlin) ───────────────────────────┐
│  UI: Touchpad · Keyboard · Remote keys · Mic (push-to-talk / mute)       │
│                         │                                                │
│                 Input model (pointer, key, text, media, voice)          │
│                         │                                                │
│        ┌────────────────┼──────────────────────┐                        │
│        ▼                ▼                      ▼                        │
│  HID transport     Network transport       Speech-to-text               │
│  (Bluetooth,       (per-TV adapters:        (dictation → text →         │
│   no TV app)        Google TV / LG /         HID or network)            │
│                     Samsung, optional)                                   │
└──────────────────────────────────────────────────────────────────────────┘
          │ Bluetooth HID              │ Wi-Fi, PIN pairing
          ▼                            ▼
        Smart TV (no app needed for either path)
```

The UI produces abstract input events, and transports deliver them. That way
the same touchpad and keyboard work over Bluetooth on any TV, and better paths
(Unicode text, voice) are used automatically when the TV supports them.

### Phases

1. **HID MVP.** Touchpad, keyboard, remote keys, and a foreground service that
   keeps the connection alive and reconnects automatically.
2. **Dictation (M1).** Push-to-talk and a mute toggle, with the recognized text
   typed over HID.
3. **Network adapters.** Google TV first (Unicode text and voice M2), then
   LG/Samsung if needed.
4. **Optional:** an Android TV companion app (option C / M3).

## 5. Bluetooth HID details

**Report descriptor:** a single combo device with three report IDs.

- `ID 1` Keyboard: modifier byte + 6-key rollover (boot-compatible layout)
- `ID 2` Mouse: 3 buttons, relative X/Y (int8, or int16 for smooth motion),
  vertical wheel, horizontal pan (AC Pan)
- `ID 3` Consumer control: Volume +/−, Mute, Play/Pause, Next/Prev, AC Home
  (0x223), AC Back (0x224), AC Search (0x221), Menu

**Connection flow:**

1. App calls `getProfileProxy(HID_DEVICE)` → `registerApp(sdp, …)`.
2. App makes the phone discoverable, and you pick it on the TV under
   *Bluetooth → Add device*.
3. After the first pairing, the app calls `connect(tv)` on start, and a
   foreground service keeps the session alive.
4. Android 12+ permissions: `BLUETOOTH_CONNECT`, `BLUETOOTH_ADVERTISE`,
   `BLUETOOTH_SCAN`.

**Touchpad gestures:**

| Gesture | Action |
|---|---|
| 1-finger move | Move the pointer (with an acceleration curve) |
| Tap | Left click |
| 2-finger tap | Right click / Back |
| 2-finger drag | Scroll (vertical and horizontal) |
| Double-tap and hold, then drag | Drag (button held) |
| Phone volume keys (optional) | TV volume |

**Keyboard:** we capture text from the phone's own keyboard and map each
character to a HID usage plus modifiers using the layout the TV expects (US by
default; selectable). Characters the layout can't produce are typed with the
network adapter when one is available. Otherwise they are skipped, and the app
shows a hint to switch the TV's keyboard layout.

## 6. Tech stack

- Kotlin, Jetpack Compose, minSdk 28 (Android 9), targetSdk latest
- Planned modules:
  - `app/`: UI and foreground service
  - `hid/`: descriptor, report encoding, BluetoothHidDevice wrapper
  - `input/`: gesture engine, key mapping, keyboard layouts
  - `voice/`: speech-to-text and push-to-talk
  - `remote-googletv/`, `remote-lg/`, `remote-samsung/`: optional network adapters
- Unit tests for report encoding and key mapping, since those are pure Kotlin
  and can be tested without a device

## 7. First step: check feasibility on your hardware

Before writing much code, run two quick checks:

1. **Phone supports the HID Device profile?** Install any "Bluetooth keyboard &
   mouse" app from the Play Store that uses this API. If it can't register, the
   phone maker has disabled it.
2. **TV accepts it as a mouse?** Pair it with the TV, then check whether a
   cursor appears and whether typing works in a search box.

If both pass, the Bluetooth-only plan works for your setup.

## 8. Open questions

1. Which TV brand, model, and OS (Google TV, Samsung Tizen, LG webOS, other)?
2. What is the mic for: voice search and typing, the TV's assistant, or real
   voice/video calls?
3. Which phone model and Android version?
4. Which languages do you need to type (English only, or Arabic too)?
