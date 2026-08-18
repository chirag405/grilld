import { NextRequest, NextResponse } from "next/server";
import { getToken } from "@/lib/auth";

/**
 * The one place a Client Component's fetch() (or EventSource, for the Run
 * Report SSE stream) actually goes. The JWT lives in an httpOnly cookie -
 * page JS can't read it to set an Authorization header itself - so every
 * authenticated call from the browser is proxied through here instead,
 * where a Route Handler (running on the Node server, not in the browser)
 * reads the cookie and attaches the header before forwarding to Spring.
 * Streams the upstream response body straight through unmodified, which is
 * what makes this work for both plain JSON responses and the Run Report's
 * text/event-stream SSE connection - same handler, no special-casing.
 */
const BACKEND_URL = process.env.BACKEND_API_URL ?? "http://localhost:8080";

async function proxy(request: NextRequest, ctx: RouteContext<"/api/proxy/[...path]">) {
  const token = await getToken();
  if (!token) {
    return NextResponse.json({ error: "Not authenticated" }, { status: 401 });
  }

  const { path } = await ctx.params;
  const upstreamUrl = new URL(`/api/v1/${path.join("/")}`, BACKEND_URL);
  upstreamUrl.search = request.nextUrl.search;

  const upstream = await fetch(upstreamUrl, {
    method: request.method,
    headers: {
      Authorization: `Bearer ${token}`,
      ...(request.headers.get("content-type")
        ? { "Content-Type": request.headers.get("content-type")! }
        : {}),
    },
    body: ["GET", "HEAD"].includes(request.method) ? undefined : await request.text(),
    // The Run Report's SSE stream is a long-lived connection - undici (fetch's
    // implementation here) needs this to hand back a readable body instead of
    // buffering the whole response before resolving.
    // @ts-expect-error -- duplex isn't in the TS lib fetch types yet, but Node's fetch requires it for streaming request bodies; harmless for GET/HEAD.
    duplex: "half",
  });

  const headers: Record<string, string> = {
    "Content-Type": upstream.headers.get("content-type") ?? "application/json",
  };
  // The package download response's filename lives here - dropping it (the
  // proxy used to forward only Content-Type) meant the browser fell back to
  // guessing a filename from the URL instead of grilld-blueprint-<runId>.zip.
  const disposition = upstream.headers.get("content-disposition");
  if (disposition) {
    headers["Content-Disposition"] = disposition;
  }

  return new Response(upstream.body, {
    status: upstream.status,
    headers,
  });
}

export { proxy as GET, proxy as POST, proxy as PUT, proxy as DELETE };
