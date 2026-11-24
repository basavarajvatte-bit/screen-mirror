package com.basavaraj.screenmirror

/** The page the iPad sees when it opens the phone's address. Works in Safari and Chrome. */
object Receiver {
    fun page(key: String) = """<!DOCTYPE html>
<html><head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1, viewport-fit=cover">
<meta name="apple-mobile-web-app-capable" content="yes">
<title>Screen Mirror</title>
<style>
  html,body{margin:0;height:100%;background:#000;overflow:hidden;font-family:-apple-system,system-ui,sans-serif;color:#fff}
  #v{position:fixed;inset:0;width:100%;height:100%;object-fit:contain}
  #bar{position:fixed;left:50%;transform:translateX(-50%);bottom:calc(16px + env(safe-area-inset-bottom));display:flex;gap:10px;transition:opacity .3s}
  #bar.hide{opacity:0;pointer-events:none}
  button{font:inherit;font-weight:600;border:0;border-radius:14px;padding:12px 18px;background:rgba(255,255,255,.16);color:#fff;-webkit-backdrop-filter:blur(12px);backdrop-filter:blur(12px)}
  #info{position:fixed;top:calc(10px + env(safe-area-inset-top));left:10px;padding:7px 11px;border-radius:10px;background:rgba(0,0,0,.55);font-size:13px;transition:opacity .3s}
  #info.hide{opacity:0}
  #msg{position:fixed;inset:0;display:none;align-items:center;justify-content:center;text-align:center;padding:24px;font-size:18px;background:rgba(0,0,0,.7)}
</style></head>
<body>
<img id="v" alt="">
<div id="info">Connecting…</div>
<div id="msg"></div>
<div id="bar"><button id="fs">⛶ Full screen</button><button id="rl">↻ Reconnect</button></div>
<script>
"use strict";
const KEY = "$key";
const v = document.getElementById("v"), info = document.getElementById("info"), msg = document.getElementById("msg");
let frames = 0, fails = 0, lastLoad = Date.now(), hideT = null;
const MAX_FAILS = 20;

function connect() {
  msg.style.display = "none";
  v.src = "/stream?k=" + KEY + "&t=" + Date.now();
}
v.onload = () => { frames++; fails = 0; lastLoad = Date.now(); };
v.onerror = () => {
  fails++;
  if (fails > MAX_FAILS) { msg.textContent = "Phone stopped casting. Start it again, then tap Reconnect."; msg.style.display = "flex"; return; }
  info.textContent = "Reconnecting… (" + fails + "/" + MAX_FAILS + ")";
  setTimeout(connect, Math.min(500 * fails, 5000));
};
// Picture-stream images fire onload on many browsers per frame; also watch size changes.
let lastW = 0, lastCount = 0;
setInterval(() => {
  const w = v.naturalWidth, h = v.naturalHeight;
  if (w && (w !== lastW || frames !== lastCount)) {
    info.textContent = w + "×" + h + " · local Wi-Fi/hotspot · no internet used";
  }
  lastW = w; lastCount = frames;
}, 1000);

function poke() {
  document.getElementById("bar").classList.remove("hide"); info.classList.remove("hide");
  clearTimeout(hideT);
  hideT = setTimeout(() => { document.getElementById("bar").classList.add("hide"); info.classList.add("hide"); }, 3500);
}
document.body.addEventListener("click", poke);
document.getElementById("fs").onclick = (e) => {
  e.stopPropagation();
  const d = document, el = d.documentElement;
  if (d.fullscreenElement || d.webkitFullscreenElement) (d.exitFullscreen || d.webkitExitFullscreen).call(d);
  else if (el.requestFullscreen) el.requestFullscreen().catch(() => {});
  else if (el.webkitRequestFullscreen) el.webkitRequestFullscreen();
};
document.getElementById("rl").onclick = (e) => { e.stopPropagation(); fails = 0; connect(); };
document.addEventListener("visibilitychange", () => { if (document.visibilityState === "visible") { fails = 0; connect(); } });
connect(); poke();
</script>
</body></html>"""
}
