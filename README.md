<p align="center">
  <img src="docs/assets/logo.svg" width="112" alt="Afterbeep logo"/>
</p>

<h1 align="center">Afterbeep</h1>

<p align="center">
  <b>Voicemail that lives on your phone — the iPhone feature Android never got.</b><br/>
  When you don't pick up, your Android phone answers, plays <i>your</i> greeting into the call,
  records the caller and keeps the message in a beautiful inbox — no carrier voicemail, no cloud, no root.
</p>

<p align="center">
  <a href="https://github.com/agarwalvivek29/afterbeep/releases/latest"><img alt="Latest release" src="https://img.shields.io/github/v/release/agarwalvivek29/afterbeep?color=FF8A4C"></a>
  <img alt="Android 13+" src="https://img.shields.io/badge/Android-13%2B-43E5A0">
  <img alt="No root" src="https://img.shields.io/badge/root-not%20needed-0B0C10">
  <a href="LICENSE"><img alt="License: MIT" src="https://img.shields.io/badge/license-MIT-FFB547"></a>
</p>

---

## Why

**iPhones have voicemail built in. Android phones don't.**

On an iPhone, a call you miss is answered on the device and the caller can leave a message right there
(Live Voicemail). On Android you're left with whatever your carrier offers — and many carriers, including most in
India, offer nothing at all. The alternatives aren't great either: cloud answering services send your callers'
voices to someone else's servers, and Google's call screening is limited to Pixel phones in a few countries.

There's a reason no Android app has filled the gap: Android doesn't let an ordinary app answer a call *and* talk
into it. Afterbeep finds a way around that — without root — and brings iPhone-style, on-device voicemail to
Android. Everything happens on the phone:

- 📞 **Answers calls you miss** after a delay you choose.
- 🗣️ **Plays your greeting into the call** — recorded in your voice or generated with text-to-speech — then a beep.
- 🎙️ **Records the caller** and drops the message into an inbox with a waveform player, 1×/1.5×/2× speed, call back, archive.
- 🤫 **Private both ways:** the caller never hears your microphone or surroundings, and nobody near your phone hears the caller.
- 🔔 **Never surprises you:** an always-on status notification and a Quick Settings tile show whether voicemail is **On** or **Off** right now.
- 🔒 **Locked down:** only devices you've explicitly trusted can use the debugging connection Afterbeep relies on.

## Demo

<p align="center">
  <img src="demo/01-inbox.png" width="250" alt="Inbox"/>
  &nbsp;
  <img src="demo/02-message.png" width="250" alt="A message"/>
  &nbsp;
  <img src="demo/03-greeting.png" width="250" alt="Greeting"/>
</p>
<p align="center"><sub>Inbox · a message · your greeting — on a OnePlus 13s with made-up demo callers. More in <a href="demo/"><code>demo/</code></a>.</sub></p>

## How it works (in one minute)

Ordinary apps can't inject audio into a phone call — we tried everything, the full investigation is in
[ADR 0001](docs/decisions/0001-route-4-shell-helper.md). The **shell user** (the identity behind `adb shell`)
*can*: it holds `CALL_AUDIO_INTERCEPTION`, `CAPTURE_AUDIO_OUTPUT`, `MODIFY_PHONE_STATE` and friends.

So Afterbeep is two pieces:

```
┌──────────────────────────┐          files + broadcasts           ┌────────────────────────────────┐
│  Afterbeep app (you)     │ ───────── config.json ──────────────▶ │  Engine  (runs as shell, uid   │
│  inbox · greeting ·      │ ◀──────── status.json heartbeat ───── │  2000, started via the phone's │
│  settings · tile · alerts│ ◀──────── inbox/*.wav + NEW_MESSAGE ─ │  own debugging connection)     │
└──────────────────────────┘                                       └────────────────────────────────┘
                                                                     │ waits for ring → answers →
                                                                     │ mutes your mic → silences
                                                                     │ earpiece → plays greeting +
                                                                     │ beep into the call → records
                                                                     ▼ the caller → hangs up → saves
```

1. **Once**, the app pairs with your phone's own **Wireless debugging** (exactly like a computer would, but from
   the phone itself — no computer needed, ever).
2. It uses that connection to launch the **engine**: a small background process running as the shell user.
3. The engine does the call work. It keeps running even if Android kills the app, and the app just reads its
   heartbeat and messages.

## Requirements

| | |
|---|---|
| Android | **13 or newer** |
| Root | **Not needed** |
| Computer | **Not needed** (pairing happens on the phone) |
| Tested on | OnePlus 13s, OxygenOS 16 (Android 16) |
| Should work on | Other OnePlus / OPPO / realme phones (same audio stack). Other brands: unknown — please [report](../../issues/new/choose) |

