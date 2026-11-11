# Mobile-To-Smart-TV-Mouse

Use an Android phone as a **touchpad mouse, keyboard, and push-to-talk
microphone** for a smart TV. The goal is to pair over Bluetooth with **nothing
installed on the TV**.

## How it will work

- **Bluetooth HID (core):** the phone pairs with the TV as a standard Bluetooth
  keyboard + mouse (Android 9+). It needs no TV app and works with Android TV /
  Google TV, Samsung, and many LG models.
- **Voice:** push-to-talk dictation on the phone, typed into the TV. On Google
  TV the phone mic can also go to the TV's assistant.
- **Optional network adapters:** use the TV's built-in Wi-Fi remote protocol for
  extras like Unicode text and voice, with a PIN-pairing step.

See [docs/DESIGN.md](docs/DESIGN.md) for the full design, the trade-offs, and
the open questions.

## Status

Design phase. The first milestone is a Bluetooth HID MVP with a touchpad,
keyboard, and remote keys.
