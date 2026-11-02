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
	createProvider,
	envApiKeyAuth,
	InMemoryCredentialStore,
	isContextOverflow,
	type Model,
} from "@earendil-works/pi-ai";
import { openaiProvider } from "@earendil-works/pi-ai/providers/openai";
import { deepseekProvider } from "@earendil-works/pi-ai/providers/deepseek";
import { anthropicProvider } from "@earendil-works/pi-ai/providers/anthropic";
import { googleProvider } from "@earendil-works/pi-ai/providers/google";
import { openrouterProvider } from "@earendil-works/pi-ai/providers/openrouter";
import { xaiProvider } from "@earendil-works/pi-ai/providers/xai";
import { moonshotaiProvider } from "@earendil-works/pi-ai/providers/moonshotai";
import { moonshotaiCnProvider } from "@earendil-works/pi-ai/providers/moonshotai-cn";
import { mistralProvider } from "@earendil-works/pi-ai/providers/mistral";
import { openAICompletionsApi } from "@earendil-works/pi-ai/api/openai-completions.lazy";
import { opencodeProvider } from "@earendil-works/pi-ai/providers/opencode";
import { opencodeGoProvider } from "@earendil-works/pi-ai/providers/opencode-go";
// Pi's catalog of models OpenAI offers to ChatGPT accounts ("Codex with a ChatGPT account").
import { OPENAI_CODEX_MODELS } from "@earendil-works/pi-ai/providers/openai-codex.models";
// Pi's public entry point for embedding its OAuth flows in a bundle (what Pi's own standalone binary
// uses). It registers Pi's real login flows, e.g. "Sign in with ChatGPT"; node:http and node:crypto,
// which those flows need, are shimmed in build.mjs.
import { registerBunOAuthFlows } from "@earendil-works/pi-ai/bun-oauth";
import { bridge, bridgeCall, settle } from "./bridge.ts";
import { fetchEvents, nativeFetch } from "./native-fetch.ts";
import { PhoneSession, phoneSystemPrompt, pruneOldScreens, pruneScreens } from "./phone-tools.ts";
import { SupervisorSession, supervisorSystemPrompt } from "./supervisor-tools.ts";
import { bashPrompt, bashTool } from "./bash-tool.ts";
import { compact, needsCompaction } from "./compact.ts";
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
// The providers offered here, in the order the app lists them. Adding one is a line here; the app
// builds its sign-in screens from what status() reports (Pi's provider name and auth methods).
// Each needs a model that reads images (phone tasks send screenshots), and nothing beyond a key or
// a tested sign-in (not Bedrock, Vertex, Azure or Cloudflare, which need cloud accounts).
for (const p of [
	openaiProvider(), deepseekProvider(), opencodeProvider(), opencodeGoProvider(), anthropicProvider(), googleProvider(),
	openrouterProvider(), xaiProvider(), moonshotaiProvider(), moonshotaiCnProvider(), mistralProvider(),
]) models.setProvider(p);
/**
 * Providers whose OAuth sign-in is offered. Each flow must be tried in the WebView (callback server
 * shim, Chrome hand-off) and the provider must allow a subscription to be used from this app;
 * the others offer their API key only.
 */
const OAUTH_PROVIDERS = new Set(["openai"]);

// ---- custom providers -------------------------------------------------------------------------------
// The user's own OpenAI-compatible endpoints (vLLM, Ollama, relays…), defined in the app and sent here
// with init and on every change. Built the way Pi builds its own providers (and its CLI's models.json).

interface CustomModelConfig {
	id: string;
	/** Tokens; 0/absent = 128K. The app fills it from the server's model list or Pi's catalog (lookupModels). */
	contextWindow?: number;
	/** Whether it reads images (phone tasks send screenshots); absent = the provider's `images`. */
	images?: boolean;
}

interface CustomProviderConfig {
	id: string;
	name: string;
	baseUrl: string;
	/** Model ids (older app versions) or per-model settings. */
	models: (string | CustomModelConfig)[];
	/** Default for models that don't say whether they read images. */
	images: boolean;
}

const DEFAULT_CONTEXT = 128_000;

/** Aggregators list other vendors' models under their own terms (prices, limits): a vendor's own entry wins. */
const AGGREGATORS = new Set(["openrouter", "opencode", "opencode-go"]);

/**
 * A model id as relays tend to vary it: lowercase, no vendor prefix ("anthropic/…"), no ":free"-style tag,
 * and "." or "_" written as "-" (claude-sonnet-5.5 vs claude-sonnet-5-5).
 */
