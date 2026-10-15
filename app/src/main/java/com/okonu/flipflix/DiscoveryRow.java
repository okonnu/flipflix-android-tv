package com.okonu.flipflix;

import java.util.ArrayList;
import java.util.List;

final class DiscoveryRow {
    final String title, section, path, kind;
    final boolean featured;
    final List<DiscoveryItem> items = new ArrayList<>();
    int page = 1, lastIndex;
    boolean loading, complete, failed;
    DiscoveryRow(String title, String section, String path, String kind, boolean featured) {
        this.title = title; this.section = section; this.path = path; this.kind = kind; this.featured = featured;
    }
}
