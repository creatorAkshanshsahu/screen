// AKTV stream proxy (Cloudflare Worker). Deploy, then paste its URL + your key into MainActivity.kt (WORKER / WORKER_KEY).
//
// Worker settings -> Variables and Secrets:
//   KEY      (secret)  any long random string; the app sends it as X-Aktv-Key so strangers can't use your proxy
//   REFERER  (optional) defaults to https://livetgtv.lovable.app/
//
// URL shape:  https://YOUR.workers.dev/https/host.example.com/path/index.m3u8?token=...
// Playlists (.m3u8 / .mpd) are rewritten so every segment also goes through the worker.
const UA = "Mozilla/5.0 (Linux; Android 9; TV) AppleWebKit/537.36 Chrome/110 Safari/537.36";

export default {
  async fetch(req, env) {
    const cors = { "access-control-allow-origin": "*", "access-control-allow-headers": "*", "access-control-expose-headers": "*" };
    if (req.method === "OPTIONS") return new Response(null, { status: 204, headers: cors });
    if (!env.KEY || req.headers.get("x-aktv-key") !== env.KEY) return new Response("forbidden", { status: 403 });

    const self = new URL(req.url);
    const m = self.pathname.match(/^\/(https?)\/([^\/]+)(\/.*)?$/);
    if (!m) return new Response("AKTV proxy ok", { status: 200 });
    const [, scheme, host, path = "/"] = m;
    const prefix = `${self.origin}/`;
    const upstream = `${scheme}://${host}${path}${self.search}`;
    const referer = env.REFERER || "https://livetgtv.lovable.app/";

    const h = new Headers();
    h.set("user-agent", UA);
    h.set("referer", referer);
    h.set("origin", new URL(referer).origin);
    const range = req.headers.get("range");
    if (range) h.set("range", range);

    const r = await fetch(upstream, { method: req.method === "HEAD" ? "HEAD" : "GET", headers: h, redirect: "manual" });
    const out = new Headers(r.headers);
    for (const [k, v] of Object.entries(cors)) out.set(k, v);

    if (r.status >= 300 && r.status < 400 && r.headers.get("location")) {
      const loc = new URL(r.headers.get("location"), upstream);
      out.set("location", `${prefix}${loc.protocol.replace(":", "")}/${loc.host}${loc.pathname}${loc.search}`);
      return new Response(null, { status: r.status, headers: out });
    }

    const ct = (r.headers.get("content-type") || "").toLowerCase();
    const isPlaylist = /\.(m3u8|mpd)(\?|$)/i.test(path) || ct.includes("mpegurl") || ct.includes("dash+xml");
    if (isPlaylist && r.ok) {
      let body = await r.text();
      // absolute URLs -> through the worker
      body = body.replace(/https?:\/\/([^\s"'<>]+)/g, (full) => {
        const u = new URL(full);
        return `${prefix}${u.protocol.replace(":", "")}/${u.host}${u.pathname}${u.search}`;
      });
      // root-relative playlist lines ("/live/seg1.ts") -> same upstream host through the worker
      body = body.split("\n").map((l) => (l.startsWith("/") && !l.startsWith("//") ? `${prefix}${scheme}/${host}${l}` : l)).join("\n");
      out.delete("content-length"); out.delete("content-encoding");
      return new Response(body, { status: r.status, headers: out });
    }
    return new Response(r.body, { status: r.status, headers: out });
  },
};
