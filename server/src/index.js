// Screen Mirror signaling server (Cloudflare Worker + Durable Object).
// It only passes small "how to connect" messages between the iPad and the phone.
// Video never goes through here: it travels directly (or via TURN), encrypted by WebRTC.

const CODE_TTL_MS = 2 * 60 * 1000; // pairing code valid for 2 minutes
const SESSION_MAX_MS = 12 * 60 * 60 * 1000; // a paired session lasts at most 12 hours
const MAX_MSG = 64 * 1024;
const MAX_FRAME = 4 * 1024 * 1024; // one encoded video frame

function randomCode() {
  const a = new Uint32Array(1);
  crypto.getRandomValues(a);
  return String(a[0] % 1000000).padStart(6, "0");
}

function randomToken() {
  const b = new Uint8Array(24);
  crypto.getRandomValues(b);
  return [...b].map((x) => x.toString(16).padStart(2, "0")).join("");
}

const STUN = [{ urls: ["stun:stun.cloudflare.com:3478", "stun:stun.l.google.com:19302"] }];

// Free relay (Open Relay Project by Metered) - same one the Sahayak app uses for video calls.
// Credentials are time-limited (HMAC-SHA1, standard TURN REST method). Video stays encrypted
// end-to-end by WebRTC, so the relay only passes scrambled packets it cannot read.
const OPEN_RELAY_HOST = "staticauth.openrelay.metered.ca";
const OPEN_RELAY_SECRET = "openrelayprojectsecret";

async function openRelay() {
  const username = String(Math.floor(Date.now() / 1000) + 12 * 60 * 60);
  const key = await crypto.subtle.importKey(
    "raw", new TextEncoder().encode(OPEN_RELAY_SECRET), { name: "HMAC", hash: "SHA-1" }, false, ["sign"]
  );
  const sig = await crypto.subtle.sign("HMAC", key, new TextEncoder().encode(username));
  const credential = btoa(String.fromCharCode(...new Uint8Array(sig)));
  return {
    urls: [
      `turn:${OPEN_RELAY_HOST}:80`,
      `turn:${OPEN_RELAY_HOST}:80?transport=tcp`,
      `turn:${OPEN_RELAY_HOST}:443`,
      `turns:${OPEN_RELAY_HOST}:443?transport=tcp`,
    ],
    username,
    credential,
  };
}

// ICE servers for a paired device: STUN + a relay.
async function iceServersFor(env) {
  if (!env.TURN_KEY_ID || !env.TURN_KEY_API_TOKEN) {
    try { return [...STUN, await openRelay()]; } catch (_) { return STUN; }
  }
  try {
    const r = await fetch(
      `https://rtc.live.cloudflare.com/v1/turn/keys/${env.TURN_KEY_ID}/credentials/generate-ice-servers`,
      {
        method: "POST",
        headers: { Authorization: `Bearer ${env.TURN_KEY_API_TOKEN}`, "Content-Type": "application/json" },
        body: JSON.stringify({ ttl: 12 * 60 * 60 }),
      }
    );
    if (!r.ok) return STUN;
    const j = await r.json();
    const list = Array.isArray(j.iceServers) ? j.iceServers : [j.iceServers];
    // Browsers block port 53; drop those URLs.
    const clean = list
      .map((s) => ({ ...s, urls: [].concat(s.urls).filter((u) => !/:53(\?|$)/.test(u)) }))
      .filter((s) => s.urls.length);
    return clean.length ? clean : STUN;
  } catch (_) {
    return STUN;
  }
}

export default {
  async fetch(request, env) {
    const url = new URL(request.url);

    if (url.pathname === "/ws") {
      if (request.headers.get("Upgrade") !== "websocket") {
        return new Response("Expected WebSocket", { status: 426 });
      }
      const role = url.searchParams.get("role");
      let code = url.searchParams.get("code") || "";
      if (role !== "receiver" && role !== "sender") {
        return new Response("Bad role", { status: 400 });
      }
      const isNew = role === "receiver" && code === "new";
      if (isNew) code = randomCode();
      if (!/^\d{6}$/.test(code)) return new Response("Bad code", { status: 400 });

      const stub = env.ROOMS.get(env.ROOMS.idFromName(code));
      const fwd = new URL(request.url);
      fwd.searchParams.set("code", code);
      fwd.searchParams.set("new", isNew ? "1" : "0");
      return stub.fetch(new Request(fwd, request));
    }

    if (url.pathname === "/api/ice") {
      // Public endpoint: STUN only. TURN credentials are given only to paired devices.
      return Response.json(
        { iceServers: STUN },
        { headers: { "Cache-Control": "no-store" } }
      );
    }

    return env.ASSETS.fetch(request);
  },
};

export class Room {
  constructor(ctx, env) {
    this.ctx = ctx;
    this.env = env;
  }

