// Pi host: runs Pi's Agent (pi-agent-core) and LLM + auth layer (pi-ai) inside an Android WebView.
//   Kotlin -> JS: window.PiHost.<fn>(json)   (evaluateJavascript)
//   JS -> Kotlin: PiBridge.postMessage(json) (WebViewCompat.addWebMessageListener)
// No LLM, agent or login logic lives here: this file adapts Pi's own APIs to that channel.
import { Agent, type AgentEvent, type AgentMessage, type BeforeToolCallContext } from "@earendil-works/pi-agent-core";
import {
	clampThinkingLevel,
	getSupportedThinkingLevels,
	type AuthInteraction,
	type AuthPrompt,
	type Credential,
	type CredentialStore,
	createModels,
	InMemoryCredentialStore,
	type Model,
} from "@earendil-works/pi-ai";
import { openaiProvider } from "@earendil-works/pi-ai/providers/openai";
// Pi's catalog of models OpenAI offers to ChatGPT accounts ("Codex with a ChatGPT account").
import { OPENAI_CODEX_MODELS } from "@earendil-works/pi-ai/providers/openai-codex.models";
// Pi's public entry point for embedding its OAuth flows in a bundle (what Pi's own standalone binary
// uses). It registers Pi's real login flows, e.g. "Sign in with ChatGPT"; node:http and node:crypto,
// which those flows need, are shimmed in build.mjs.
import { registerBunOAuthFlows } from "@earendil-works/pi-ai/bun-oauth";
import { bridge, bridgeCall, settle } from "./bridge.ts";
import { fetchEvents, nativeFetch } from "./native-fetch.ts";
import { PhoneSession, phoneSystemPrompt, pruneOldScreens } from "./phone-tools.ts";
import { SupervisorSession, supervisorSystemPrompt } from "./supervisor-tools.ts";
import { dispatch } from "./shims/http.ts";

declare const __PI_VERSION__: string;

// Every HTTP request Pi makes (model calls, OAuth token exchange and refresh) goes through Kotlin.
globalThis.fetch = nativeFetch as typeof fetch;

registerBunOAuthFlows();

/** Pi's in-memory store, mirrored to Kotlin (encrypted with the Android Keystore) after every write. */
class MirroredCredentialStore implements CredentialStore {
	private inner = new InMemoryCredentialStore();
	async load(saved: Record<string, Credential>) {
		for (const [id, c] of Object.entries(saved)) await this.inner.modify(id, async () => c);
	}
	private async mirror() {
		const all: Record<string, Credential> = {};
		for (const info of await this.inner.list()) {
			const c = await this.inner.read(info.providerId);
			if (c) all[info.providerId] = c;
		}
		bridge({ type: "credentials_changed", json: JSON.stringify(all) });
	}
	read: CredentialStore["read"] = (id, o) => this.inner.read(id, o);
	list: CredentialStore["list"] = (o) => this.inner.list(o);
	modify: CredentialStore["modify"] = async (id, fn, o) => {
		const r = await this.inner.modify(id, fn, o);
		await this.mirror();
		return r;
	};
	delete: CredentialStore["delete"] = async (id, o) => {
		await this.inner.delete(id, o);
		await this.mirror();
	};
}

const credentials = new MirroredCredentialStore();
const models = createModels({ credentials });
models.setProvider(openaiProvider());

let deviceId = "";
let agentName = "Pi";
let login: AbortController | undefined;
let promptSeq = 0;
const prompts = new Map<string, { resolve: (v: string) => void; reject: (e: unknown) => void }>();

// ---- auth -------------------------------------------------------------------------------------------

async function status() {
	const infos = await credentials.list();
	const providers = models.getProviders().map((p) => ({
		id: p.id,
		name: p.name,
		// The sign-in choices are Pi's own: its api-key method and its OAuth (subscription) method.
		methods: [
			p.auth.oauth && { type: "oauth", name: p.auth.oauth.name, label: p.auth.oauth.loginLabel, subscription: !!p.auth.oauth.isSubscription },
			p.auth.apiKey && { type: "api_key", name: p.auth.apiKey.name },
		].filter(Boolean),
		credential: infos.find((i) => i.providerId === p.id)?.type ?? null,
		models: modelsFor(p.id, infos.find((i) => i.providerId === p.id)?.type),
	}));
	bridge({ type: "status", providers });
}