const normalizeModelId = (id: string) =>
	id.toLowerCase().replace(/^.*\//, "").replace(/:.*$/, "").replace(/^~/, "").replace(/[._]/g, "-");

const EFFORT_SUFFIX = /-(minimal|low|medium|high|xhigh|max|thinking|nothinking|no-thinking|think|reasoning)$/;

/**
 * What Pi's catalog knows about models with these ids (a relay usually passes vendors' model names
 * through): context window, whether they read images, and which catalog entry matched. Unknown ids are left out.
 */
function lookupModels(ids: string[]) {
	const builtIn = models.getProviders().filter((p) => !customIds.has(p.id));
	const ordered = [...builtIn.filter((p) => !AGGREGATORS.has(p.id)), ...builtIn.filter((p) => AGGREGATORS.has(p.id))];
	const out: Record<string, { contextWindow: number; images: boolean; match: string }> = {};
	const find = (id: string, want: string) => {
		for (const p of ordered) {
			const m = models.getModels(p.id).find((x) => x.id === id) ?? models.getModels(p.id).find((x) => normalizeModelId(x.id) === want);
			if (m) return { contextWindow: m.contextWindow, images: m.input.includes("image"), match: `${p.id}/${m.id}` };
		}
	};
	for (const id of ids) {
		const want = normalizeModelId(id);
		// Relays often name a reasoning-effort variant with a suffix (gemini-3.8-flash-high): same model, same window.
		const found = find(id, want) ?? find(id, want.replace(EFFORT_SUFFIX, ""));
		if (found) out[id] = found;
	}
	return out;
}

const customIds = new Set<string>();

function customProvider(c: CustomProviderConfig) {
	return createProvider({
		id: c.id,
		name: c.name,
		baseUrl: c.baseUrl,
		auth: { apiKey: envApiKeyAuth(`${c.name} API key`, []) },
		models: c.models.map((x) => {
			const m: CustomModelConfig = typeof x === "string" ? { id: x } : x;
			return {
			id: m.id,
			name: m.id,
			api: "openai-completions" as const,
			provider: c.id,
			baseUrl: c.baseUrl,
			reasoning: false,
			input: (m.images ?? c.images) ? (["text", "image"] as const) : (["text"] as const),
			cost: { input: 0, output: 0, cacheRead: 0, cacheWrite: 0 },
			contextWindow: m.contextWindow || DEFAULT_CONTEXT,
			maxTokens: 16_384,
			// Many OpenAI-compatible servers reject OpenAI's newer "developer" role and "store" field.
			compat: { supportsStore: false, supportsDeveloperRole: false },
			};
		}),
		api: openAICompletionsApi(),
	});
}

/** Make Pi's custom providers exactly [list]; a removed one loses its saved key too. */
async function setCustomProviders(list: CustomProviderConfig[]) {
	const keep = new Set(list.map((c) => c.id));
	for (const id of customIds) {
		if (keep.has(id)) continue;
		models.deleteProvider(id);
		await credentials.delete(id);
		customIds.delete(id);
	}
	for (const c of list) {
		models.setProvider(customProvider(c));
		customIds.add(c.id);
	}
}

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
			p.auth.oauth && OAUTH_PROVIDERS.has(p.id) && { type: "oauth", name: p.auth.oauth.name, label: p.auth.oauth.loginLabel, subscription: !!p.auth.oauth.isSubscription },
			p.auth.apiKey && { type: "api_key", name: p.auth.apiKey.name },
		].filter(Boolean),
		credential: infos.find((i) => i.providerId === p.id)?.type ?? null,
		custom: customIds.has(p.id),
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
	if (type === "oauth" && !OAUTH_PROVIDERS.has(provider)) throw new Error(`OAuth sign-in to ${provider} is not offered here`);
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
				// A user message's text: Kotlin marks a steered message as delivered when Pi takes it.
				const text = m.role === "user"
					? typedText(m.content)
					: undefined;
				send({ type: "message_end", role: m.role, text });
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
	return pruneScreens(messages, 2).flatMap((m: any) => {
		if (m.role === "system") return [];
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
			systemPrompt: cfg.systemPrompt ?? (supervisor ? supervisorSystemPrompt(agentName)
				: (phone ? phoneSystemPrompt(agentName)
					: `You are ${agentName}, a helpful assistant on the user's Android phone. Reply in the user's language.`) + "\n" + bashPrompt),
			model,
			thinkingLevel: thinkingLevel as any,
			// Chat and phone sessions get bash (Termux); the supervisor only manages sessions.
			tools: supervisor ? supervisor.tools : [...(phone?.tools ?? []), bashTool(cfg.session)],
		},
		// Forwarded to providers for routing and prompt caching (OpenCode requires it as x-opencode-session).
		sessionId: cfg.session,
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

/** Content blocks the app attaches to a message (Kotlin Attachments.content): text, or image. */
type Block = { type: "text"; text: string } | { type: "image"; data: string; mimeType: string };

/**
 * The user's message: typed [text], then attached files. A model that can't read images gets a note
 * in place of each image, so it can say so instead of failing.
 */
function userMessage(s: Session, text: string, attached: Block[] = []): AgentMessage {
	const images = s.agent.state.model.input.includes("image");
	const content: Block[] = [];
	if (text) content.push({ type: "text", text });
	for (const b of attached) {
		if (b.type === "image" && !images) content.push({ type: "text", text: "<attachment>(an image: the model in use can't see images; tell the user to pick one that can)</attachment>" });
		else content.push(b);
	}
	if (content.length === 0) content.push({ type: "text", text: "(empty message)" });
	return { role: "user", content, timestamp: Date.now() } as AgentMessage;
}

/** What the user typed in a user message: its text blocks, without the attached files (see userMessage). */
const typedText = (content: any) =>
	typeof content === "string" ? content
		: (content ?? []).filter((c: any) => c.type === "text" && !c.text.startsWith("<attachment")).map((c: any) => c.text).join("");

async function prompt(id: string, text: string, attached?: Block[]) {
	const s = need(id);
	if (s.running) throw new Error("this session is already working");
	s.running = true;
	s.phone?.reset();
	s.supervisor?.reset();
	try {
		if (needsCompaction(s.agent.state.messages, s.agent.state.model.contextWindow)) await compactSession(s);
		if (attached?.length) await s.agent.prompt(userMessage(s, text, attached));
		else await s.agent.prompt(text);
		// Pi takes steered messages after each turn, also the last one; one that came in as the run was
		// ending is still queued: go on with it, so nothing the user typed is left behind.
		while (s.agent.hasQueuedMessages()) await s.agent.continue();
		// The conversation outgrew the model after all (a long task, an estimate off): compact once, go on.
		if (overflowed(s) && (await compactSession(s, true))) {
			await s.agent.continue();
			while (s.agent.hasQueuedMessages()) await s.agent.continue();
		}
	} finally {
		s.running = false;
		saveTranscript(id);
		bridge({ type: "idle", session: id });
	}
}

function overflowed(s: Session) {
	const last: any = s.agent.state.messages.at(-1);
	return last?.role === "assistant" && last.stopReason === "error" && isContextOverflow(last, s.agent.state.model.contextWindow);
}

/**
 * Replace the older part of the session's conversation with a summary (see compact.ts). [dropFailed]: the
 * last message is a reply that failed because the context was too long; it goes. False if nothing changed.
 */
async function compactSession(s: Session, dropFailed = false): Promise<boolean> {
	const before = s.agent.state.messages;
	const messages = dropFailed ? before.slice(0, -1) : before;
	// After a failed reply the task under way is kept word for word; before a new task all may be summarised.
	const after = await compact(messages, s.agent.state.model, models.completeSimple.bind(models), dropFailed).catch((e) => {
		console.warn(`compaction failed for ${s.id}: ${e}`);
		return null;
	});
	if (!after) return false;
	s.agent.state.messages = after;
	bridge({ type: "compacted", session: s.id, before: before.length, after: after.length });
	saveTranscript(s.id);
	return true;
}

/**
 * The user's message to a session that is working: Pi adds it to the conversation after the current
 * turn (Agent.steer), so the model reads it before its next step. If the run has already ended,
 * Kotlin is told (steer_late) and sends it as a new message.
 */
function steer(id: string, text: string, attached?: Block[]) {
	const s = sessions.get(id);
	if (!s?.running) {
		// Kotlin still has the attachments (on its queued message) and sends them with the text.
		bridge({ type: "steer_late", session: id, text });
		return;
	}
	s.agent.steer(userMessage(s, text, attached));
}

/** Stop: drop what the user queued too, so the run doesn't go on with it. */
function abort(id: string) {
	const s = sessions.get(id);
	if (!s) return;
	s.agent.clearAllQueues();
	s.agent.abort();
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
			// Before the first status, so a custom provider in use is still there when Kotlin picks one.
			await setCustomProviders(a.customProviders ?? []);
			await status();
		}),
	status: () => guard("status", status),
	/** Synchronous, for evaluateJavascript's result: Pi's catalog entries for these model ids (see lookupModels). */
	lookupModels: (json: string) => JSON.stringify(lookupModels(arg(json).ids ?? [])),
	/** The app's custom providers changed; `key` (optional) is a new API key for `provider`. */
	setCustomProviders: (json: string) =>
		guard("setCustomProviders", async () => {
			const a = arg(json);
			await setCustomProviders(a.providers ?? []);
			if (a.provider && a.key) await credentials.modify(a.provider, async () => ({ type: "api_key", key: a.key }));
			await status();
		}),
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
	prompt: (json: string) => { const a = arg(json); guard("prompt", () => prompt(a.session, a.text, a.content), a.session); },
	steer: (json: string) => { const a = arg(json); guard("steer", async () => steer(a.session, a.text, a.content), a.session); },
	abort: (json: string) => abort(arg(json).session),
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
