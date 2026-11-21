package dev.afterbeep.engine;

import android.content.AttributionSource;
import android.content.ContextWrapper;
import android.media.AudioAttributes;
import android.media.AudioDeviceInfo;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.AudioTrack;
import android.media.MediaRecorder;
import android.os.Binder;
import android.os.IBinder;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.RandomAccessFile;
import java.lang.reflect.Method;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * Afterbeep engine. Runs as the ADB shell user (uid 2000) via app_process, started by the app over
 * self-paired wireless debugging. Shell holds CALL_AUDIO_INTERCEPTION / CAPTURE_AUDIO_OUTPUT /
 * MODIFY_PHONE_STATE, which a normal app cannot get (see docs/decisions/0001).
 *
 * Validated flow (OnePlus 13s, Android 16):
 *   ring N s -> acceptRingingCall -> mic mute + MODE_CALL_SCREENING (held by our Binder token)
 *   -> silence earpiece (voice stream mute + OnePlus HAL rx mute)
 *   -> greeting: AudioTrack VOICE_COMMUNICATION + preferred TYPE_TELEPHONY (lands on in_call_music)
 *   -> record caller: AudioRecord VOICE_DOWNLINK -> WAV
 *   -> endCall, restore everything, tell the app.
 *
 * Usage: app_process / dev.afterbeep.engine.Engine <dataDir> <versionCode>
 */
public final class Engine {
    static final String PKG_SHELL = "com.android.shell";
    static final String APP_PKG = "dev.afterbeep";
    static final Binder MODE_TOKEN = new Binder();

    static final String[] RX_MUTE_ON = {
            "OPLUS_VC_DOWNLINK_MUTE_MODE=enable", "device_mute=true;direction=rx", "voice_translation_rx_mute=true"};
    static final String[] RX_MUTE_OFF = {
            "OPLUS_VC_DOWNLINK_MUTE_MODE=disable", "device_mute=false;direction=rx", "voice_translation_rx_mute=false"};

    static File dataDir, inboxDir, logFile;
    static Object telecom, audio;
    static volatile String state = "idle";
    /** Held (never read) so no other engine can start while this one lives. */
    static Object engineSlot;
    static int version;

    public static void main(String[] args) throws Exception {
        if (Looper.getMainLooper() == null) Looper.prepareMainLooper();
        dataDir = new File(args[0]);
        version = args.length > 1 ? Integer.parseInt(args[1]) : 0;
        inboxDir = new File(dataDir, "inbox");
        inboxDir.mkdirs();
        logFile = new File(dataDir, "engine.log");
        escapeAdbdCgroup();

        // Single instance, graceful hand-over. The abstract socket name is held for the life of the process,
        // so a second engine can't bind it. Instead of killing the old one (which could cut a message mid-call
        // and leave the phone un-muted), ask it to stop and wait: it only checks the stop flag between calls,
        // so it always finishes the call it's on first.
        android.net.LocalServerSocket slot = null;
        boolean announced = false;
        while (slot == null) {
            try {
                slot = new android.net.LocalServerSocket("dev.afterbeep.engine");
            } catch (java.io.IOException busy) {
                if (!announced) { log("another engine is running -> asking it to hand over"); announced = true; }
                try { new File(dataDir, "engine.stop").createNewFile(); } catch (Throwable ignored) {}
                Thread.sleep(500);
            }
        }
        engineSlot = slot;
        new File(dataDir, "engine.stop").delete();

        telecom = service("telecom", "com.android.internal.telecom.ITelecomService$Stub");
        audio = service("audio", "android.media.IAudioService$Stub");
        log("engine start pid=" + android.os.Process.myPid() + " uid=" + android.os.Process.myUid() + " v" + version);
        restoreAudio(null, -1); // in case a previous engine died mid-call

        Thread hb = new Thread(Engine::heartbeatLoop, "heartbeat");
        hb.setDaemon(true);
        hb.start();

        Thread guard = new Thread(Engine::adbGuardLoop, "adb-guard");
        guard.setDaemon(true);
        guard.start();

        boolean wasRinging = false;
        while (!new File(dataDir, "engine.stop").exists()) {
            boolean ringing = Boolean.TRUE.equals(call(telecom, "isRinging"));
            if (ringing && !wasRinging) {
                try { onRing(); } catch (Throwable t) { log("onRing failed: " + stack(t)); state = "idle"; }
            }
            wasRinging = ringing;
            Thread.sleep(250);
        }
        log("engine stop requested");
        writeStatus(false);
        System.exit(0);
    }