const SUBSCRIPTION_IDS = new Set(Object.values(OPENAI_CODEX_MODELS).map((m: any) => m.id));

/** The chat models Pi knows for a provider, with the thinking levels Pi says each one supports. */
function modelsFor(providerId: string, credential?: string) {
	return models
		.getModels(providerId)
		// A ChatGPT sign-in only reaches the models OpenAI offers to ChatGPT accounts.
		.filter((m) => !(providerId === "openai" && credential === "oauth") || SUBSCRIPTION_IDS.has(m.id))
		.map((m) => ({
			id: m.id,
			name: m.name,
			thinkingLevels: getSupportedThinkingLevels(m),
			contextWindow: m.contextWindow,
			input: m.input,
		}));
}

const interaction = (signal: AbortSignal): AuthInteraction => ({
	signal,
	notify: (event) => bridge({ type: "auth_event", event }),
	prompt: (p: AuthPrompt) =>
		new Promise<string>((resolve, reject) => {
			const id = `p${++promptSeq}`;
			prompts.set(id, { resolve, reject });
			const { signal: promptSignal, ...shown } = p;
			bridge({ type: "auth_prompt", id, prompt: shown });
			const cancel = () => {
				if (!prompts.delete(id)) return;
				bridge({ type: "auth_prompt_closed", id });
				reject(new Error("Prompt cancelled"));
			};
			promptSignal?.addEventListener("abort", cancel, { once: true });
			signal.addEventListener("abort", cancel, { once: true });
		}),
});

async function startLogin(provider: string, type: "oauth" | "api_key") {
	login?.abort();
	const controller = new AbortController();
	login = controller;
	try {
		const c = await models.login(provider, type, interaction(controller.signal), { getDeviceId: () => deviceId, agentName });
		bridge({ type: "login_done", provider, credential: c.type });
	} catch (e: any) {
		bridge({ type: "login_failed", provider, message: controller.signal.aborted ? "cancelled" : String(e?.message ?? e) });
	} finally {
		if (login === controller) login = undefined;
		await status();
	}
}

// ---- sessions ---------------------------------------------------------------------------------------
// Every session is its own Pi Agent with its own transcript and phone tools, so sessions run side by
// side in this one runtime (one WebView for all of them keeps memory flat). Every message to Kotlin
// about a session carries its id.

/** tools: "phone" (operate the phone), "supervisor" (manage the other sessions), or none (chat). `phone: true` = "phone". */
type Config = {
	session: string; provider: string; modelId: string; thinkingLevel?: string; baseUrl?: string; systemPrompt?: string;
	tools?: "phone" | "supervisor"; phone?: boolean; messages?: AgentMessage[];
};

interface Session {
	id: string;
	agent: Agent;
	phone?: PhoneSession;
	supervisor?: SupervisorSession;
	running: boolean;
}

const sessions = new Map<string, Session>();

const need = (id: string) => {
	const s = sessions.get(id);
	if (!s) throw new Error(`no session ${id}; configure it first`);
	return s;
};

