/* Runs only in permitted provider frames. No DOM polling or layout mutation. */
(function () {
    if (window.__flipflixPlayback || !window.FlipflixPlayback) return;
    window.__flipflixPlayback = true;
    var video = null, lastReport = 0;
    function findVideo() {
        if (video && video.isConnected) return video;
        var candidates = document.querySelectorAll('video');
        for (var i = 0; i < candidates.length; i++) {
            if (!video || candidates[i].duration > video.duration) video = candidates[i];
        }
        return video;
    }
    function option(id, label, selected) { return {id: String(id), label: label, selected: !!selected}; }
    function state() {
        var v = findVideo();
        if (!v) return;
        var subtitles = [], qualities = [], nativeTracks = v.textTracks;
        // Rift publishes real provider controls even when its HLS instance is private.
        var subButtons = document.querySelectorAll('#subtitleLanguages [data-sub]');
        if (subButtons.length) {
            Array.prototype.forEach.call(subButtons, function (b) {
                subtitles.push(option('dom:' + b.dataset.sub, b.textContent.trim(), b.classList.contains('active')));
            });
        } else {
            var selected = false;
            for (var t = 0; t < nativeTracks.length; t++) {
                var track = nativeTracks[t];
                if (track.kind !== 'subtitles' && track.kind !== 'captions') continue;
                selected = selected || track.mode === 'showing';
                subtitles.push(option(t, track.label || track.language || ('Subtitles ' + (t + 1)), track.mode === 'showing'));
            }
            subtitles.unshift(option('off', 'Off', !selected));
        }
        var qualityButtons = document.querySelectorAll('#settingsPanel [data-quality]');
        Array.prototype.forEach.call(qualityButtons, function (b) {
            qualities.push(option('dom:' + b.dataset.quality, b.textContent.trim(), b.classList.contains('active')));
        });
        var hls = window.hls;
        if (!qualities.length && hls && Array.isArray(hls.levels)) {
            qualities.push(option('auto', 'Auto', hls.autoLevelEnabled));
            hls.levels.forEach(function (level, i) { qualities.push(option(i, level.height + 'p', !hls.autoLevelEnabled && hls.currentLevel === i)); });
        }
        window.FlipflixPlayback.postMessage(JSON.stringify({available: v.readyState > 0,
            seekable: v.seekable.length > 0, position: Math.round((v.currentTime || 0) * 1000),
            duration: Number.isFinite(v.duration) ? Math.round(v.duration * 1000) : 0,
            paused: v.paused, muted: v.muted || v.volume === 0, subtitles: subtitles, qualities: qualities}));
    }
    function selectButton(selector, attribute, value) {
        var buttons = document.querySelectorAll(selector);
        for (var i = 0; i < buttons.length; i++) if (buttons[i].getAttribute(attribute) === value) { buttons[i].click(); break; }
    }
    window.FlipflixPlayback.onmessage = function (event) {
        try {
            var command = JSON.parse(event.data), v = findVideo(), value = command.value;
            if (!v) return;
            if (command.name === 'play' || (command.name === 'toggle' && v.paused)) {
                var promise = v.play(); if (promise) promise.catch(function () { state(); });
            } else if (command.name === 'pause' || command.name === 'toggle') v.pause();
            else if (command.name === 'mute') { if (v.muted || v.volume === 0) { v.muted = false; v.volume = 1; } else v.muted = true; }
            else if (command.name === 'seek' && v.seekable.length) {
                var target = Number(value) / 1000;
                if (Number.isFinite(target)) v.currentTime = Math.max(v.seekable.start(0), Math.min(target, v.seekable.end(v.seekable.length - 1)));
            } else if (command.name === 'subtitle') {
                if (value.indexOf('dom:') === 0) selectButton('#subtitleLanguages [data-sub]', 'data-sub', value.slice(4));
                else for (var t = 0; t < v.textTracks.length; t++) {
                    if (v.textTracks[t].kind === 'subtitles' || v.textTracks[t].kind === 'captions') v.textTracks[t].mode = String(t) === value ? 'showing' : 'disabled';
                }
            } else if (command.name === 'quality') {
                if (value.indexOf('dom:') === 0) selectButton('#settingsPanel [data-quality]', 'data-quality', value.slice(4));
                else if (window.hls) window.hls.currentLevel = value === 'auto' ? -1 : Number(value);
            }
            state();
        } catch (_) { /* An unsupported provider stays under its own playback control. */ }
    };
    ['loadedmetadata', 'play', 'pause', 'volumechange', 'durationchange', 'seeked', 'timeupdate'].forEach(function (name) {
        document.addEventListener(name, function (event) {
            if (!event.target || event.target.tagName !== 'VIDEO') return;
            video = event.target;
            if (name === 'timeupdate' && Date.now() - lastReport < 1000) return;
            lastReport = Date.now(); state();
        }, true);
    });
    document.addEventListener('DOMContentLoaded', state, {once: true});
    state();
}());
