// fetch() for Pi, carried out by Kotlin (NativeHttp.kt) instead of the WebView's network stack.
// Why: browsers enforce CORS, and providers don't send CORS headers on every response (OpenAI's 401/429
// errors have none), so the WebView would turn real API errors into "Connection error". Node, Pi's home
// runtime, has no CORS; this gives Pi the same plain HTTP client on Android. Bodies stream back in chunks,
// so SSE token streaming still arrives token by token, and AbortSignal cancels the native request.
import { bridge } from "./bridge.ts";

type Pending = { controller?: ReadableStreamDefaultController<Uint8Array>; resolve: (r: Response) => void; reject: (e: unknown) => void };
const pending = new Map<string, Pending>();
let seq = 0;

const toB64 = (buf: ArrayBuffer) => {
	let s = "";
	const bytes = new Uint8Array(buf);
	for (let i = 0; i < bytes.length; i += 0x8000) s += String.fromCharCode(...bytes.subarray(i, i + 0x8000));
	return btoa(s);
};
const fromB64 = (b64: string) => Uint8Array.from(atob(b64), (c) => c.charCodeAt(0));

const browserFetch = globalThis.fetch.bind(globalThis);

export async function nativeFetch(input: RequestInfo | URL, init?: RequestInit): Promise<Response> {
	const req = new Request(input, init);
	// The app's own assets still load through the WebView.
	if (new URL(req.url).origin === location.origin) return browserFetch(input, init);
	const id = `f${++seq}`;
	const body = req.method === "GET" || req.method === "HEAD" ? null : await req.arrayBuffer();
	const signal = init?.signal ?? req.signal;
	signal?.throwIfAborted();
	return new Promise<Response>((resolve, reject) => {
		pending.set(id, { resolve, reject });
		signal?.addEventListener("abort", () => {
			const p = pending.get(id);
			if (!p) return;
			pending.delete(id);
			bridge({ type: "fetch_abort", id });
			const err = signal.reason ?? new DOMException("Aborted", "AbortError");
			p.controller ? p.controller.error(err) : p.reject(err);
		}, { once: true });
		bridge({
			type: "fetch", id, method: req.method, url: req.url,
			headers: Object.fromEntries(req.headers.entries()),
			body: body && body.byteLength ? toB64(body) : null,
		});
	});
}

/** Kotlin → JS: response status/headers, then body chunks, then end or error. */
export const fetchEvents = {
	head(id: string, status: number, statusText: string, headers: Record<string, string>) {
		const p = pending.get(id);
		if (!p) return;
		const stream = new ReadableStream<Uint8Array>({ start: (c) => { p.controller = c; } });
		const nullBody = status === 204 || status === 304;
		p.resolve(new Response(nullBody ? null : stream, { status, statusText, headers }));
	},
	chunk(id: string, b64: string) {
		pending.get(id)?.controller?.enqueue(fromB64(b64));
	},
	end(id: string) {
		const p = pending.get(id);
		pending.delete(id);
		try { p?.controller?.close(); } catch {}
	},
	error(id: string, message: string) {
		const p = pending.get(id);
		pending.delete(id);
		const err = new TypeError(`Network error: ${message}`);
		p?.controller ? p.controller.error(err) : p?.reject(err);
	},
};
