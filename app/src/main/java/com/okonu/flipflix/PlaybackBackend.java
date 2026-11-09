package com.okonu.flipflix;

import java.util.ArrayList;
import java.util.List;

/** Playback strategy shared by engines and provider adapters. Commands run on the
 * main thread; option IDs are opaque and valid only for the current adapter/session.
 * Only advertise tracks/settings that the implementation can actually select. */
interface PlaybackBackend {
    final class Option {
        final String id, label;
        final boolean selected;
        Option(String id, String label, boolean selected) {
            this.id = id; this.label = label; this.selected = selected;
        }
    }
    final class State {
        boolean available, canStart, seekable, paused = true, muted, canResize, fillScreen;
        long position, duration;
        final List<Option> subtitles = new ArrayList<>();
        final List<Option> qualities = new ArrayList<>();
        // Optional provider-owned choices, refreshed atomically with each state event.
        final java.util.Map<String, List<Option>> settings = new java.util.LinkedHashMap<>();
        boolean providerControls, ended;
        // Explain engine limitations separately from missing source tracks.
        String qualityMessage = "";
        Object optionsOwner;
    }
    State state();
    void command(String name, String value);
    default void refresh() { }
}
