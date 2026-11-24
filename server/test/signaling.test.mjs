// End-to-end test of the signaling server. Run against `wrangler dev`.
// Usage: BASE=http://127.0.0.1:8787 node test/signaling.test.mjs
import assert from "node:assert/strict";

const BASE = process.env.BASE || "http://127.0.0.1:8787";
const WS = BASE.replace("http", "ws") + "/ws";

function open(query) {
  return new Promise((resolve, reject) => {
    const ws = new WebSocket(`${WS}?${query}`);
    const inbox = [];
    const waiters = [];
    ws.onmessage = (e) => {
      const m = JSON.parse(e.data);
      const i = waiters.findIndex((w) => w.type === m.type);
      if (i >= 0) waiters.splice(i, 1)[0].resolve(m); else inbox.push(m);
    };
    ws.next = (type, ms = 5000) => {
      const i = inbox.findIndex((m) => m.type === type);
      if (i >= 0) return Promise.resolve(inbox.splice(i, 1)[0]);
      return new Promise((res, rej) => {
        const w = { type, resolve: res };
        waiters.push(w);
        setTimeout(() => rej(new Error(`timeout waiting for ${type}`)), ms);
      });
    };
    ws.sendJson = (o) => ws.send(JSON.stringify(o));
    ws.onopen = () => resolve(ws);
    ws.onerror = reject;
  });
}

let passed = 0;
const ok = (name) => { passed++; console.log("✓", name); };

// Website + ICE endpoint
const page = await fetch(BASE + "/");
assert.equal(page.status, 200);
assert.match(await page.text(), /Screen Mirror/);
ok("receiver website is served");

const ice = await (await fetch(BASE + "/api/ice")).json();
assert.ok(Array.isArray(ice.iceServers) && ice.iceServers.length > 0);
assert.ok(!JSON.stringify(ice).includes("credential"), "public endpoint has no relay credentials");
ok("/api/ice returns ICE servers");

// Receiver creates a session
const rx = await open("role=receiver&code=new");
const s = await rx.next("session");
assert.match(s.code, /^\d{6}$/);
assert.ok(s.token.length >= 32);
assert.ok(Array.isArray(s.iceServers) && s.iceServers.length > 0);
const relay = s.iceServers.find((x) => [].concat(x.urls).some((u) => u.startsWith("turn")));
assert.ok(relay && relay.username && relay.credential, "relay credentials present");
assert.ok(Number(relay.username) > Date.now() / 1000, "relay credentials are time-limited");
ok(`receiver got code ${s.code} and a secret token`);

// Wrong code is rejected
const wrong = s.code === "000000" ? "000001" : "000000";
const bad = await open(`role=sender&code=${wrong}`);
assert.equal((await bad.next("error")).reason, "no-session");
ok("wrong code is rejected");

// Receiver cannot be hijacked without its token
const hijack = await open(`role=receiver&code=${s.code}`);
assert.equal((await hijack.next("error")).reason, "session-ended");
ok("another receiver cannot take over the session");

// Phone joins
const tx = await open(`role=sender&code=${s.code}`);
const j = await tx.next("joined");
assert.ok(j.token.length >= 32);
assert.equal(j.receiverOnline, true);
assert.ok(Array.isArray(j.iceServers) && j.iceServers.length > 0);
const peer = await rx.next("peer");
assert.equal(peer.role, "sender");
ok("phone paired; iPad notified");

// Second phone with same code is refused
const tx2 = await open(`role=sender&code=${s.code}`);
assert.equal((await tx2.next("error")).reason, "already-paired");
ok("second phone is refused (no unauthorized devices)");

// Messages are relayed both ways
tx.sendJson({ type: "offer", sdp: "v=0 test-offer" });
assert.equal((await rx.next("offer")).sdp, "v=0 test-offer");
rx.sendJson({ type: "answer", sdp: "v=0 test-answer" });
assert.equal((await tx.next("answer")).sdp, "v=0 test-answer");
tx.sendJson({ type: "ice", candidate: { candidate: "candidate:1", sdpMid: "0", sdpMLineIndex: 0 } });
assert.equal((await rx.next("ice")).candidate.candidate, "candidate:1");
rx.sendJson({ type: "hack", x: 1 });
// Video frames (binary) go phone -> iPad only
rx.binaryType = "arraybuffer";
const gotFrame = new Promise((res) => {
  const prev = rx.onmessage;
  rx.onmessage = (e) => { if (typeof e.data !== "string") { rx.onmessage = prev; res(e.data); } else prev(e); };
});
tx.send(new Uint8Array([1, 0, 0, 0, 0, 0, 0, 0, 7, 0xaa, 0xbb]));
const fr = new Uint8Array(await gotFrame);
assert.equal(fr.length, 11);
assert.equal(fr[10], 0xbb);
rx.sendJson({ type: "keyframe" });
await tx.next("keyframe");
rx.sendJson({ type: "input", action: "tap", x: 0.5, y: 0.5 });
assert.equal((await tx.next("input")).action, "tap");
ok("offer / answer / ICE / video frames / keyframe requests relayed; unknown messages dropped");

// Phone reconnects with its token
tx.close();
assert.equal((await rx.next("peer")).state, "offline");
const tx3 = await open(`role=sender&code=${s.code}&token=${j.token}`);
await tx3.next("joined");
assert.equal((await rx.next("peer")).state, "online");
ok("phone reconnects with its token");

// Disconnect revokes the session
rx.sendJson({ type: "bye" });
await tx3.next("bye");
const late = await open(`role=sender&code=${s.code}&token=${j.token}`);
assert.equal((await late.next("error")).reason, "no-session");
ok("disconnect revokes access");

console.log(`\nAll ${passed} tests passed`);
process.exit(0);
