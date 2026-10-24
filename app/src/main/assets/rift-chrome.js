/* Rift-only presentation boundary. No video manipulation, polling, or DOM scans. */
(function () {
    if (window.__flipflixRiftChrome || !window.FlipflixRiftChrome) return;
    window.__flipflixRiftChrome = true;
    var nativeControls = true, session = __SESSION__, style = document.createElement('style');
    style.id = 'flipflix-rift-chrome';
    // These are Rift's observed control containers, not general page overlays.
    // Captions, video, loading/preroll and fatal error/retry content remain untouched.
    var controls = ['.top-bar', '#labeledBar', '.top-actions', '.bottom-bar', '#centerToggle',
        '#centerBack', '#centerFwd', '#pauseCard', '#flash', '#lockShield', '#introSkip',
        '#nextUpCard', '#lbQualityMenu', '#lbSubsMenu', '#lbEpisodesView', '#episodesPanel'];
    style.textContent = controls.map(function (selector) {
        return 'html[data-flipflix-native-controls] #player > ' + selector;
    }).join(',') + '{display:none!important;pointer-events:none!important}';
    function apply() {
        if (!document.documentElement) return;
        if (!style.isConnected) document.documentElement.appendChild(style);
        if (nativeControls) document.documentElement.setAttribute('data-flipflix-native-controls', '');
        else document.documentElement.removeAttribute('data-flipflix-native-controls');
    }
    window.FlipflixRiftChrome.onmessage = function (event) {
        try {
            var data = JSON.parse(event.data);
            if (data.session !== session || typeof data.native !== 'boolean') return;
            nativeControls = data.native; apply();
        } catch (_) { }
    };
    // Document-start may precede <html>. Observe only until that root exists.
    if (document.documentElement) apply();
    else {
        var rootReady = new MutationObserver(function () {
            if (document.documentElement) { rootReady.disconnect(); apply(); }
        });
        rootReady.observe(document, {childList:true});
    }
    window.FlipflixRiftChrome.postMessage(session);
}());