    // ------------------------------------------------------------------ call handling

    static final RepeatCallers REPEATS = new RepeatCallers();

    static void onRing() throws Exception {
        JSONObject cfg = readConfig();
        String number = incomingNumber();
        log("ringing from " + mask(number));
        try { handleRing(cfg, number); }
        finally { REPEATS.remember(number, System.currentTimeMillis()); }
    }

    static void handleRing(JSONObject cfg, String number) throws Exception {
        if (!cfg.optBoolean("enabled", true)) { log("disabled -> let it ring"); return; }
        if (isSkipped(cfg, number)) { log("number on ring-through list -> let it ring"); return; }
        if (cfg.optBoolean("repeatRingThrough", true) && REPEATS.isRecent(number, System.currentTimeMillis())) {
            log("same number called again within 3 min -> let it ring"); return;
        }

        state = "ringing";
        long waitMs = Math.max(1, cfg.optInt("ringSeconds", 15)) * 1000L;
        long until = System.currentTimeMillis() + waitMs;
        while (System.currentTimeMillis() < until) {
            if (!Boolean.TRUE.equals(call(telecom, "isRinging"))) { log("picked up / declined / caller hung up"); state = "idle"; return; }
            Thread.sleep(200);
        }
        takeMessage(cfg, number);
    }

    static void takeMessage(JSONObject cfg, String number) throws Exception {
        String id = new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(new Date());
        File wav = new File(inboxDir, id + ".wav");
        File part = new File(inboxDir, id + ".part");
        int maxSecs = Math.max(10, cfg.optInt("maxSeconds", 120));
        boolean silence = cfg.optBoolean("silenceLocal", true);
        Object origVol = null;
        long startedAt = System.currentTimeMillis();
        double[] result = {0, 0}; // seconds, peak dBFS

        state = "answering";
        call(telecom, "acceptRingingCall");
        for (int i = 0; i < 40 && !modeIs(2); i++) Thread.sleep(100);
        log("answered, mode=" + call(audio, "getMode"));
        try {
            call(audio, "setMicrophoneMute", true);
            call(audio, "setMode", 4 /* MODE_CALL_SCREENING */);
            Thread.sleep(200);
            if (silence) {
                origVol = call(audio, "getStreamVolume", 0);
                call(audio, "setStreamVolume", 0, 0, 0);
                call(audio, "adjustStreamVolume", 0, -100 /* ADJUST_MUTE */, 0);
                for (String kv : RX_MUTE_ON) setParam(kv);
            }
            log("private: micMuted=" + call(audio, "isMicrophoneMuted") + " mode=" + call(audio, "getMode")
                    + " voiceMuted=" + call(audio, "isStreamMute", 0));

            state = "greeting";
            File greeting = new File(dataDir, "greeting.wav");
            if (greeting.exists()) playGreeting(greeting);
            else log("no greeting.wav -> skipping greeting");
            beep();

            state = "recording";
            result = record(part, maxSecs);
        } finally {
            if (Boolean.TRUE.equals(call(telecom, "isInCall"))) { call(telecom, "endCall"); log("endCall"); }
            restoreAudio(origVol, silence ? 1 : 0);
            state = "idle";
        }

        if (result[0] < 1.0) { log("message too short (" + result[0] + "s) -> discarded"); part.delete(); return; }
        finishWav(part, 16000);
        if (!part.renameTo(wav)) log("rename failed");

        JSONObject meta = new JSONObject();
        meta.put("id", id);
        meta.put("number", number == null ? "" : number);
        meta.put("receivedAt", startedAt);
        meta.put("durationMs", (long) (result[0] * 1000));
        meta.put("peakDb", result[1]);
        meta.put("file", wav.getName());
        writeAtomic(new File(inboxDir, id + ".json"), meta.toString(2));
        log(String.format(Locale.US, "saved %s %.1fs peak=%.1fdB", id, result[0], result[1]));
        notifyApp(id);
    }

