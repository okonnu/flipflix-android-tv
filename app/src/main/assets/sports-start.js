/* Start the sports provider's player, not arbitrary page/ad buttons. */
(function () {
  'use strict';
  if (window.self !== window.top || location.protocol !== 'https:' ||
      location.hostname !== 'embed.st' || (location.port && location.port !== '443') ||
      location.pathname.indexOf('/embed/') !== 0 || window.__flipflixSportsStart) return;

  var timers = [], stopped = false, leaving = false;
  function cancel() {
    stopped = true;
    timers.forEach(function (id) { clearTimeout(id); });
    timers = [];
    document.removeEventListener('DOMContentLoaded', automatic);
    document.removeEventListener('playing', playing, true);
  }
  function playing(event) {
    var root = document.getElementById('player');
    if (root && event.target.tagName === 'VIDEO' && root.contains(event.target)) cancel();
  }
  function attempt() {
    if (leaving) return false;
    var root = document.getElementById('player');
    if (!root) return false;
    // JW's public API starts the provider's loading flow before HTML video has
    // metadata. video.play() alone cannot start that flow on the initial screen.
    try {
      if (typeof window.jwplayer === 'function') {
        var player = window.jwplayer('player');
        var state = player && typeof player.getState === 'function' ? player.getState() : '';
        if (state === 'playing' || state === 'buffering') { cancel(); return true; }
        if ((state === 'idle' || state === 'paused') && typeof player.play === 'function') {
          var result = player.play();
          cancel();
          // JW currently returns void; also tolerate Promise-returning versions.
          // A refused autoplay can be retried explicitly from the TV controls.
          if (result && typeof result.catch === 'function') result.catch(function () {});
          return true;
        }
      }
    } catch (ignored) { /* A player still being set up can be tried later. */ }

    // Fallback to the observed JW start control only. Never tap the screen
    // center or click links/overlays that may belong to an advertisement.
    var button = root.querySelector('.jw-display-icon-display .jw-icon-display[role="button"][aria-label="Play"]');
    if (!button || button.getAttribute('aria-disabled') === 'true') return false;
    var rect = button.getBoundingClientRect(), style = getComputedStyle(button);
    if (!rect.width || !rect.height || style.visibility === 'hidden' || style.display === 'none') return false;
    button.click();
    cancel();
    return true;
  }
  function automatic() { if (!stopped) attempt(); }
  window.__flipflixSportsStart = {play: attempt, cancel: cancel};
  document.addEventListener('DOMContentLoaded', automatic);
  document.addEventListener('playing', playing, true);
  window.addEventListener('pagehide', function () { leaving = true; cancel(); }, {once: true});
  // Bounded readiness checks only: no polling during playback or DOM scanning.
  [0, 250, 750, 1500, 3000, 6000, 12000, 20000].forEach(function (delay) {
    timers.push(setTimeout(automatic, delay));
  });
  timers.push(setTimeout(cancel, 21000));
}());
