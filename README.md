# TV Mouse (Mobile-To-Smart-TV-Mouse)

TV Mouse is an Android app that turns your phone into a **touchpad, keyboard and remote** for
a smart TV. The same APK runs on the phone (controller) and on an Android / Google TV
(receiver).

There are two ways to connect:

| | **Wi-Fi (recommended)** | **Bluetooth** |
|---|---|---|
| On the TV | TV Mouse installed, Accessibility turned on | Nothing |
| Works with | Android TV / Google TV / Fire TV | TVs that accept Bluetooth keyboards/mice |
| Mouse cursor | Yes (drawn by TV Mouse) | Yes (the TV's own) |
| Typing | Any language (Arabic included) | English (US) characters only |
| Pairing | Once, with a 6-digit code shown on the TV | Once, from the TV's Bluetooth settings |

The phone and TV must be on the **same Wi-Fi / local network**. No server or internet
service is involved: the phone talks to the TV directly.

## Features

- **Touchpad:** move with 1 finger, tap to click, 2-finger tap for Back, 2-finger swipe to
  scroll, long-press (or double-tap and hold) to drag. Hold-able Left / Middle / Right buttons.
- **Keyboard:** live typing (each letter goes to the TV as you type), Enter, Backspace,
  arrows, and Select all / Copy / Cut / Paste on the TV.
- **Copy & paste from the phone:** send any text, or the phone clipboard, to the TV. Or use
  **Share → TV Mouse** from any app.
- **Remote:** D-pad + OK, Back, Home, volume and media keys.
- **Auto-update:** phone and TV check this repo's GitHub Releases at start and offer to install
  new versions.
- **Crash reports:** after a crash, one tap opens a pre-filled GitHub issue.

## Setup

### 1. Phone

Download the APK from the
[latest release](https://github.com/Mo3bdlaa/Mobile-To-Smart-TV-Mouse/releases/latest)
and install it. Allow your browser to install apps when Android asks.

### 2. TV (Wi-Fi mode)

1. On the TV, install **Downloader** (by AFTVnews) from the Play Store.
2. In Downloader, open
   `github.com/Mo3bdlaa/Mobile-To-Smart-TV-Mouse/releases/latest/download/TV-Mouse.apk`
   and install it. Allow Downloader to install apps when the TV asks.
3. Open **TV Mouse** on the TV and press **Open Accessibility settings**. Choose **TV Mouse**
   and turn it on.
   - If it's greyed out or says it's restricted: Settings → Apps → TV Mouse → ⋮ (menu) →
     **Allow restricted settings**, then try again.
   - If your TV has no such menu, with ADB (`adb connect <tv-ip>`):
     `adb shell appops set io.github.mo3bdlaa.tvmouse ACCESS_RESTRICTED_SETTINGS allow`
4. The TV Mouse screen on the TV now says **Ready** and shows the TV's IP address.

### 3. Pair

1. On the phone: TV Mouse → **Connect** → **Wi-Fi**. Your TV appears in the list.
   - If it doesn't, tap **Add TV by IP address** and type the address shown on the TV.
2. Tap **Pair**. A 6-digit code appears on the TV. Type it on the phone.
3. Done. Next time the phone reconnects to that TV automatically.

## How the Wi-Fi connection works

- The TV advertises itself on the local network (`_tvmouse._tcp`, port 47321), so the
  phone finds it without typing an address.
- **Pairing** uses an ECDH (P-256) key exchange confirmed with the 6-digit code shown on the
  TV. Both sides derive a shared secret, and nothing secret is sent over the network.
- **Every connection** proves both sides know that secret (challenge-response in both
  directions). After that, all input, including everything you type, is **encrypted with
  AES-256-GCM**.
- Only phones paired with a code shown on the TV can connect. The TV app lists paired phones
  and lets you **Forget** them.
- On the TV, input happens through Android's Accessibility API. TV Mouse draws a cursor,
  taps and swipes where it points, and types into the selected text box.
- It's local network only by design. Reaching the TV over the internet would need a relay
  server or opening ports on the router.

The code is in `app/src/main/java/io/github/mo3bdlaa/tvmouse/net/` (protocol, phone client)
and `.../tv/` (TV receiver). `EndToEndTest` runs the real TV server against the real phone
handshake.

## Bluetooth mode

Connect tab → **Bluetooth** → **Make phone visible**, then pair from the TV's Bluetooth
settings. Many TVs don't list phones, and some phone makers disable this feature. In that
case use Wi-Fi mode.

## Releases (GitHub Actions)

- **CI** (`.github/workflows/ci.yml`) runs on every push and pull request. It runs the unit
  tests, builds a debug APK and uploads it as a workflow artifact.
- **Release** (`.github/workflows/release.yml`) runs from **Actions → Release → Run
  workflow**. Pick `patch`, `minor` or `major` and optionally write notes. It works out the
  next version from the latest `v*` tag, runs the tests, builds a signed APK and publishes a
  GitHub release with `TV-Mouse.apk` attached. Installed apps (phone and TV) see it the next
  time they open.

## Crash reports and issues

- When the app crashes, a **"TV Mouse stopped unexpectedly"** screen opens. **Report on
  GitHub** opens a new issue with the crash details filled in and labeled `crash`. You review
  it and press *Submit*. On a TV without a browser, report from the phone instead.
- If the crash screen couldn't open, the app asks the next time you open it.
- **Menu → Report a problem** opens a bug report with app and phone details.
- **Menu → Touchpad settings → Test crash reporting** triggers a test crash.

## Signing

Every release must be signed with the **same key**, or Android refuses to install it as an
update. The key is never stored in the repository. The release workflow reads it from these
repository secrets (*Settings → Secrets and variables → Actions*):

| Secret | Value |
|---|---|
| `SIGNING_KEYSTORE_BASE64` | The keystore file, base64-encoded (`base64 -w0 release.jks`) |
| `SIGNING_STORE_PASSWORD` | The keystore password |
| `SIGNING_KEY_ALIAS` | Optional. Defaults to `tvmouse` |
| `SIGNING_KEY_PASSWORD` | Optional. Defaults to the keystore password |

The workflow stops with an error if the first two are missing. Keep a backup of the keystore
and password somewhere safe.

## Build locally

You need JDK 17+ and the Android SDK (platform 36).

```
./gradlew testDebugUnitTest assembleDebug
```

Local release builds without the signing variables are signed with the debug key (testing
only). The debug build installs next to the release build with the package suffix `.debug`.

## Project layout

```
app/src/main/java/io/github/mo3bdlaa/tvmouse/
  Input.kt  Sends screen input over Wi-Fi or Bluetooth
  net/      Wi-Fi protocol, encryption, pairing, phone client, TV discovery
  tv/       TV receiver: accessibility service (cursor, taps, typing), server, TV screen
  hid/      Bluetooth HID: report descriptor, report encoding, key map, connection service
  ui/       Phone screens: Connect, Touchpad, Keyboard, Remote; shared dialogs
  update/   GitHub Releases update check, download and install
  crash/    Crash capture, crash screen, GitHub issue links
docs/DESIGN.md   Background and options considered
```
