package dev.afterbeep.engine;

/**
 * Live silence detection on the caller's audio (16 kHz mono 16-bit PCM), fed chunk by chunk as it's recorded.
 * Ends the message when the caller never speaks (robocall, pocket dial) or has gone quiet after speaking.
 * Pure Java, no Android, so it can be unit-tested on a plain JVM.
 *
 * ponytail: fixed dBFS threshold. Phone networks fill silence with comfort noise whose level varies by carrier;
 * tune "silenceDb" in config.json from the floor= value in engine.log, or switch to a per-call adaptive floor if
 * background noise keeps messages open.
 */
final class SilenceGate {
    static final int BYTES_PER_MS = 32;      // 16000 samples/s * 2 bytes
    static final int SPEECH_CHUNKS = 3;      // ~300 ms of sustained sound before it counts as speech (not a click)
    static final int TAIL_KEEP_BYTES = 16000; // keep 0.5 s after the last sound so the end doesn't sound clipped

    private final double thresholdDb;
    private final long silenceMs, noSpeechMs;
    private long bytes, lastLoud;
    private int loudRun;
    private boolean spoke;
    private double floorDb = 0;

    SilenceGate(double thresholdDb, long silenceMs, long noSpeechMs) {
        this.thresholdDb = thresholdDb;
        this.silenceMs = silenceMs;
        this.noSpeechMs = noSpeechMs;
    }

    /** Feeds one chunk; returns why to hang up now, or null to keep recording. */
    String feed(byte[] buf, int n) {
        double sum = 0;
        for (int i = 0; i + 1 < n; i += 2) {
            int s = (short) ((buf[i] & 0xff) | (buf[i + 1] << 8));
            sum += (double) s * s;
        }
        bytes += n;
        double db = 20 * Math.log10(Math.sqrt(sum / Math.max(1, n / 2)) / 32768.0 + 1e-9);
        floorDb = Math.min(floorDb, db);
        if (db > thresholdDb) {
            lastLoud = bytes;
            if (++loudRun >= SPEECH_CHUNKS) spoke = true;
        } else loudRun = 0;
        if (!spoke && bytes / BYTES_PER_MS > noSpeechMs) return "no speech in " + noSpeechMs / 1000 + "s";
        if (spoke && (bytes - lastLoud) / BYTES_PER_MS > silenceMs) return "silent for " + silenceMs / 1000 + "s";
        return null;
    }

    boolean spoke() { return spoke; }

    double floorDb() { return floorDb; }

    /** Audio bytes worth keeping: everything up to the last sound plus a short tail. */
    long keepBytes() { return spoke ? Math.min(bytes, lastLoud + TAIL_KEEP_BYTES) : bytes; }
}