    static boolean modeIs(int m) { Object o = call(audio, "getMode"); return o instanceof Integer && (Integer) o == m; }

    static void restoreAudio(Object origVol, int silenced) {
        try {
            for (String kv : RX_MUTE_OFF) setParam(kv);
            call(audio, "adjustStreamVolume", 0, 100 /* ADJUST_UNMUTE */, 0);
            if (origVol instanceof Integer) call(audio, "setStreamVolume", 0, (Integer) origVol, 0);
            call(audio, "setMode", 0);
            call(audio, "setMicrophoneMute", false);
        } catch (Throwable t) {
            log("restore failed " + t);
        }
    }

    // ------------------------------------------------------------------ audio

    /** VOICE_COMMUNICATION + preferred TYPE_TELEPHONY output -> HAL in_call_music -> caller hears it. */
    static void playGreeting(File f) throws Exception {
        byte[] wav = readAll(f);
        int rate = le32(wav, 24), ch = le16(wav, 22), off = findData(wav), len = wav.length - off;
        AudioFormat fmt = new AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(rate)
                .setChannelMask(ch == 1 ? AudioFormat.CHANNEL_OUT_MONO : AudioFormat.CHANNEL_OUT_STEREO).build();
        AudioTrack t = new AudioTrack.Builder()
                .setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                .setAudioFormat(fmt).setTransferMode(AudioTrack.MODE_STREAM)
                .setBufferSizeInBytes(Math.max(AudioTrack.getMinBufferSize(rate, fmt.getChannelMask(), AudioFormat.ENCODING_PCM_16BIT) * 4, 48000))
                .build();
        AudioDeviceInfo tx = device(2, AudioDeviceInfo.TYPE_TELEPHONY);
        boolean pref = tx != null && t.setPreferredDevice(tx);
        t.play();
        for (int w = 0; w < len; ) {
            if (!Boolean.TRUE.equals(call(telecom, "isInCall"))) break;
            int n = t.write(wav, off + w, Math.min(8192, len - w));
            if (n < 0) { log("greeting write err " + n); break; }
            w += n;
        }
        long frames = len / (2L * ch), until = System.currentTimeMillis() + 3000 + frames * 1000 / rate;
        while (t.getPlaybackHeadPosition() < frames - 10 && t.getPlayState() == AudioTrack.PLAYSTATE_PLAYING
                && System.currentTimeMillis() < until) Thread.sleep(40);
        AudioDeviceInfo r = t.getRoutedDevice();
        log("greeting done preferred=" + pref + " routed=" + (r == null ? "null" : r.getType()));
        t.stop();
        t.release();
    }

    /** Short 1 kHz beep through the same path, after the greeting. */
    static void beep() throws Exception {
        int rate = 48000, n = rate * 6 / 10;
        byte[] pcm = new byte[44 + n * 2];
        for (int i = 0; i < n; i++) {
            double env = Math.min(1, Math.min(i, n - i) / 480.0);
            short s = (short) (Math.sin(2 * Math.PI * 1000 * i / rate) * 12000 * env);
            pcm[44 + 2 * i] = (byte) s;
            pcm[45 + 2 * i] = (byte) (s >> 8);
        }
        writeWavHeader(pcm, rate, n * 2);
        File f = new File(dataDir, ".beep.wav");
        try (FileOutputStream o = new FileOutputStream(f)) { o.write(pcm); }
        playGreeting(f);
    }

