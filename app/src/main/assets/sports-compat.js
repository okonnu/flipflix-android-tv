(function () {
    'use strict';
    // embed.st mistakes a failed PDF plugin probe for an iframe sandbox.
    // Android WebView has no inline PDF plugin. Only handle that exact probe
    // on the directly loaded, non-framed sports player, never a real iframe.
    if (location.protocol !== 'https:' || location.hostname !== 'embed.st' ||
        location.pathname.indexOf('/embed/') !== 0 || window.top !== window ||
        window.__flipflixSportsCompat) return;
    window.__flipflixSportsCompat = true;
    window.addEventListener('error', function (event) {
        var object = event.target;
        if (object && object.tagName === 'OBJECT' && object.id === 'sandDetect' &&
            object.getAttribute('data') === 'data:application/pdf;base64,aG1t') {
            event.stopImmediatePropagation();
        }
    }, true);
}());
