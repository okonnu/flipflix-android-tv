package com.okonu.flipflix;

/** Stable history IDs: the original 0..2 providers now explicitly mean Native. */
final class PlaybackChoice {
    static final int COUNT = 6;
    private static final String[] PROVIDERS = {"Bolt", "Rift", "Fast"};
    private PlaybackChoice() { }
    static int normalize(int id) { return id >= 0 && id < COUNT ? id : 0; }
    static int provider(int id) { return normalize(id) % 3; }
    static boolean nativeMode(int id) { return normalize(id) < 3; }
    static String label(int id) { return PROVIDERS[provider(id)] + (nativeMode(id) ? " - Native" : " - Web"); }
    static String[] labels() {
        String[] labels = new String[COUNT];
        for (int i = 0; i < COUNT; i++) labels[i] = label(i);
        return labels;
    }
    static int[] displayOrder() { return new int[]{0, 3, 1, 4, 2, 5}; }
}
