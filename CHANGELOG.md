# Changelog

## Unreleased

- Repeat callers ring through: if the same number calls again within 3 minutes of their last call, the
  phone rings instead of going to voicemail. On by default (Settings → Privacy).

## 1.0.0 — 2026-11-21

First public release.

- On-device answering: answers missed calls after a configurable delay, plays your greeting + a beep into the
  call, records the caller.
- Privacy: your microphone is muted for the whole message; the call is silenced on your phone (OnePlus).
- Inbox with Unread / All / Archived, waveform player, speed control, call back; messages stay unread until heard.
- Greeting: record your own or generate with text-to-speech.
- In-app Wireless-debugging pairing — no computer needed.
- Engine survives the app being killed and Wi-Fi changes (with USB debugging on).
- Safe hand-over: restarting never cuts a call or leaves the mic unmuted.
- Always-on status notification, Quick Settings tile, "voicemail stopped" alerts.
- Security: debugging prompt guard, random private port per start, trusted keys only.
- After reboot: one tap on the Wireless debugging tile restarts everything.
- Plain-text `startup.log` / `engine.log` for troubleshooting.
