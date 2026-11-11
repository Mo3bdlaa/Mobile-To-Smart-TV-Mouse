# TV Mouse (Mobile-To-Smart-TV-Mouse)

TV Mouse is an Android app that turns your phone into a **Bluetooth touchpad, keyboard and
remote** for a smart TV. The TV sees the phone as an ordinary Bluetooth keyboard + mouse, so
**you don't install anything on the TV**.

- **Touchpad:** move with 1 finger, tap to click, 2-finger tap for right-click / Back,
  2-finger swipe to scroll, long-press (or double-tap and hold) to drag. Hold-able
  Left / Middle / Right buttons are also available.
- **Keyboard:** live typing (each key goes to the TV as you type), plus Enter, Backspace,
  arrows, Tab, Esc and Select all / Copy / Cut / Paste / Undo shortcuts.
- **Copy & paste from the phone:** send any text, or the phone clipboard, to the TV. You can
  also **Share → TV Mouse** from any app.
- **Remote:** D-pad + OK, Back, Home, Menu, Search, volume and media keys.
- **Auto-update:** the app checks this repo's GitHub Releases when it starts and offers to
  install new versions.
- **Crash reports:** if the app crashes, a screen lets you open a pre-filled GitHub issue
  with the crash details.

Requirements: Android 9 or newer on the phone, and a TV that accepts Bluetooth keyboards/mice
(Android TV / Google TV, Samsung Tizen, many LG webOS models).

## Install

1. On the phone, open the
   [latest release](https://github.com/Mo3bdlaa/Mobile-To-Smart-TV-Mouse/releases/latest)
   and download `TV-Mouse-vX.Y.Z.apk`.
2. Open it and allow your browser to install apps when Android asks.
3. Later updates are offered inside the app, so you only do this once.

## Pair with the TV (first time)

1. Open TV Mouse and allow the **Nearby devices** permission.
2. On the **Connect** tab, tap **Make phone visible**.
3. On the TV, open Settings → Bluetooth (Google TV: *Remotes & Accessories → Pair accessory*)
   and choose your phone. Confirm the code on both screens.
4. The app switches to the touchpad once connected. Next time it reconnects by itself, or you
   can tap **Connect** next to the TV.

> If the TV and phone were paired before, **forget the pairing on both** and pair again.
> Otherwise the TV may still treat the phone as a phone instead of a keyboard and mouse.

Some phone makers disable the Bluetooth "keyboard/mouse" (HID Device) feature. If so, the
Connect tab says **Not supported on this phone**.

Typing sends key presses with an English (US) layout, which is what TVs expect by default.
Characters that aren't on a US keyboard (for example Arabic) are skipped, and the app tells
you how many were skipped.

## Releases (GitHub Actions)

- **CI** (`.github/workflows/ci.yml`) runs on every push and pull request. It runs the unit
  tests, builds a debug APK and uploads it as a workflow artifact.
- **Release** (`.github/workflows/release.yml`) runs from **Actions → Release → Run
  workflow**. Pick `patch`, `minor` or `major` and optionally write release notes. The
  workflow:
  1. works out the next version from the latest `v*` tag (the first one is `v0.0.1`),
  2. runs the tests and builds a signed release APK,
  3. creates the tag and a GitHub release with `TV-Mouse-vX.Y.Z.apk` attached.

  Installed apps see the new release the next time they open.

## Crash reports and issues

- When the app crashes, a **"TV Mouse stopped unexpectedly"** screen opens. **Report on
  GitHub** opens a new issue with the crash details filled in and labeled `crash`. You review
  it and press *Submit*.
- If that screen can't open (for example the crash happened in the background), the app asks
  the next time you open it.
- **Menu → Report a problem** opens a bug report with the app and phone details filled in.
- **Menu → Touchpad settings → Test crash reporting** triggers a test crash so you can try
  the flow.

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

The workflow stops with an error if the first two are missing. To create a key yourself:

```
keytool -genkeypair -keystore release.jks -alias tvmouse -keyalg RSA -keysize 2048 -validity 36500
```

Keep a backup of the keystore and password somewhere safe. If you lose them, the next
release can't update the installed app, and you'll have to uninstall and reinstall once.

## Build locally

You need JDK 17+ and the Android SDK (platform 36).

```
./gradlew testDebugUnitTest assembleDebug
```

The debug build installs next to the release build as "TV Mouse" with the package suffix
`.debug`, and it doesn't check for updates on its own.

## Project layout

```
app/src/main/java/io/github/mo3bdlaa/tvmouse/
  hid/      Bluetooth HID: report descriptor, report encoding, key map, connection service
  ui/       Compose screens: Connect, Touchpad, Keyboard, Remote
  update/   GitHub Releases update check, download and install
  crash/    Crash capture, crash screen, GitHub issue links
docs/DESIGN.md   Background, options considered, and how it works
```