  async fetch(request) {
    const url = new URL(request.url);
    const role = url.searchParams.get("role");
    const code = url.searchParams.get("code");
    const token = url.searchParams.get("token") || "";
    const now = Date.now();

    const meta = (await this.ctx.storage.get("meta")) || null;
    const pair = new WebSocketPair();
    const [client, server] = Object.values(pair);

    const reject = (reason) => {
      this.ctx.acceptWebSocket(server);
      server.send(JSON.stringify({ type: "error", reason }));
      server.close(4000, reason);
      return new Response(null, { status: 101, webSocket: client });
    };

    if (role === "receiver") {
      const isNew = url.searchParams.get("new") === "1";
      if (!isNew) {
        // Receiver reconnecting to its own session: token required.
        if (!meta || meta.ended || !token || token !== meta.receiverToken) return reject("session-ended");
        this.closeRole("receiver");
      } else {
        const busy = meta && !meta.ended &&
          (now - meta.created < CODE_TTL_MS || (meta.senderToken && now - meta.created < SESSION_MAX_MS));
        if (busy) return reject("code-in-use");
        // New session.
        const m = { created: now, receiverToken: randomToken(), senderToken: null, ended: false };
        await this.ctx.storage.put("meta", m);
        this.closeRole("receiver");
        this.closeRole("sender");
        this.ctx.acceptWebSocket(server, ["receiver"]);
        server.send(JSON.stringify({
          type: "session", code, token: m.receiverToken, expiresInMs: CODE_TTL_MS,
          iceServers: await iceServersFor(this.env),
        }));
        return new Response(null, { status: 101, webSocket: client });
      }
      this.ctx.acceptWebSocket(server, ["receiver"]);
      server.send(JSON.stringify({
        type: "session", code, token: meta.receiverToken, resumed: true, iceServers: await iceServersFor(this.env),
      }));
      this.notify("sender", { type: "peer", role: "receiver", state: "online" });
      return new Response(null, { status: 101, webSocket: client });
    }

    // role === "sender"
    if (!meta || meta.ended) return reject("no-session");
    if (now - meta.created > SESSION_MAX_MS) return reject("session-expired");
    const receiverOnline = this.ctx.getWebSockets("receiver").length > 0;

    if (meta.senderToken) {
      // Already paired: only the same phone (with its token) may come back.
      if (token !== meta.senderToken) return reject("already-paired");
      this.closeRole("sender");
    } else {
      if (now - meta.created > CODE_TTL_MS) return reject("code-expired");
      if (!receiverOnline) return reject("receiver-offline");
      meta.senderToken = randomToken();
      await this.ctx.storage.put("meta", meta);
    }

    this.ctx.acceptWebSocket(server, ["sender"]);
    server.send(JSON.stringify({
      type: "joined", token: meta.senderToken, receiverOnline, iceServers: await iceServersFor(this.env),
    }));
    this.notify("receiver", { type: "peer", role: "sender", state: "online" });
    return new Response(null, { status: 101, webSocket: client });
  }

  roleOf(ws) {
    return this.ctx.getTags(ws)[0];
  }

  notify(role, msg) {
    for (const ws of this.ctx.getWebSockets(role)) {
      try { ws.send(JSON.stringify(msg)); } catch (_) {}
    }
  }

  closeRole(role) {
    for (const ws of this.ctx.getWebSockets(role)) {
      try { ws.close(4001, "replaced"); } catch (_) {}
    }
  }

  async webSocketMessage(ws, data) {
    if (typeof data !== "string") {
      // Binary = encrypted-in-transit (WSS) H.264 video frame from the phone. Pass it to the iPad only.
      if (this.roleOf(ws) !== "sender" || data.byteLength > MAX_FRAME) return;
      for (const r of this.ctx.getWebSockets("receiver")) {
        try { r.send(data); } catch (_) {}
      }
      return;
    }
    if (data.length > MAX_MSG) return;
    let msg;
    try { msg = JSON.parse(data); } catch (_) { return; }
    const from = this.roleOf(ws);
    if (!from) return; // rejected socket
    const to = from === "receiver" ? "sender" : "receiver";

    if (msg.type === "ping") {
      ws.send(JSON.stringify({ type: "pong" }));
      return;
    }
    if (msg.type === "bye") {
      // Disconnect + revoke: session is closed for good.
      const meta = await this.ctx.storage.get("meta");
      if (meta) { meta.ended = true; await this.ctx.storage.put("meta", meta); }
      this.notify(to, { type: "bye" });
      this.closeRole(to);
      ws.close(1000, "bye");
      return;
    }
    // Relay only WebRTC signaling messages.
    if (["offer", "answer", "ice", "restart", "stats", "mode", "keyframe"].includes(msg.type) ||
        (msg.type === "input" && from === "receiver")) {
      this.notify(to, msg);
    }
  }

  async webSocketClose(ws) {
    const role = this.roleOf(ws);
    if (!role) return; // rejected socket
    const other = role === "receiver" ? "sender" : "receiver";
    const stillThere = this.ctx.getWebSockets(role).some((w) => w !== ws && w.readyState === 1);
    if (!stillThere) this.notify(other, { type: "peer", role, state: "offline" });
  }

  async webSocketError(ws) {
    await this.webSocketClose(ws);
  }
}
