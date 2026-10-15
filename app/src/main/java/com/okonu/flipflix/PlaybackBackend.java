package com.okonu.flipflix;

import java.util.ArrayList;
import java.util.List;

/** The same TV controls operate native streams and cooperating HTML video frames. */
interface PlaybackBackend {
    final class Option {
        final String id, label;
        final boolean selected;
        Option(String id, String label, boolean selected) {
            this.id = id; this.label = label; this.selected = selected;
        }
    }
    final class State {
        boolean available, seekable, paused = true, muted, canResize, fillScreen;
        long position, duration;
        final List<Option> subtitles = new ArrayList<>();
        final List<Option> qualities = new ArrayList<>();
    }
    State state();
    void command(String name, String value);
    default void refresh() { }
}
