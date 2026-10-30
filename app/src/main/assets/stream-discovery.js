/* Read only the active provider's normal video/resource state. No request patching,
   payload decryption, polling, or permissions bridge. Removed on source change. */
(function () {
    if (!window.DarkflixStream) return;
    var sent = false, encrypted = false, stopped = false, lastAttempt = 0, loadCount = 0, resourceStart = 0;
    var owners = new WeakMap(), hooks = [];
    function https(url) { return typeof url === 'string' && /^https:\/\//i.test(url); }
    function observeHls() {
        var proto = window.Hls && window.Hls.prototype;
        if (stopped || !proto || typeof proto.attachMedia !== 'function') return;
        for (var i = 0; i < hooks.length; i++) if (hooks[i].proto === proto) return;
        // The instance is often private to the provider's closure. Observe its
        // public media attachment, preserving every argument and return value.
        // A preload/custom loader can supply the master without a network entry;
        // resource timing then contains only video children and loses audio.
        var original = proto.attachMedia;
        function attached() {
            var result = original.apply(this, arguments);
            if (!stopped && this.media) owners.set(this.media, this);
            return result;
        }
        proto.attachMedia = attached;
        hooks.push({proto:proto, original:original, attached:attached});
    }
    // Script load is captured before the following provider script constructs
    // its player. This also handles deferred/dynamically loaded HLS libraries.
    document.addEventListener('load', observeHls, true);
    observeHls();
    function discover(event) {
        var video = event && event.target;
        if (!video || video.tagName !== 'VIDEO') return;
        if (stopped) { video.pause(); return; }
        if (sent || encrypted || video.mediaKeys || video.readyState < 1 || !isFinite(video.duration) || video.duration < 60) return;
        if (event.type === 'timeupdate' && Date.now() - lastAttempt < 2000) return;
        lastAttempt = Date.now();
        var owner = owners.get(video);
        if (!owner && window.hls && window.hls.media === video) owner = window.hls;
        // A detached/replaced instance must not supply another video's URL.
        if (owner && owner.media !== video) owner = null;
        var url = owner ? owner.url : video.currentSrc;
        // MediaSource blob URLs are browser-local. HLS.js may expose the actual
        // manifest. Never replace a known HLS input with a guessed child URL.
        if (!https(url) && (owner || window.Hls)) return;
        if (!https(url) && window.performance && performance.getEntriesByType) {
            var resources = performance.getEntriesByType('resource'), manifests = [], host = '';
            for (var i = 0; i < resources.length; i++) {
                var candidate = resources[i].name;
                if (resources[i].startTime < resourceStart) continue;
                if (!https(candidate) || !/\.(m3u8|mpd)(\?|$)/i.test(candidate)) continue;
                var a = document.createElement('a'); a.href = candidate;
                // Multiple unrelated origins are ambiguous. Decline the candidate.
                if (host && host !== a.host) return;
                host = a.host;
                if (manifests.indexOf(candidate) < 0) manifests.push(candidate);
            }
            if (manifests.length) url = manifests[0];
        }
        if (!https(url)) return;
        var captions = [], tracks = video.querySelectorAll('track[src]');
        for (var j = 0; j < tracks.length && j < 100; j++) {
            var t = tracks[j];
            if (https(t.src)) captions.push({url:t.src, lang:t.srclang, label:t.label, code:t.srclang});
        }
        sent = true;
        DarkflixStream.postMessage(JSON.stringify({session:'__SESSION__', url:url, page:location.href,
            duration:Math.round(video.duration * 1000), captions:captions, encrypted:false}));
    }
    DarkflixStream.onmessage = function (event) {
        if (event.data !== 'stop') return;
        stopped = true;
        document.removeEventListener('load', observeHls, true);
        for (var h = 0; h < hooks.length; h++) {
            var hook = hooks[h];
            if (hook.proto.attachMedia === hook.attached) hook.proto.attachMedia = hook.original;
        }
        hooks = []; owners = new WeakMap();
        var videos = document.querySelectorAll('video');
        for (var i = 0; i < videos.length; i++) videos[i].pause();
    };
    document.addEventListener('encrypted', function () { encrypted = true; }, true);
    document.addEventListener('loadstart', function (event) {
        if (!event.target || event.target.tagName !== 'VIDEO') return;
        // After an ad or a media replacement, don't reuse the previous video's
        // manifest. Allow a just-requested manifest to precede MSE attachment.
        if (loadCount++ && window.performance && performance.now) resourceStart = Math.max(0, performance.now() - 1000);
    }, true);
    ['loadedmetadata', 'durationchange', 'playing', 'timeupdate'].forEach(function (event) {
        document.addEventListener(event, discover, true);
    });
}());