    static double[] record(File out, int maxSecs) throws Exception {
        int rate = 16000;
        int min = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
        AudioRecord rec = new AudioRecord.Builder()
                .setContext(new ShellContext())
                .setAudioSource(MediaRecorder.AudioSource.VOICE_DOWNLINK)
                .setAudioFormat(new AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(rate).setChannelMask(AudioFormat.CHANNEL_IN_MONO).build())
                .setBufferSizeInBytes(Math.max(min * 4, 32000))
                .build();
        rec.startRecording();
        log("recording state=" + rec.getRecordingState());
        byte[] buf = new byte[3200];
        long end = System.currentTimeMillis() + maxSecs * 1000L, bytes = 0, lastCheck = 0;
        double peak = 0;
        try (FileOutputStream o = new FileOutputStream(out)) {
            o.write(new byte[44]); // header placeholder
            while (System.currentTimeMillis() < end) {
                int n = rec.read(buf, 0, buf.length);
                if (n < 0) { log("read err " + n); break; }
                o.write(buf, 0, n);
                bytes += n;
                for (int i = 0; i + 1 < n; i += 2) peak = Math.max(peak, Math.abs((short) ((buf[i] & 0xff) | (buf[i + 1] << 8))));
                long now = System.currentTimeMillis();
                if (now - lastCheck > 400) {
                    lastCheck = now;
                    if (Boolean.FALSE.equals(call(telecom, "isInCall"))) { log("caller hung up"); break; }
                }
            }
        }
        rec.stop();
        rec.release();
        return new double[]{bytes / (2.0 * rate), 20 * Math.log10(Math.max(peak, 1) / 32768.0)};
    }

    /** Lets AudioRecord attribute the capture to com.android.shell (uid 2000). */
    static final class ShellContext extends ContextWrapper {
        ShellContext() { super(null); }
        @Override public String getPackageName() { return PKG_SHELL; }
        @Override public String getOpPackageName() { return PKG_SHELL; }
        @Override public String getAttributionTag() { return null; }
        @Override public AttributionSource getAttributionSource() {
            return new AttributionSource.Builder(android.os.Process.myUid()).setPackageName(PKG_SHELL).build();
        }
        @Override public int getDeviceId() { return 0; }
        @Override public android.content.Context getApplicationContext() { return this; }
    }

    // ------------------------------------------------------------------ config / status / app link

    static JSONObject readConfig() {
        try { return new JSONObject(new String(readAll(new File(dataDir, "config.json")), "UTF-8")); }
        catch (Throwable t) { return new JSONObject(); }
    }

    static boolean isSkipped(JSONObject cfg, String number) {
        if (number == null || number.isEmpty()) return false;
        JSONArray arr = cfg.optJSONArray("ringThrough");
        if (arr == null) return false;
        String n = RepeatCallers.digitsTail(number);
        Set<String> set = new HashSet<>();
        for (int i = 0; i < arr.length(); i++) set.add(RepeatCallers.digitsTail(arr.optString(i)));
        return set.contains(n);
    }