// Pi events carry whole message objects; forward a compact form that Kotlin can render and log.
function forward(session: string, event: AgentEvent) {
	const send = (m: Record<string, unknown>) => bridge({ ...m, session });
	switch (event.type) {
		case "message_update": {
			const e = event.assistantMessageEvent;
			if (e.type === "text_delta") send({ type: "text_delta", delta: e.delta });
			break;
		}
		case "message_end": {
			const m = event.message as any;
			if (m.role === "assistant") {
				const text = (m.content ?? []).filter((c: any) => c.type === "text").map((c: any) => c.text).join("");
				send({
					type: "assistant_end", text, model: m.model, provider: m.provider, api: m.api,
					stopReason: m.stopReason, errorMessage: m.errorMessage, usage: m.usage,
				});
			} else {
				send({ type: "message_end", role: m.role });
				// The user's message is in the transcript now: save, so a crash before the turn ends keeps it.
				if (m.role === "user") saveTranscript(session);
			}
			break;
		}
		case "tool_execution_start":
			send({ type: "tool_start", tool: event.toolName, args: event.args });
			break;
		case "tool_execution_end": {
			const r = (event as any).result;
			const out = (r?.content ?? []).filter((c: any) => c.type === "text").map((c: any) => c.text).join("");
			send({ type: "tool_end", tool: event.toolName, isError: !!(event as any).isError, output: out.slice(0, 300) });
			break;
		}
		case "turn_end":
			send({ type: "turn_end" });
			saveTranscript(session);
			break;
		default:
			send({ type: event.type });
	}
}

/**
 * The session's transcript for Kotlin to save, small enough to write after every turn: screenshots
 * become a note, and screen reads older than the latest two become a note (as the model already sees
 * them, see pruneOldScreens). Restoring it gives Pi the same conversation the model was having.
 */
export function savableTranscript(messages: AgentMessage[]) {
	const screens = messages
		.map((m: any, i) => (m.role === "toolResult" && (m.toolName === "read_screen" || m.toolName === "screenshot") ? i : -1))
		.filter((i) => i >= 0);
	const old = new Set(screens.slice(0, Math.max(0, screens.length - 2)));
	return messages.flatMap((m: any, i) => {
		if (m.role === "system") return [];
		if (old.has(i)) return { ...m, content: [{ type: "text", text: `[earlier ${m.toolName} omitted; the screen has changed since]` }] };
		if (!Array.isArray(m.content) || !m.content.some((c: any) => c.type === "image")) return m;
		return { ...m, content: m.content.map((c: any) => (c.type === "image" ? { type: "text", text: "[screenshot not kept]" } : c)) };
	});
}

function saveTranscript(id: string) {
	const s = sessions.get(id);
	if (s) bridge({ type: "transcript", session: id, messages: savableTranscript(s.agent.state.messages) });
}

/**
 * Before every tool call: wait while the session is paused (Kotlin answers "gate" when it may go on),
 * then the phone session's own checks (confirmation of risky taps, step limit).
 */
const beforeToolCall = (s: Session) => async (ctx: BeforeToolCallContext) => {
	const go = await bridgeCall("gate", { session: s.id });
	if (go === false) return { block: true, terminate: true, reason: "Stopped by the user." };
	return (await s.supervisor?.limit(ctx)) ?? s.phone?.confirmRiskyTap(ctx);
};

async function configure(cfg: Config) {
	const found = models.getModel(cfg.provider, cfg.modelId);
	if (!found) throw new Error(`Pi has no model ${cfg.provider}/${cfg.modelId}`);
	const prev = sessions.get(cfg.session);
	prev?.agent.abort();
	// baseUrl override exists only so a local mock server can show exactly what Pi sends.
	const model: Model<any> = cfg.baseUrl ? { ...found, baseUrl: cfg.baseUrl } : found;
	// Pi maps the level onto what this model supports (e.g. "max" → the highest it has).
	const thinkingLevel = clampThinkingLevel(model, (cfg.thinkingLevel ?? "off") as any);
	const tools = cfg.tools ?? (cfg.phone ? "phone" : undefined);
	const phone = tools === "phone" ? new PhoneSession(cfg.session) : undefined;
	const supervisor = tools === "supervisor" ? new SupervisorSession(cfg.session) : undefined;
	// The conversation goes on across a change of model or tools; a saved one is restored as given.
	// System messages are left out: Pi puts the current system prompt and tools in front (initialState).
	const history = (cfg.messages ?? prev?.agent.state.messages ?? []).filter((m: any) => m.role !== "system");
	const session: Session = { id: cfg.session, agent: undefined as unknown as Agent, phone, supervisor, running: false };
	session.agent = new Agent({
		initialState: {
			messages: history,
			systemPrompt: cfg.systemPrompt ?? (phone ? phoneSystemPrompt(agentName) : supervisor ? supervisorSystemPrompt(agentName)
				: `You are ${agentName}, a helpful assistant on the user's Android phone. Reply in the user's language.`),
			model,
			thinkingLevel: thinkingLevel as any,
			tools: phone?.tools ?? supervisor?.tools ?? [],
		},
		// Pi's hook: the pause gate, then risky taps wait for the user's OK.
		beforeToolCall: beforeToolCall(session),
		// Pi's hook to shape the context per model call: drop old screens (see pruneOldScreens).
		transformContext: phone ? pruneOldScreens : undefined,
		// Pi resolves auth per request from the credential store (api key, or ChatGPT token refreshed as needed).
		streamFn: models.streamSimple.bind(models),
	});
	session.agent.subscribe((e) => forward(cfg.session, e));
	sessions.set(cfg.session, session);
	const auth = await credentials.read(cfg.provider);
	bridge({ type: "configured", session: cfg.session, provider: model.provider, model: model.id, api: model.api, baseUrl: model.baseUrl, auth: auth?.type ?? null, thinkingLevel, messages: history.length });
}

