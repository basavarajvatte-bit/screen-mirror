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
<div id="bar"><button id="snd">🔇 Tap for sound</button><button id="fs">⛶ Full screen</button><button id="rl">↻ Reconnect</button></div>
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
// ---- Sound: raw 24 kHz mono 16-bit pieces from the phone, played with Web Audio ----
const RATE = 24000;
let actx = null, soundOn = false, nextT = 0, audioAbort = null;
async function startSound() {
  if (!actx) actx = new (window.AudioContext || window.webkitAudioContext)();
  await actx.resume();
  soundOn = true; document.getElementById("snd").textContent = "🔊 Sound on";
  let tries = 0;
  while (soundOn && tries < 20) {
    try {
      audioAbort = new AbortController();
      const r = await fetch("/audio?k=" + KEY + "&t=" + Date.now(), { signal: audioAbort.signal, cache: "no-store" });
      const reader = r.body.getReader();
      let rest = new Uint8Array(0);
      tries = 0;
      while (soundOn) {
        const { value, done } = await reader.read();
        if (done) break;
        const all = new Uint8Array(rest.length + value.length);
        all.set(rest); all.set(value, rest.length);
        const n = all.length >> 1;
        rest = all.slice(n * 2);
        if (n) play(new DataView(all.buffer, 0, n * 2), n);
      }
    } catch (_) {}
    if (!soundOn) break;
    tries++;
    await new Promise((ok) => setTimeout(ok, 1000));
  }
}
function play(dv, n) {
  const buf = actx.createBuffer(1, n, RATE);
  const ch = buf.getChannelData(0);
  for (let i = 0; i < n; i++) ch[i] = dv.getInt16(i * 2, true) / 32768;
  const now = actx.currentTime;
  if (nextT < now + 0.03 || nextT > now + 0.6) nextT = now + 0.08; // keep sound in step with picture
  const src = actx.createBufferSource();
  src.buffer = buf; src.connect(actx.destination); src.start(nextT);
  nextT += buf.duration;
}
document.getElementById("snd").onclick = (e) => {
  e.stopPropagation();
  if (soundOn) { soundOn = false; try { audioAbort && audioAbort.abort(); } catch (_) {} document.getElementById("snd").textContent = "🔇 Tap for sound"; }
  else startSound();
};

connect(); poke();
</script>
</body></html>"""
}
