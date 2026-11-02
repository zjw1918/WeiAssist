// Runs the real pi-host bundle (what ships in the APK) in a sandbox, with a fake Kotlin side that
// answers the bridge like PiEngine does: native fetch (via Node's fetch), credential mirroring,
// loopback listen/close, and app host calls (e.g. "phone", "confirm") supplied by each test.
import { execFileSync } from "node:child_process";
import { mkdtempSync, readFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import vm from "node:vm";

let bundle;
/** Build the bundle once per test process, exactly as Gradle does. */
export function buildBundle() {
	if (bundle) return bundle;
	const out = mkdtempSync(join(tmpdir(), "pi-host-test-"));
	execFileSync(process.execPath, ["build.mjs", "--out", out], { cwd: new URL("..", import.meta.url), stdio: "pipe" });
	bundle = readFileSync(join(out, "pi", "host.js"), "utf8");
	return bundle;
}

export async function startHost({ credentials = null, hostCalls = {} } = {}) {
	// Kotlin's pause gate: by default every session may go on.
	hostCalls = { gate: () => true, ...hostCalls };
	const messages = [];
	const waiters = [];
	const fetches = [];
	const listening = new Set();
	const aborts = new Map();
	const state = { credentials };
	let ctx;

	const call = (fn, arg) => ctx.PiHost[fn](arg === undefined ? undefined : JSON.stringify(arg));

	async function nativeFetch(m) {
		fetches.push({ method: m.method, url: m.url });
		const ac = new AbortController();
		aborts.set(m.id, ac);
		try {
			const res = await fetch(m.url, {
				method: m.method,
				headers: m.headers,
				body: m.body ? Buffer.from(m.body, "base64") : undefined,
				signal: ac.signal,
			});
			const headers = Object.fromEntries(res.headers.entries());
			ctx.PiHost.fetchHead(JSON.stringify({ id: m.id, status: res.status, statusText: res.statusText, headers }));
			for await (const chunk of res.body ?? []) ctx.PiHost.fetchChunk(m.id, Buffer.from(chunk).toString("base64"));
			ctx.PiHost.fetchEnd(m.id);
		} catch (e) {
			if (!ac.signal.aborted) ctx.PiHost.fetchError(m.id, String(e?.message ?? e));
		} finally {
			aborts.delete(m.id);
		}
	}

	function onMessage(raw) {
		const m = JSON.parse(raw);
		messages.push(m);
		for (const w of [...waiters]) if (w.match(m)) { waiters.splice(waiters.indexOf(w), 1); w.resolve(m); }
		switch (m.type) {
			case "ready":
				call("init", { deviceId: "3f1c2a9e-6b7d-4e21-9a0c-5d8e7f6a1b2c", agentName: "WeiAssist", credentials: state.credentials });
				break;
			case "credentials_changed":
				state.credentials = m.json;
				break;
			case "http_listen":
				listening.add(m.port);
				call("reply", { id: m.id, ok: true, value: null });
				break;
			case "http_close":
				listening.delete(m.port);
				break;
			case "fetch":
				nativeFetch(m);
				break;
			case "fetch_abort":
				aborts.get(m.id)?.abort();
				break;
			default:
				if (hostCalls[m.type]) {
					Promise.resolve(hostCalls[m.type](m)).then(
						(value) => call("reply", { id: m.id, ok: true, value: value ?? null }),
						(e) => call("reply", { id: m.id, ok: false, value: { message: String(e?.message ?? e) } }),
					);
				}
		}
	}

	const g = globalThis;
	ctx = vm.createContext({
		PiBridge: { postMessage: (raw) => queueMicrotask(() => onMessage(raw)) },
		location: { origin: "https://appassets.androidplatform.net" },
		console, setTimeout, clearTimeout, setInterval, clearInterval, queueMicrotask, structuredClone, performance,
		TextEncoder, TextDecoder, URL, URLSearchParams, AbortController, AbortSignal, DOMException, Event, EventTarget,
		Headers: g.Headers, Request: g.Request, Response: g.Response, ReadableStream: g.ReadableStream, Blob: g.Blob,
		FormData: g.FormData, fetch: g.fetch, crypto: g.crypto, btoa, atob, navigator: g.navigator,
	});

	/** Resolve with the first message (already received or future) matching `type` and `pred`. */
	const waitFor = (type, pred = () => true, timeoutMs = 10_000) => {
		const match = (m) => m.type === type && pred(m);
		const seen = messages.find(match);
		if (seen) return Promise.resolve(seen);
		return new Promise((resolve, reject) => {
			const timer = setTimeout(() => reject(new Error(`timed out waiting for ${type}`)), timeoutMs);
			waiters.push({ match, resolve: (m) => { clearTimeout(timer); resolve(m); } });
		});
	};

	vm.runInContext(buildBundle(), ctx);
	await waitFor("status");
	return { ctx, call, messages, waitFor, fetches, listening, state, clear: () => messages.splice(0) };
}
