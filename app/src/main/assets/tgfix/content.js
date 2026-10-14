// AKTV: keeps TG's embed player alive on old TV hardware.
// - starts playback when the player stays paused (autoplay/black-screen fix)
// - clicks the big play button if the page hides the <video> behind its own UI
// - reloads the embed (TG then auto-fetches from a working server) when it stalls or errors
(function () {
  var host = location.hostname || "";
  var ref = document.referrer || "";
  var isTop = window.top === window;
  var ok = isTop ? (host.indexOf("livetgtv.lovable.app") >= 0 && location.pathname.indexOf("/embed/") >= 0)
                 : (ref.indexOf("livetgtv.lovable.app") >= 0);
  if (!ok) return;

  var KEY = "aktv_reloads", MAX_RELOADS = 5;
  var lastTime = -1, lastMove = Date.now(), started = Date.now(), playingSince = 0;

  function reloads() { try { return parseInt(sessionStorage.getItem(KEY) || "0", 10); } catch (e) { return 0; } }
  function setReloads(n) { try { sessionStorage.setItem(KEY, String(n)); } catch (e) {} }
  function hardReload(why) {
    var n = reloads();
    if (n >= MAX_RELOADS) return;
    setReloads(n + 1);
    try { console.log("AKTV reload: " + why); } catch (e) {}
    location.reload();
  }

  function tryPlay(v) {
    try {
      v.setAttribute("playsinline", ""); v.autoplay = true;
      v.style.visibility = "visible"; v.style.opacity = "1";
      var p = v.play();
      if (p && p.catch) p.catch(function () {
        // Autoplay with sound refused: start muted, then try to bring the sound back.
        v.muted = true;
        var q = v.play();
        if (q && q.then) q.then(function () { setTimeout(function () { try { v.muted = false; } catch (e) {} }, 1200); }).catch(function () {});
      });
    } catch (e) {}
  }

  function clickPlayButtons() {
    var sels = ['.vjs-big-play-button', '.jw-icon-display', '.plyr__control--overlaid', '.play-button', '.big-play',
                'button[aria-label*="Play" i]', 'button[title*="Play" i]', '[data-plyr="play"]', '.mejs__overlay-button', '.ytp-large-play-button'];
    for (var i = 0; i < sels.length; i++) {
      var el = document.querySelector(sels[i]);
      if (el && el.offsetParent !== null) { try { el.click(); } catch (e) {} return true; }
    }
    return false;
  }

  function tick() {
    var vids = document.querySelectorAll("video");
    var now = Date.now();
    if (!vids.length) {
      if (isTop && now - started > 30000) hardReload("no video element");
      return;
    }
    var best = vids[0];
    for (var i = 0; i < vids.length; i++) { if ((vids[i].videoWidth || 0) > (best.videoWidth || 0)) best = vids[i]; }
    var v = best;

    if (v.paused && !v.ended) {
      tryPlay(v);
      if (now - started > 4000) clickPlayButtons();
    }

    if (!v.paused && v.currentTime !== lastTime) {
      lastTime = v.currentTime; lastMove = now;
      if (!playingSince) playingSince = now;
      if (now - playingSince > 60000 && reloads() > 0) setReloads(0);   // stable: forgive earlier reloads
    } else if (now - lastMove > 18000) {
      lastMove = now; playingSince = 0;
      hardReload(v.paused ? "stuck paused" : "frozen");
    }
    if (v.error) { lastMove = now; setTimeout(function () { hardReload("media error"); }, 2500); }
  }

  setInterval(tick, 1500);
  document.addEventListener("visibilitychange", function () { setTimeout(tick, 300); });
})();
