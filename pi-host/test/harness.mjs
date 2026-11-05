// Runs the real pi-host bundle (what ships in the APK) in a sandbox, with a fake Kotlin side that
// answers the bridge like PiEngine does: native fetch (via Node's fetch), credential mirroring,
// loopback listen/close, and app host calls supplied by each test. Like the app, it gives every session its tools
// from the real catalog (agent/…/tools.json, chosen as ToolCatalog.forSession does) and runs each "tool" call as
// Kotlin's ToolRouter does, through the test's fakes for the parts of the app: "phone", "bash", "send_file",
// "supervisor". The raw "tool" calls pi-host made are in `toolCalls`.
import { execFileSync } from "node:child_process";
import { mkdtempSync, readFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import vm from "node:vm";

/** The app's tool catalog, as Kotlin loads it. */
export const catalog = JSON.parse(readFileSync(new URL("../../agent/src/main/resources/com/lanqi/weiassist/agent/tools.json", import.meta.url), "utf8")).tools;

/** A session's tools, as ToolCatalog.forSession chooses them from what configure says about the session. */
export function toolSpecsFor(cfg) {
	const kind = cfg.tools ?? (cfg.phone ? "phone" : undefined);
	const sets = kind === "supervisor" ? ["supervisor"]
		: [...(kind === "phone" ? ["phone"] : []), ...(cfg.bash !== false ? ["bash"] : []), ...(cfg.bash !== false && cfg.channel ? ["send_file"] : [])];
	return catalog.filter((t) => sets.includes(t.set)).map(({ set, ...spec }) => spec);
}

/** SessionCommands' action for each supervisor tool (ToolRouter.supervisorCall). */
const SUPERVISOR_ACTIONS = {
	list_sessions: "list", get_session: "get", create_session: "create", send_message: "send", wait_for: "wait",
	pause_session: "pause", resume_session: "resume", stop_session: "stop", delete_session: "delete", rename_session: "rename",
};

let bundle;
/** Build the bundle once per test process, exactly as Gradle does. */
export function buildBundle() {
	if (bundle) return bundle;
	const out = mkdtempSync(join(tmpdir(), "pi-host-test-"));
	execFileSync(process.execPath, ["build.mjs", "--out", out], { cwd: new URL("..", import.meta.url), stdio: "pipe" });
	bundle = readFileSync(join(out, "pi", "host.js"), "utf8");
	return bundle;
}

export async function startHost({ credentials = null, customProviders = [], hostCalls = {} } = {}) {
	// Kotlin's before_tool (pause gate, phone safety checks): by default every tool call may go on.
	hostCalls = { before_tool: () => null, ...hostCalls };
	const messages = [];
	const waiters = [];
	const fetches = [];
	const listening = new Set();
	const aborts = new Map();
	const state = { credentials };
	let ctx;

	const toolCalls = [];
	const runningBash = new Set();
	// The app sends each session's tools with configure (PiEngine.configure).
	const call = (fn, arg) => {
		if (fn === "configure" && arg && !arg.toolSpecs) arg = { ...arg, toolSpecs: toolSpecsFor(arg) };
		return ctx.PiHost[fn](arg === undefined ? undefined : JSON.stringify(arg));
	};
	const never = new Promise(() => {});
	const setOf = Object.fromEntries(catalog.map((t) => [t.name, t.set]));

	/** A "tool" call, as Kotlin's ToolRouter runs it; a part of the app the test doesn't fake never answers. */
	async function runTool(m) {
		toolCalls.push(m);
		const a = m.args ?? {};
		const fake = (name) => hostCalls[name] ?? (() => never);
		switch (setOf[m.name]) {
			case "phone": {
				if (m.name === "note") return { text: "noted" };
				const out = await fake("phone")({ session: m.session, id: m.id, action: m.name, args: a });
				if (m.name === "screenshot" && out.startsWith("/9j/")) {
					return { text: "Screenshot (720 px wide; for tap_at, scale x and y by screen width / 720).", image: { data: out, mimeType: "image/jpeg" } };
				}
				return { text: out };
			}
			case "bash": {
				runningBash.add(m.call);
				try {
					return { text: await fake("bash")({ session: m.session, id: m.id, call: m.call, command: a.command, timeout: Math.max(1, Math.min(600, a.timeout ?? 120)) }) };
				} finally {
					runningBash.delete(m.call);
				}
			}
			case "send_file": {
				const r = await fake("send_file")({ session: m.session, id: m.id, path: a.path, caption: a.caption ?? "" });
				return { text: r.text, isError: r.ok !== true };
			}
			case "supervisor":
				return { text: await fake("supervisor")({ session: m.session, id: m.id, action: SUPERVISOR_ACTIONS[m.name], args: a }) };
			default:
				return { text: `This session has no tool ${m.name}.`, isError: true };
		}
	}
	hostCalls = {
		tool: runTool,
		// Only a running bash command has anything to end (ToolRouter.cancel).
		tool_cancel: (m) => (runningBash.has(m.call) ? (hostCalls.bash_kill ?? (() => null))({ session: m.session, call: m.call }) : null),
		...hostCalls,
	};

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
				call("init", { deviceId: "3f1c2a9e-6b7d-4e21-9a0c-5d8e7f6a1b2c", agentName: "WeiAssist", credentials: state.credentials, customProviders });
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
	return { ctx, call, messages, toolCalls, waitFor, fetches, listening, state, clear: () => messages.splice(0) };
}
