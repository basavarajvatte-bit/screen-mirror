// Screen Mirror signaling server (Cloudflare Worker + Durable Object).
// It only passes small "how to connect" messages between the iPad and the phone.
// Video never goes through here: it travels directly (or via TURN), encrypted by WebRTC.

const CODE_TTL_MS = 2 * 60 * 1000; // pairing code valid for 2 minutes
const SESSION_MAX_MS = 12 * 60 * 60 * 1000; // a paired session lasts at most 12 hours
const MAX_MSG = 64 * 1024;

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
      // Phase 7 will add TURN here. For now: free public STUN.
      return Response.json(
        { iceServers: [{ urls: ["stun:stun.cloudflare.com:3478", "stun:stun.l.google.com:19302"] }] },
        { headers: { "Cache-Control": "no-store" } }
      );
    }

    return env.ASSETS.fetch(request);
  },
};

export class Room {
  constructor(ctx, env) {
    this.ctx = ctx;
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
        server.send(JSON.stringify({ type: "session", code, token: m.receiverToken, expiresInMs: CODE_TTL_MS }));
        return new Response(null, { status: 101, webSocket: client });
      }
      this.ctx.acceptWebSocket(server, ["receiver"]);
      server.send(JSON.stringify({ type: "session", code, token: meta.receiverToken, resumed: true }));
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
    server.send(JSON.stringify({ type: "joined", token: meta.senderToken, receiverOnline }));
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
    if (typeof data !== "string" || data.length > MAX_MSG) return;
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
    if (["offer", "answer", "ice", "restart", "stats"].includes(msg.type)) {
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
