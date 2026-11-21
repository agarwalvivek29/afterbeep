package dev.afterbeep.engine;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class SilenceGateTest {
    static final int CHUNK = 3200; // 100 ms, same as Engine.record

    /** 100 ms of a 440 Hz tone at [amp], or near-silence (comfort-noise level) when amp is small. */
    static byte[] chunk(int amp) {
        byte[] b = new byte[CHUNK];
        for (int i = 0; i < CHUNK / 2; i++) {
            short s = (short) (Math.sin(2 * Math.PI * 440 * i / 16000.0) * amp);
            b[2 * i] = (byte) s;
            b[2 * i + 1] = (byte) (s >> 8);
        }
        return b;
    }

    static final byte[] SPEECH = chunk(8000);  // ~-15 dBFS
    static final byte[] QUIET = chunk(50);     // ~-59 dBFS

    /** Feeds [ms] of [audio]; returns the chunk index (from 1) the gate hung up on, or 0 if it never did. */
    static int feed(SilenceGate g, byte[] audio, int ms) {
        for (int i = 1; i <= ms / 100; i++) if (g.feed(audio, CHUNK) != null) return i;
        return 0;
    }

    static SilenceGate gate() { return new SilenceGate(-45, 10_000, 10_000); }

    @Test public void robocallHangsUpAfterNoSpeech() {
        SilenceGate g = gate();
        int at = feed(g, QUIET, 30_000);
        assertEquals("hangs up just past 10 s", 101, at);
        assertFalse(g.spoke());
    }

    @Test public void hangsUpTenSecondsAfterCallerStops() {
        SilenceGate g = gate();
        assertEquals(0, feed(g, SPEECH, 3_000));
        assertTrue(g.spoke());
        assertEquals(101, feed(g, QUIET, 30_000));
    }

    @Test public void pauseShorterThanLimitKeepsRecording() {
        SilenceGate g = gate();
        feed(g, SPEECH, 2_000);
        assertEquals(0, feed(g, QUIET, 9_000));
        assertEquals(0, feed(g, SPEECH, 2_000));
    }

    @Test public void singleClickIsNotSpeech() {
        SilenceGate g = gate();
        feed(g, QUIET, 1_000);
        assertNull(g.feed(SPEECH, CHUNK));
        feed(g, QUIET, 1_000);
        assertFalse(g.spoke());
    }

    @Test public void trimsTrailingSilenceKeepingHalfSecond() {
        SilenceGate g = gate();
        feed(g, SPEECH, 3_000);
        feed(g, QUIET, 30_000);
        assertEquals(3_000 * SilenceGate.BYTES_PER_MS + SilenceGate.TAIL_KEEP_BYTES, g.keepBytes());
    }

    @Test public void continuousSpeechNeverHangsUp() {
        // The message length limit is the engine's job, not the gate's.
        assertEquals(0, feed(gate(), SPEECH, 300_000));
    }

    @Test public void speechNeedsThreeLoudChunksInARow() {
        SilenceGate g = gate();
        feed(g, SPEECH, 200);
        feed(g, QUIET, 100);
        feed(g, SPEECH, 200);
        feed(g, QUIET, 100);
        assertFalse(g.spoke());
        feed(g, SPEECH, 300);
        assertTrue(g.spoke());
    }

    @Test public void callerWhoStartsAt9800msIsNotCutOff() {
        SilenceGate g = gate();
        assertEquals(0, feed(g, QUIET, 9_800));
        assertEquals(0, feed(g, SPEECH, 300));
        assertTrue(g.spoke());
    }

    @Test public void realisticMessageWithPause() {
        // Breathing, speech, a 6 s pause to think, more speech, then the caller stays on the line.
        SilenceGate g = gate();
        assertEquals(0, feed(g, QUIET, 2_000));
        assertEquals(0, feed(g, SPEECH, 4_000));
        assertEquals(0, feed(g, QUIET, 6_000));
        assertEquals(0, feed(g, SPEECH, 3_000));
        assertEquals(101, feed(g, QUIET, 30_000));
        assertEquals(15_000 * SilenceGate.BYTES_PER_MS + SilenceGate.TAIL_KEEP_BYTES, g.keepBytes());
    }

    @Test public void shortReadsAreTimedByAudioLengthNotChunkCount() {
        // AudioRecord.read can return less than the buffer. 50 ms chunks: 10 s of quiet is 200 chunks.
        SilenceGate g = gate();
        byte[] half = new byte[CHUNK / 2];
        int at = 0;
        for (int i = 1; i <= 400 && at == 0; i++) if (g.feed(half, half.length) != null) at = i;
        assertEquals(201, at);
    }

    @Test public void oddAndEmptyReadsAreSafe() {
        SilenceGate g = gate();
        assertNull(g.feed(new byte[CHUNK + 1], CHUNK + 1));
        assertNull(g.feed(new byte[CHUNK], 0));
        assertFalse(g.spoke());
    }

    @Test public void thresholdIsConfigurable() {
        byte[] medium = chunk(800); // ~-35 dBFS
        SilenceGate lenient = new SilenceGate(-45, 10_000, 10_000);
        SilenceGate strict = new SilenceGate(-30, 10_000, 10_000);
        feed(lenient, medium, 300);
        feed(strict, medium, 300);
        assertTrue(lenient.spoke());
        assertFalse(strict.spoke());
    }

    @Test public void timingsAreConfigurable() {
        assertEquals(51, feed(new SilenceGate(-45, 10_000, 5_000), QUIET, 30_000));
    }

    @Test public void floorReportsTheQuietestChunk() {
        SilenceGate g = gate();
        feed(g, SPEECH, 1_000);
        feed(g, QUIET, 500);
        assertEquals(-59.3, g.floorDb(), 1.0);
    }

    @Test public void trimNeverExceedsWhatWasRecorded() {
        // Caller hangs up right after speaking: there is less than 0.5 s after the last sound.
        SilenceGate g = gate();
        feed(g, SPEECH, 3_000);
        assertEquals(3_000 * SilenceGate.BYTES_PER_MS, g.keepBytes());
        feed(g, QUIET, 200);
        assertEquals(3_200 * SilenceGate.BYTES_PER_MS, g.keepBytes());
    }

    @Test public void hangUpReasonIsReadableInTheLog() {
        SilenceGate g = gate();
        String reason = null;
        for (int i = 0; i < 200 && reason == null; i++) reason = g.feed(QUIET, CHUNK);
        assertEquals("no speech in 10s", reason);
    }
}
