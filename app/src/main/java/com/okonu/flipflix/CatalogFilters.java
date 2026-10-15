package com.okonu.flipflix;

import android.content.SharedPreferences;
import android.net.Uri;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/** Discovery filters are sent to the catalog, not applied to one loaded page. */
final class CatalogFilters {
    static final int GENRE = 0, YEAR = 1, RATING = 2, COUNTRY = 3, SORT = 4;
    static final String[] TITLES = {"Genre", "Year", "Minimum rating", "Country", "Sort by"};
    private final String kind;
    private final String[] values = {"", "", "", "", "popular"};
    private final List<List<Option>> choices = new ArrayList<>();

    static final class Option {
        final String value, label;
        Option(String value, String label) { this.value = value; this.label = label; }
    }

    CatalogFilters(String kind) {
        if (!kind.equals("movie") && !kind.equals("tv")) throw new IllegalArgumentException("Unsupported catalog");
        this.kind = kind;
        List<Option> genres = new ArrayList<>();
        genres.add(new Option("", "All genres"));
        String[] names = kind.equals("movie")
            ? new String[]{"Action", "Adventure", "Animation", "Comedy", "Crime", "Documentary", "Drama", "Family", "Fantasy", "History", "Horror", "Music", "Mystery", "Romance", "Sci-Fi", "Thriller", "War", "Western"}
            : new String[]{"Action & adventure", "Animation", "Comedy", "Crime", "Documentary", "Drama", "Family", "Kids", "Mystery", "News", "Reality", "Sci-Fi & fantasy", "Soap", "Talk", "War & politics", "Western"};
        int[] ids = kind.equals("movie")
            ? new int[]{28, 12, 16, 35, 80, 99, 18, 10751, 14, 36, 27, 10402, 9648, 10749, 878, 53, 10752, 37}
            : new int[]{10759, 16, 35, 80, 99, 18, 10751, 10762, 9648, 10763, 10764, 10765, 10766, 10767, 10768, 37};
        for (int i = 0; i < ids.length; i++) genres.add(new Option(String.valueOf(ids[i]), names[i]));
        choices.add(genres);
        List<Option> years = new ArrayList<>();
        years.add(new Option("", "All years"));
        for (int year = Calendar.getInstance().get(Calendar.YEAR); year >= 1900; year--) {
            years.add(new Option(String.valueOf(year), String.valueOf(year)));
        }
        choices.add(years);
        List<Option> ratings = new ArrayList<>();
        ratings.add(new Option("", "Any rating"));
        for (int rating = 1; rating <= 9; rating++) ratings.add(new Option(String.valueOf(rating), rating + "+ / 10"));
        choices.add(ratings);
        List<Option> countries = new ArrayList<>();
        for (String code : Locale.getISOCountries()) countries.add(new Option(code, new Locale("", code).getDisplayCountry(Locale.US)));
        Collections.sort(countries, (a, b) -> a.label.compareToIgnoreCase(b.label));
        countries.add(0, new Option("", "All countries"));
        choices.add(countries);
        List<Option> sorting = new ArrayList<>();
        sorting.add(new Option("popular", "Most popular"));
        sorting.add(new Option("rated", "Highest rated"));
        sorting.add(new Option("newest", "Newest first"));
        sorting.add(new Option("oldest", "Oldest first"));
        choices.add(sorting);
    }

    List<Option> options(int field) { return Collections.unmodifiableList(choices.get(field)); }
    String value(int field) { return values[field]; }
    String label(int field) { return choices.get(field).get(selectedIndex(field)).label; }
    int selectedIndex(int field) {
        List<Option> options = choices.get(field);
        for (int i = 0; i < options.size(); i++) if (options.get(i).value.equals(values[field])) return i;
        return 0;
    }
    boolean select(int field, String value) {
        for (Option option : choices.get(field)) {
            if (!option.value.equals(value) || values[field].equals(value)) continue;
            values[field] = value;
            return true;
        }
        return false;
    }
    void reset() { for (int i = 0; i < values.length; i++) values[i] = i == SORT ? "popular" : ""; }
    void restore(SharedPreferences preferences) {
        for (int i = 0; i < values.length; i++) select(i, preferences.getString(kind + "." + i, values[i]));
    }
    void save(SharedPreferences preferences) {
        SharedPreferences.Editor editor = preferences.edit();
        for (int i = 0; i < values.length; i++) editor.putString(kind + "." + i, values[i]);
        editor.apply();
    }
    String pathPrefix() {
        boolean movie = kind.equals("movie");
        String sort = "popularity.desc";
        if (values[SORT].equals("rated")) sort = "vote_average.desc";
        else if (values[SORT].equals("newest") || values[SORT].equals("oldest")) {
            sort = (movie ? "primary_release_date" : "first_air_date") + (values[SORT].equals("newest") ? ".desc" : ".asc");
        }
        StringBuilder path = new StringBuilder("/api/tmdb/discover/").append(kind)
            .append("?sort_by=").append(sort).append("&include_adult=false&vote_count.gte=80");
        if (movie) path.append("&include_video=false");
        append(path, "with_genres", values[GENRE]);
        append(path, movie ? "primary_release_year" : "first_air_date_year", values[YEAR]);
        append(path, "vote_average.gte", values[RATING]);
        append(path, "with_origin_country", values[COUNTRY]);
        return path.append("&page=").toString();
    }
    private static void append(StringBuilder path, String key, String value) {
        if (!value.isEmpty()) path.append('&').append(key).append('=').append(Uri.encode(value));
    }
}
