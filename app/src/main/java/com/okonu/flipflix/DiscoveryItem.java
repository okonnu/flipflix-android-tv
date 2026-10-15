package com.okonu.flipflix;

import org.json.JSONObject;

/** Presentation data shared by cards, hero, library, and ambient playback. */
final class DiscoveryItem {
    String key, title, category, thumbnail, heroImage, previewUrl = "", watchUrl = "";
    String description = "", metadata = "", attribution = "", logoUrl = "";
    String ageRating = "", episodeInfo = "", broadcastTime = "";
    boolean live;
    float progress = -1;
    long previewStartMs;
    JSONObject catalog;

    JSONObject json() {
        JSONObject o = new JSONObject();
        try {
            o.put("key", key).put("title", title).put("category", category).put("thumbnail", thumbnail)
                .put("heroImage", heroImage).put("previewUrl", previewUrl).put("watchUrl", watchUrl)
                .put("description", description).put("metadata", metadata).put("attribution", attribution)
                .put("logoUrl", logoUrl).put("live", live).put("progress", progress).put("ageRating", ageRating)
                .put("episodeInfo", episodeInfo).put("broadcastTime", broadcastTime).put("previewStartMs", previewStartMs)
                .put("catalog", catalog);
        } catch (org.json.JSONException impossible) { throw new IllegalStateException(impossible); }
        return o;
    }
    static DiscoveryItem from(JSONObject o) {
        DiscoveryItem i = new DiscoveryItem();
        i.key = o.optString("key"); i.title = o.optString("title"); i.category = o.optString("category");
        i.thumbnail = o.optString("thumbnail"); i.heroImage = o.optString("heroImage");
        i.previewUrl = o.optString("previewUrl"); i.watchUrl = o.optString("watchUrl");
        i.description = o.optString("description"); i.metadata = o.optString("metadata");
        i.attribution = o.optString("attribution"); i.logoUrl = o.optString("logoUrl");
        i.live = o.optBoolean("live"); i.progress = (float) o.optDouble("progress", -1);
        i.ageRating = o.optString("ageRating"); i.episodeInfo = o.optString("episodeInfo");
        i.broadcastTime = o.optString("broadcastTime"); i.previewStartMs = o.optLong("previewStartMs");
        i.catalog = o.optJSONObject("catalog");
        return i;
    }
}
