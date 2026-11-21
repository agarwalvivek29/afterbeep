package dev.afterbeep.engine;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class RepeatCallersTest {
    static final long T0 = 1_000_000;

    @Test public void firstCallIsNotARepeat() {
        assertFalse(new RepeatCallers().isRecent("+919876543210", T0));
    }

    @Test public void secondCallWithinThreeMinutesRingsThrough() {
        RepeatCallers r = new RepeatCallers();
        r.remember("+919876543210", T0);
        assertTrue(r.isRecent("+919876543210", T0 + 2 * 60_000));
        assertTrue(r.isRecent("+919876543210", T0 + RepeatCallers.WINDOW_MS));
    }

    @Test public void callAfterWindowGoesToVoicemail() {
        RepeatCallers r = new RepeatCallers();
        r.remember("+919876543210", T0);
        assertFalse(r.isRecent("+919876543210", T0 + RepeatCallers.WINDOW_MS + 1));
    }

    @Test public void otherNumbersDontCount() {
        RepeatCallers r = new RepeatCallers();
        r.remember("+919876543210", T0);
        assertFalse(r.isRecent("+919999999999", T0 + 1000));
    }

    @Test public void hiddenNumbersNeverCount() {
        RepeatCallers r = new RepeatCallers();
        r.remember("", T0);
        r.remember(null, T0);
        assertFalse(r.isRecent("", T0 + 1000));
        assertFalse(r.isRecent(null, T0 + 1000));
    }

    @Test public void differentFormatsOfTheSameNumberMatch() {
        RepeatCallers r = new RepeatCallers();
        r.remember("+91 98765 43210", T0);
        assertTrue(r.isRecent("098765-43210", T0 + 1000));
        assertEquals("9876543210", RepeatCallers.digitsTail("+91 (98765) 43210"));
    }

    @Test public void windowRestartsAfterEachCall() {
        // Call 1 ends at T0. Call 2 rings through at T0+2m and ends at T0+2m30s.
        // Call 3 at T0+5m is 5 minutes after call 1, but only 2.5 minutes after call 2, so it also rings.
        RepeatCallers r = new RepeatCallers();
        r.remember("+919876543210", T0);
        assertTrue(r.isRecent("+919876543210", T0 + 120_000));
        r.remember("+919876543210", T0 + 150_000);
        assertTrue(r.isRecent("+919876543210", T0 + 300_000));
    }

    @Test public void eachNumberHasItsOwnWindow() {
        RepeatCallers r = new RepeatCallers();
        r.remember("+919876543210", T0);
        r.remember("+919999999999", T0 + 120_000);
        assertFalse(r.isRecent("+919876543210", T0 + 200_000));
        assertTrue(r.isRecent("+919999999999", T0 + 200_000));
    }

    @Test public void oldEntriesExpireEvenWhenOtherNumbersCall() {
        RepeatCallers r = new RepeatCallers();
        r.remember("+919876543210", T0);
        r.remember("+919999999999", T0 + RepeatCallers.WINDOW_MS + 1); // prunes the first number
        assertFalse(r.isRecent("+919876543210", T0 + RepeatCallers.WINDOW_MS + 2));
    }

    @Test public void shortServiceNumbersMatchExactly() {
        RepeatCallers r = new RepeatCallers();
        r.remember("12345", T0);
        assertTrue(r.isRecent("12345", T0 + 1000));
        assertFalse(r.isRecent("123456", T0 + 1000));
    }

    @Test public void lettersAndSymbolsOnlyCountAsHidden() {
        RepeatCallers r = new RepeatCallers();
        r.remember("Private", T0);
        assertFalse(r.isRecent("Unknown", T0 + 1000));
        assertEquals("", RepeatCallers.digitsTail("Private"));
    }

    @Test public void knownLimitationSameTenDigitsInDifferentCountriesMatch() {
        // Matching uses the last 10 digits (same as the favourites check), so these count as one caller.
        RepeatCallers r = new RepeatCallers();
        r.remember("+1 415 555 0100", T0);
        assertTrue(r.isRecent("+44 415 555 0100", T0 + 1000));
    }
}