async function prompt(id: string, text: string) {
	const s = need(id);
	if (s.running) throw new Error("this session is already working");
	s.running = true;
	s.phone?.reset();
	s.supervisor?.reset();
	try {
		await s.agent.prompt(text);
	} finally {
		s.running = false;
		saveTranscript(id);
		bridge({ type: "idle", session: id });
	}
}

function dispose(id: string) {
	const s = sessions.get(id);
	if (!s) return;
	s.agent.abort();
	sessions.delete(id);
}

// ---- entry points for Kotlin ----------------------------------------------------------------------

function guard(id: string, fn: () => Promise<void>, session?: string) {
	fn().catch((e) => bridge({ type: "error", id, session, message: String(e?.message ?? e) }));
}
const arg = (json: string) => JSON.parse(json);

(globalThis as any).PiHost = {
	init: (json: string) =>
		guard("init", async () => {
			const a = arg(json);
			deviceId = a.deviceId;
			agentName = a.agentName ?? agentName;
			if (a.credentials) await credentials.load(JSON.parse(a.credentials));
			await status();
		}),
	status: () => guard("status", status),
	login: (json: string) => guard("login", () => startLogin(arg(json).provider, arg(json).type)),
	cancelLogin: () => login?.abort(),
	answer: (json: string) => {
		const a = arg(json);
		const p = prompts.get(a.id);
		if (!p) return;
		prompts.delete(a.id);
		a.cancel ? p.reject(new Error("cancelled")) : p.resolve(a.value);
	},
	logout: (json: string) => guard("logout", async () => { await models.logout(arg(json).provider); await status(); }),
	configure: (json: string) => { const a = arg(json); guard("configure", () => configure(a), a.session); },
	prompt: (json: string) => { const a = arg(json); guard("prompt", () => prompt(a.session, a.text), a.session); },
	abort: (json: string) => sessions.get(arg(json).session)?.agent.abort(),
	dispose: (json: string) => dispose(arg(json).session),
	/** Sessions alive in this runtime (after a renderer restart this is empty: Kotlin reconfigures). */
	sessions: () => bridge({ type: "sessions", ids: [...sessions.keys()], running: [...sessions.values()].filter((s) => s.running).map((s) => s.id) }),
	reply: (json: string) => { const a = arg(json); settle(a.id, a.ok, a.value); },
	fetchHead: (json: string) => { const a = arg(json); fetchEvents.head(a.id, a.status, a.statusText, a.headers); },
	fetchChunk: (id: string, b64: string) => fetchEvents.chunk(id, b64),
	fetchEnd: (id: string) => fetchEvents.end(id),
	fetchError: (id: string, message: string) => fetchEvents.error(id, message),
	http: (json: string) => { const a = arg(json); dispatch(a.port, a.id, a.url, a.method); },
};
bridge({ type: "ready", pi: __PI_VERSION__, userAgent: navigator.userAgent });
