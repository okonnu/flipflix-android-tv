package com.okonu.flipflix;

import org.junit.Test;
import static org.junit.Assert.*;

public final class SeekAcceleratorTest {
    @Test public void heldOrRepeatedInputAcceleratesButNeverFloods() {
        SeekAccelerator seek = new SeekAccelerator();
        assertEquals(110000, seek.seek(1, 0, 100000, 3600000));
        assertEquals(-1, seek.seek(1, 50, 100000, 3600000));
        assertEquals(120000, seek.seek(1, 200, 100000, 3600000));
        for (int t = 400; t <= 1000; t += 200) seek.seek(1, t, 100000, 3600000);
        assertEquals(30000, seek.stepMillis);
        for (int t = 1200; t <= 2600; t += 200) seek.seek(1, t, 100000, 3600000);
        assertEquals(60000, seek.stepMillis);
        for (int t = 2800; t <= 5000; t += 200) seek.seek(1, t, 100000, 3600000);
        assertEquals(120000, seek.stepMillis);
        assertTrue("Accumulate despite stale provider position", seek.target() > 1000000);
    }
    @Test public void reversingAndRestingReturnToFineStepsAndBounds() {
        SeekAccelerator seek = new SeekAccelerator();
        for (int t = 0; t <= 1400; t += 200) seek.seek(1, t, 100000, 3600000);
        long before = seek.target();
        assertEquals(before - 10000, seek.seek(-1, 1450, 100000, 3600000));
        assertEquals(10000, seek.stepMillis);
        seek.reset(); // New playback/timeline touch establishes a new position.
        assertEquals(40000, seek.seek(1, 2500, 30000, 3600000));
        assertEquals(10000, seek.stepMillis);
        seek.reset(); assertEquals(0, seek.seek(-1, 3000, 1000, 50000));
        seek.reset(); assertEquals(50000, seek.seek(1, 3200, 49000, 50000));
        assertEquals(50000, seek.displayPosition(4200, 50000));
    }

    @Test public void idleSlowsStepsWithoutJumpingBackToDelayedProgress() {
        SeekAccelerator seek = new SeekAccelerator();
        assertEquals(110000, seek.seek(1, 0, 100000, 3600000));
        assertEquals(110000, seek.displayPosition(1200,100000));
        assertEquals(120000, seek.seek(1, 1300, 100000, 3600000));
        assertEquals(10000, seek.stepMillis);
        assertEquals(121000, seek.displayPosition(2400,121000));
        assertEquals(131000, seek.seek(1,2500,121000,3600000));
        // A refused seek must eventually yield to the provider, not stick forever.
        assertEquals(121000, seek.displayPosition(9000,121000));
    }
}