> ⚠️ Silencing the caller on *your* phone uses OnePlus-specific audio parameters. On other brands the greeting
> and recording may work while the caller is still faintly audible from your earpiece. **Always do a test call
> first** in a place where that wouldn't matter.

## Install

1. Download the latest **`Afterbeep-x.y.z.apk`** from [Releases](https://github.com/agarwalvivek29/afterbeep/releases/latest) and open it (allow installs from your browser/files app).
2. Turn on **Developer options**: *Settings → About device → Version →* tap **Build number** 7 times.
3. Open Afterbeep and follow the four setup steps:

| Step | What you do | Why |
|---|---|---|
| **1. Notifications & contacts** | Allow | So new messages ping you and show caller names |
| **2. Pair** | Tap *Start pairing* → in Developer options open **Wireless debugging → Pair device with pairing code** → type the code into the Afterbeep notification | Gives Afterbeep its own trusted key for your phone's debugging service — the way it launches the engine. One time only |
| **3. USB debugging** | Turn on **USB debugging** in Developer options (no cable needed) | Keeps the engine alive when you leave Wi-Fi. Without it, Android shuts the debugging service down when Wireless debugging turns off — and takes the engine with it |
| **4. Start engine** | Tap *Start engine* | Launches the engine. The status turns green: **Listening** |

Then record a greeting in the **Greeting** tab and do a test call from another phone.

**Recommended extras**

- Add the **Voicemail** tile to Quick Settings (*Settings tab → Alerts → Add to Quick Settings*).
- Add Android's own **Wireless debugging** tile: *Developer options → Quick settings developer tiles → Wireless debugging*. After a reboot, restarting Afterbeep is one tap on it (see [Reboots](#reboots)).

## Settings — what they do and *why* they exist

### Engine
| Control | What it does | Why |
|---|---|---|
| **Start / Restart / Stop** | Launches, relaunches or stops the engine | Restart is safe at any time: a new engine *asks* the old one to hand over, and the old one always finishes a call in progress first. Nothing can cut a message off mid-call or leave your mic unmuted |
| **USB debugging warning** | Shows in amber if USB debugging is off | That's the one setting that keeps the engine alive off Wi-Fi |

### Answering
| Setting | Default | Why |
|---|---|---|
| **Take messages** | On | Pause voicemail without stopping the engine — calls just ring normally |
| **Answer after** | 15 s (3–40) | How long the phone rings before Afterbeep picks up. Long enough to grab the phone, short enough that callers don't give up. If you use carrier forwarding as a backup, keep this **shorter** than the carrier's no-answer timer |
| **Longest message** | 2:00 (0:30–5:00) | Caps recording length so a pocket-dial can't fill your storage. Afterbeep also hangs up early when the line goes quiet: after 10 s with no speech from the beep (nothing is saved), or 10 s after the caller stops speaking (the silence is cut from the message) |

### Privacy
| Setting | Default | Why |
|---|---|---|
| **Silent on this phone** | On | Mutes the call on *your* side while Afterbeep talks to the caller, so people around you never hear them. (Your microphone is **always** muted during a message — that isn't optional.) |
| **Favourites ring through** | Off | Starred contacts are never sent to voicemail — they keep ringing until you answer or they hang up |

### Alerts
| Setting | Why |
|---|---|
| **Status notification** | A silent, always-on notification: *Voicemail on · Listening*, *Taking a message…*, or *Voicemail off* with the reason and a Start button. Voicemail that silently stopped working is worse than none — this makes it impossible to miss |
| **Quick Settings tile** | Android doesn't let apps pin a notification to the top, so the tile is the always-visible status: lit = answering calls, dim = off. Tap to open/start |
| **New-voicemail notifications** | Banner with **Listen** and **Call back**. OnePlus hides banners for new apps by default — this button takes you straight to the switch |
| **Unread** | A message stays *unheard* until you've actually listened to ~90% of it — opening it isn't enough |

### Security
| Setting | Default | Why |
|---|---|---|
| **Lock debugging to trusted devices** | On | Afterbeep needs the phone's debugging service, so it treats it like a door it's responsible for. With the lock on, the engine **instantly rejects every "Allow USB debugging?" prompt** (closed within ~0.1 s, before you could tap *Allow* by mistake) and alerts you. Only keys already trusted — Afterbeep's own and any computer you pair with a **pairing code** — get in. Turn it off only if you want the normal prompts back for a USB cable |

### After a reboot
| Setting | Why |
|---|---|
| **Restart automatically** | Only available if Android grants Afterbeep `WRITE_SECURE_SETTINGS` (it tries on every start). With it, Afterbeep can flip Wireless debugging on for a few seconds by itself after a reboot. Many OEM builds (including OxygenOS 16) refuse that grant — then use the one-tap flow below |

## Reboots

A phone reboot ends every process, including the engine. Starting it again needs the debugging connection,
and right after a boot the only way an app can reach it is **Wireless debugging, which requires Wi-Fi**
(any network — no internet needed).

- After a reboot you get **"Voicemail is paused"** and the tile shows **Off**. Calls ring normally meanwhile.
- On any Wi-Fi, tap the **Wireless debugging** tile once. Afterbeep notices, starts the engine, and moves the
  debugging service to a **private random port** so it never needs Wireless debugging again until the next
  reboot. Turn Wireless debugging back off and leave Wi-Fi whenever you like.

Between reboots, Start / Restart / crash recovery work **anywhere, without Wi-Fi**.

## Security model

Afterbeep deliberately uses a powerful thing — the phone's debugging service — so here is exactly what it does:

- **Trusted keys only.** Afterbeep generates its own key on first run (stored in its private app storage) and
  pairs it with a pairing code you type. Nobody else's key is added.
- **Random port.** On every engine start the debugging service's plain-TCP listener moves to a new random port
  (20000–60000, never the well-known 5555 that ADB worms scan for). Afterbeep itself only ever connects over
  `127.0.0.1` — its traffic never leaves the phone.
- **Prompt guard.** Any new "Allow debugging?" request is denied and dismissed within ~0.1 s, and you get a
  *"Blocked a connection attempt"* notification. (Android doesn't let anything short of root remove that
  dialog entirely; this is the next best thing.)
- **Mid-call safety.** Moving the port restarts the debugging service, which would end the engine — so it's
  never done during a call.
- **What's still on you:** don't approve debugging prompts you didn't start, keep your phone updated, and turn
  off USB/Wireless debugging if you uninstall Afterbeep.

Found a hole? See [SECURITY.md](SECURITY.md).

## Troubleshooting

Everything Afterbeep does is logged in plain text in `Android/data/dev.afterbeep/files/`:

| File | What's in it |
|---|---|
| `startup.log` | Every start attempt: Wi-Fi/USB/Wireless-debugging state, which route was used, success or the exact error |
| `engine.log` | Every call: ringing → answered → privacy applied → greeting → recording → saved (numbers are masked) |

| Symptom | Fix |
|---|---|
| *"Not paired yet"* | Settings → **Pair with Wireless debugging** |
| *"Connect to Wi-Fi first" / "Turn on Wireless debugging"* | Needed once after each reboot — see [Reboots](#reboots) |
| Engine stops when leaving Wi-Fi | Turn on **USB debugging** |
| No banner for new messages | Settings → Alerts → **Notification settings** → turn on Banners |
| Caller faintly audible on your phone (non-OnePlus) | Please open an issue with your model + `engine.log` |

## Building from source

```bash
git clone https://github.com/agarwalvivek29/afterbeep && cd afterbeep
./gradlew :app:assembleDebug        # → app/build/outputs/apk/debug/app-debug.apk
```

Needs JDK 17+ and the Android SDK with platform `android-37.2` (`sdkmanager "platforms;android-37.2"`).
Release builds are signed with a key read from `keystore.properties` (not in git); without it they fall back to
the debug key.

```
app/src/main/java/dev/afterbeep/
├── engine/Engine.java          # the shell-uid engine: answer, privacy, greeting, record, guard
├── engine/EngineController.kt  # pairing, launching, port rotation, start-when-ready, diagnostics
├── engine/StatusService.kt     # always-on status notification
├── engine/VoicemailTile.kt     # Quick Settings tile
├── adb/                        # in-app Wireless-debugging pairing (libadb-android)
├── audio/                      # greeting recorder / TTS, message player
├── data/                       # settings + inbox repository
└── ui/                         # Compose UI: inbox, greeting, settings, setup
docs/decisions/                 # architecture decision records
research/                       # the proof-of-concept spike + the plain-app experiment we ruled out
```

## Roadmap

- [ ] Optional backup forwarding to a cloud number (e.g. Twilio) for when the phone is off or just rebooted
- [ ] "Missed call, no message" entries for callers who hang up at the beep
- [ ] Per-contact greetings
- [ ] On-device transcription

## Contributing

Issues and PRs are very welcome — especially **test reports from other phones**. See [CONTRIBUTING.md](CONTRIBUTING.md).

## Acknowledgements

- [libadb-android](https://github.com/MuntashirAkon/libadb-android) by Muntashir Akon — in-app adb pairing & connection
- [Shizuku](https://github.com/RikkaApps/Shizuku) and JemRec — proof that the shell-user approach works for real apps
- Built with Jetpack Compose and Material 3

## License

[MIT](LICENSE) © 2026 Vivek Agarwal
