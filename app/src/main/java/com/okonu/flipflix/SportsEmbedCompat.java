package com.okonu.flipflix;

import android.net.Uri;
import android.webkit.CookieManager;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebView;
import androidx.webkit.WebViewCompat;
import androidx.webkit.WebViewFeature;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** A one-page compatibility fix, not a general request filter or DOM scanner. */
final class SportsEmbedCompat {
    private static final int MAX_HTML_BYTES = 2 * 1024 * 1024;
    private static final Pattern FIRST_SCRIPT = Pattern.compile("<script\\b", Pattern.CASE_INSENSITIVE);
    private final String script;
    private final String userAgent;
    private final boolean documentStart;

    SportsEmbedCompat(WebView view) {
        userAgent = view.getSettings().getUserAgentString();
        String source = "";
        try (InputStream input = view.getContext().getAssets().open("sports-compat.js")) {
            source = new String(readBounded(input), StandardCharsets.UTF_8);
        } catch (Exception ignored) { }
        script = source;
        documentStart = WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT);
        if (documentStart && !script.isEmpty()) {
            WebViewCompat.addDocumentStartJavaScript(view, script, Collections.singleton("https://embed.st"));
        }
    }

    static boolean isSportsPage(Uri uri) {
        return "https".equals(uri.getScheme()) && "embed.st".equals(uri.getHost()) &&
            (uri.getPort() == -1 || uri.getPort() == 443) && uri.getUserInfo() == null &&
            uri.getPath() != null && uri.getPath().startsWith("/embed/");
    }

    // Old Fire OS WebViews cannot install a document-start script. For this
    // top-level HTML only, insert it before the provider's first script. Media,
    // scripts, child frames and every other provider retain WebView networking.
    WebResourceResponse intercept(WebResourceRequest request) {
        if (documentStart || script.isEmpty() || !request.isForMainFrame() ||
            !"GET".equals(request.getMethod()) || !isSportsPage(request.getUrl())) return null;
        HttpURLConnection connection = null;
        try {
            String url = request.getUrl().toString();
            connection = (HttpURLConnection) new URL(url).openConnection();
            connection.setConnectTimeout(7000);
            connection.setReadTimeout(12000);
            connection.setInstanceFollowRedirects(false);
            connection.setRequestProperty("User-Agent", userAgent);
            connection.setRequestProperty("Accept-Encoding", "identity");
            for (Map.Entry<String, String> header : request.getRequestHeaders().entrySet()) {
                String key = header.getKey();
                if (key.equalsIgnoreCase("Accept") || key.equalsIgnoreCase("Accept-Language") ||
                    key.equalsIgnoreCase("Referer")) connection.setRequestProperty(key, header.getValue());
            }
            String cookies = CookieManager.getInstance().getCookie(url);
            if (cookies != null) connection.setRequestProperty("Cookie", cookies);
            String type = connection.getContentType();
            if (connection.getResponseCode() != 200 || type == null ||
                !type.toLowerCase(java.util.Locale.ROOT).startsWith("text/html")) return null;
            byte[] original;
            try (InputStream input = connection.getInputStream()) { original = readBounded(input); }
            // Provider documents are UTF-8. Do not reinterpret other encodings.
            String lowerType = type.toLowerCase(java.util.Locale.ROOT);
            if (lowerType.contains("charset=") && !lowerType.matches(".*charset=[\"']?utf-8[\"']?\\s*;?\\s*")) return null;
            String html = inject(new String(original, StandardCharsets.UTF_8), script);
            Map<String, String> headers = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
            for (Map.Entry<String, List<String>> header : connection.getHeaderFields().entrySet()) {
                String key = header.getKey();
                if (key == null || header.getValue() == null) continue;
                if (key.equalsIgnoreCase("Set-Cookie")) {
                    for (String cookie : header.getValue()) CookieManager.getInstance().setCookie(url, cookie);
                } else if (!key.equalsIgnoreCase("Content-Length") && !key.equalsIgnoreCase("Content-Encoding") &&
                    !key.equalsIgnoreCase("Transfer-Encoding")) {
                    headers.put(key, android.text.TextUtils.join(", ", header.getValue()));
                }
            }
            // In particular, retain the provider's CSP, referrer and CORS headers.
            return new WebResourceResponse("text/html", "UTF-8", 200, "OK", headers,
                new ByteArrayInputStream(html.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception ignored) {
            return null; // Let WebView handle network failures normally.
        } finally { if (connection != null) connection.disconnect(); }
    }

    static String inject(String html, String script) {
        Matcher start = FIRST_SCRIPT.matcher(html);
        if (!start.find()) return html;
        return html.substring(0, start.start()) + "<script>" + script + "</script>" + html.substring(start.start());
    }

    private static byte[] readBounded(InputStream input) throws java.io.IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192]; int count;
        while ((count = input.read(buffer)) != -1) {
            if (bytes.size() + count > MAX_HTML_BYTES) throw new java.io.IOException("HTML too large");
            bytes.write(buffer, 0, count);
        }
        return bytes.toByteArray();
    }
}