    /** Incoming number from the telephony registry (shell can read it). */
    static String incomingNumber() {
        try {
            Process p = Runtime.getRuntime().exec(new String[]{"dumpsys", "telephony.registry"});
            try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
                String line;
                while ((line = r.readLine()) != null) {
                    int i = line.indexOf("mCallIncomingNumber=");
                    if (i >= 0) {
                        String n = line.substring(i + 20).trim();
                        if (!n.isEmpty()) return n;
                    }
                }
            }
        } catch (Throwable t) { log("incomingNumber failed " + t); }
        return "";
    }

    static void notifyApp(String id) {
        try {
            Runtime.getRuntime().exec(new String[]{"am", "broadcast", "-a", "dev.afterbeep.NEW_MESSAGE",
                    // FLAG_RECEIVER_FOREGROUND | FLAG_INCLUDE_STOPPED_PACKAGES: deliver promptly even if the app was swiped away
                    "-f", "0x10000020",
                    "-n", APP_PKG + "/.engine.EngineEventsReceiver", "--es", "id", id}).waitFor();
        } catch (Throwable t) { log("notifyApp failed " + t); }
    }

    /**
     * We're launched by adbd, so we sit in adbd's process group (cgroup). When Wireless debugging turns off
     * (e.g. Wi-Fi off), init stops adbd and kills every process in that group, setsid/nohup or not. Move
     * ourselves into a group of our own first (same trick Shizuku uses).
     */
    static void escapeAdbdCgroup() {
        int pid = android.os.Process.myPid();
        int uid = android.os.Process.myUid();
        String before = readFirstLine("/proc/self/cgroup");
        java.util.List<String> tried = new java.util.ArrayList<>();
        // 1) a fresh leaf group next to adbd's: uid_2000/pid_<us>  2) the roots of the known hierarchies
        File leaf = new File("/sys/fs/cgroup/uid_" + uid + "/pid_" + pid);
        leaf.mkdirs();
        String[] targets = {
            leaf.getPath() + "/cgroup.procs",
            "/sys/fs/cgroup/cgroup.procs",
            "/acct/uid_" + uid + "/pid_" + pid + "/cgroup.procs",
            "/acct/cgroup.procs",
            "/dev/cg2_bpf/cgroup.procs",
        };
        for (String t : targets) {
            if (t.startsWith("/acct/uid_")) new File(t).getParentFile().mkdirs();
            try (java.io.FileWriter w = new java.io.FileWriter(t)) {
                w.write(Integer.toString(pid));
                tried.add(t + "=ok");
                break;
            } catch (Throwable e) {
                tried.add(t + "=" + e.getClass().getSimpleName());
            }
        }
        log("cgroup " + before + " -> " + readFirstLine("/proc/self/cgroup") + " " + tried);
    }

    static String readFirstLine(String path) {
        try (BufferedReader r = new BufferedReader(new java.io.FileReader(path))) {
            StringBuilder sb = new StringBuilder();
            String l; while ((l = r.readLine()) != null) sb.append(l).append(' ');
            return sb.toString().trim();
        } catch (Throwable t) { return "?"; }
    }

    // ------------------------------------------------------------------ debugging guard

    /**
     * Rejects "Allow USB debugging?" prompts that come over the network (e.g. someone on the same Wi-Fi hitting
     * adbd's TCP port) the instant they appear, so a mistaken tap can never let a stranger in. Prompts while a
     * computer is plugged in over USB are left alone (that's you). Shell holds MANAGE_DEBUGGING, the same
     * permission SystemUI's dialog uses to answer.
     */
    static void adbGuardLoop() {
        Object adbManager = null;
        try { adbManager = service("adb", "android.debug.IAdbManager$Stub"); } catch (Throwable t) { log("guard: no adb service " + t); }
        log("guard: watching for debugging prompts");
        applyPromptLock();
        while (true) {
            Process p = null;
            try {
                // -T 1: only lines from now on
                p = new ProcessBuilder("logcat", "-T", "1", "-v", "brief", "-s", "AdbDebuggingManager:D").redirectErrorStream(true).start();
                BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()));
                String line;
                while ((line = r.readLine()) != null) {
                    int i = line.indexOf("Received public key:");
                    if (i < 0) continue;
                    applyPromptLock(); // re-assert (setting may have changed)
                    String key = line.substring(i + 20).trim();
                    String fp = fingerprint(key);
                    // Locked (default): only keys already trusted, or added with a Wireless-debugging pairing code,
                    // ever get in — every "Allow debugging?" prompt is rejected, USB cable or not.
                    boolean locked = readConfig().optBoolean("lockDebugging", true);
                    if (!locked && usbHostConnected()) { log("guard: debugging prompt " + fp + " while a computer is plugged in by USB -> left for you to answer"); continue; }
                    boolean denied = false;
                    try { if (adbManager != null) { call(adbManager, "denyDebugging"); denied = true; } }
                    catch (Throwable t) { log("guard: denyDebugging failed " + t); }
                    // Close the dialog the instant it's on screen (checks every ~40 ms for up to 1.5 s).
                    long until = System.currentTimeMillis() + 1500;
                    boolean closed = false;
                    while (System.currentTimeMillis() < until && !closed) {
                        if (topActivity().contains("UsbDebuggingActivity")) {
                            Runtime.getRuntime().exec(new String[]{"input", "keyevent", "KEYCODE_BACK"}).waitFor();
                            closed = true;
                        } else Thread.sleep(40);
                    }
                    log("guard: BLOCKED debugging request " + fp + " (denied=" + denied + ", locked=" + locked + ")");
                    try {
                        Runtime.getRuntime().exec(new String[]{"am", "broadcast", "-a", "dev.afterbeep.ADB_BLOCKED",
                                "-f", "0x10000020", "-n", APP_PKG + "/.engine.EngineEventsReceiver", "--es", "fp", fp}).waitFor();
                    } catch (Throwable ignored) {}
                }
            } catch (Throwable t) {
                log("guard: " + t);
            } finally {
                if (p != null) p.destroy();
            }
            try { Thread.sleep(2000); } catch (InterruptedException e) { return; }
        }
    }

    static final String PROMPT_COMPONENT = "com.android.systemui/.usb.UsbDebuggingActivity";
    static Boolean promptDisabled = null;

    /**
     * Locked: switch off SystemUI's "Allow USB debugging?" screen entirely, so an unknown computer never gets a
     * prompt to accept (the request just times out). Unlocked: switch it back on. Reversible; the guard remains
     * as a second layer either way.
     */
    static void applyPromptLock() {
        boolean locked = readConfig().optBoolean("lockDebugging", true);
        if (promptDisabled != null && promptDisabled == locked) return;
        try {
            Process p = new ProcessBuilder("pm", locked ? "disable" : "enable", PROMPT_COMPONENT).redirectErrorStream(true).start();
            String out = new String(readAll(p.getInputStream())).trim();
            p.waitFor();
            if (out.contains("Exception")) log("guard: Android doesn't let shell " + (locked ? "disable" : "enable") + " the system prompt -> relying on instant rejection");
            else log("guard: system debugging prompt " + (locked ? "DISABLED" : "enabled") + " -> " + out);
            promptDisabled = locked; // don't retry every heartbeat
        } catch (Throwable t) { log("guard: couldn't change prompt: " + t); }
    }

    /** True only when a computer is actually connected as USB host (not just a charger). */
    static boolean usbHostConnected() {
        try {
            Process p = Runtime.getRuntime().exec(new String[]{"dumpsys", "usb"});
            BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()));
            String l;
            while ((l = r.readLine()) != null) {
                String t = l.trim();
                if (t.equals("configured=true")) { p.destroy(); return true; }
            }
            p.waitFor();
        } catch (Throwable ignored) {}
        return false;
    }

    static String topActivity() {
        try {
            Process p = Runtime.getRuntime().exec(new String[]{"sh", "-c", "dumpsys activity activities | grep -m1 topResumedActivity"});
            String out = new String(readAll(p.getInputStream()));
            p.waitFor();
            return out;
        } catch (Throwable t) { return ""; }
    }

    static byte[] readAll(InputStream in) throws java.io.IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        byte[] buf = new byte[4096]; int n;
        while ((n = in.read(buf)) > 0) b.write(buf, 0, n);
        return b.toByteArray();
    }

    static String fingerprint(String key) {
        try {
            String b64 = key.split(" ")[0];
            byte[] d = java.security.MessageDigest.getInstance("MD5").digest(android.util.Base64.decode(b64, 0));
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 6; i++) sb.append(String.format("%02X", d[i])).append(i < 5 ? ":" : "");
            String who = key.contains(" ") ? key.substring(key.indexOf(' ') + 1) : "";
            return sb + (who.isEmpty() ? "" : " (" + who + ")");
        } catch (Throwable t) { return "?"; }
    }

    static int beats = 0;
    static void heartbeatLoop() {
        while (true) {
            writeStatus(true);
            if (++beats % 4 == 0) applyPromptLock();
            try { Thread.sleep(3000); } catch (InterruptedException e) { return; }
        }
    }

    static void writeStatus(boolean running) {
        try {
            JSONObject s = new JSONObject();
            s.put("running", running);
            s.put("pid", android.os.Process.myPid());
            s.put("version", version);
            s.put("heartbeat", System.currentTimeMillis());
            s.put("state", state);
            writeAtomic(new File(dataDir, "status.json"), s.toString());
        } catch (Throwable ignored) {}
    }

    // ------------------------------------------------------------------ helpers

    static Object service(String name, String stub) throws Exception {
        IBinder b = (IBinder) Class.forName("android.os.ServiceManager").getMethod("getService", String.class).invoke(null, name);
        return Class.forName(stub).getMethod("asInterface", IBinder.class).invoke(null, b);
    }

    /** Binder call by name: ints/booleans from vals in order; first String = shell package; IBinder = mode token. */
    static Object call(Object svc, String name, Object... vals) {
        for (Method m : svc.getClass().getMethods()) {
            if (!m.getName().equals(name)) continue;
            Class<?>[] p = m.getParameterTypes();
            Object[] a = new Object[p.length];
            int vi = 0;
            boolean firstString = true;
            for (int i = 0; i < p.length; i++) {
                if (p[i] == int.class) a[i] = vi < vals.length && vals[vi] instanceof Integer ? vals[vi++] : 0;
                else if (p[i] == boolean.class) a[i] = vi < vals.length && vals[vi] instanceof Boolean ? vals[vi++] : false;
                else if (p[i] == String.class) { a[i] = firstString ? PKG_SHELL : null; firstString = false; }
                else if (IBinder.class.isAssignableFrom(p[i])) a[i] = MODE_TOKEN;
            }
            try { return m.invoke(svc, a); }
            catch (Throwable t) { return "err:" + (t.getCause() != null ? t.getCause() : t); }
        }
        return "n/a:" + name;
    }

    static Object setParam(String kv) {
        try { return Class.forName("android.media.AudioSystem").getMethod("setParameters", String.class).invoke(null, kv); }
        catch (Throwable t) { return "err " + t; }
    }

    static AudioDeviceInfo device(int flags, int type) {
        try {
            Method m = android.media.AudioManager.class.getDeclaredMethod("getDevicesStatic", int.class);
            m.setAccessible(true);
            for (AudioDeviceInfo d : (AudioDeviceInfo[]) m.invoke(null, flags)) if (d.getType() == type) return d;
        } catch (Throwable t) { log("getDevicesStatic failed " + t); }
        return null;
    }

    static void finishWav(File f, int rate) throws Exception {
        try (RandomAccessFile raf = new RandomAccessFile(f, "rw")) {
            int dataLen = (int) (raf.length() - 44);
            byte[] h = new byte[44];
            writeWavHeader(h, rate, dataLen);
            raf.seek(0);
            raf.write(h);
        }
    }

    static void writeWavHeader(byte[] h, int rate, int dataLen) {
        put(h, 0, "RIFF"); le32(h, 4, 36 + dataLen); put(h, 8, "WAVE"); put(h, 12, "fmt ");
        le32(h, 16, 16); le16(h, 20, 1); le16(h, 22, 1); le32(h, 24, rate); le32(h, 28, rate * 2);
        le16(h, 32, 2); le16(h, 34, 16); put(h, 36, "data"); le32(h, 40, dataLen);
    }

    static void put(byte[] b, int o, String s) { for (int i = 0; i < 4; i++) b[o + i] = (byte) s.charAt(i); }
    static void le16(byte[] b, int o, int v) { b[o] = (byte) v; b[o + 1] = (byte) (v >> 8); }
    static void le32(byte[] b, int o, int v) { le16(b, o, v); le16(b, o + 2, v >> 16); }
    static int le16(byte[] b, int o) { return (b[o] & 0xff) | (b[o + 1] & 0xff) << 8; }
    static int le32(byte[] b, int o) { return le16(b, o) | le16(b, o + 2) << 16; }

    static int findData(byte[] w) {
        for (int i = 12; i < w.length - 8; ) {
            int sz = le32(w, i + 4);
            if (new String(w, i, 4).equals("data")) return i + 8;
            i += 8 + sz + (sz & 1);
        }
        return 44;
    }

    static byte[] readAll(File f) throws Exception {
        try (InputStream in = new FileInputStream(f)) {
            ByteArrayOutputStream o = new ByteArrayOutputStream();
            byte[] b = new byte[65536];
            for (int n; (n = in.read(b)) > 0; ) o.write(b, 0, n);
            return o.toByteArray();
        }
    }

    static void writeAtomic(File f, String s) throws Exception {
        File tmp = new File(f.getPath() + ".tmp");
        try (FileOutputStream o = new FileOutputStream(tmp)) { o.write(s.getBytes("UTF-8")); }
        if (!tmp.renameTo(f)) { f.delete(); tmp.renameTo(f); }
    }

    static String mask(String n) { return n == null || n.length() < 4 ? "unknown" : "…" + n.substring(n.length() - 4); }

    static String stack(Throwable t) { return android.util.Log.getStackTraceString(t); }

    static synchronized void log(String s) {
        String line = new SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US).format(new Date()) + " " + s;
        System.out.println(line);
        try {
            if (logFile.length() > 1_000_000) logFile.renameTo(new File(dataDir, "engine.log.1"));
            try (FileWriter w = new FileWriter(logFile, true)) { w.write(line + "\n"); }
        } catch (Throwable ignored) {}
    }
}
