// End-to-end tests of the shipped Pi bundle: Pi's own login, Agent, OpenAI adapter and our adapter
// code, talking to a mock OpenAI server through the same bridge protocol Kotlin implements.
import assert from "node:assert/strict";
import { after, before, test } from "node:test";
import { MOCK_REPLY, startMock } from "../../tools/mock-openai.mjs";
import { buildBundle, startHost } from "./harness.mjs";

let mock;
before(async () => {
	buildBundle();
	mock = await startMock(0, { log: () => {}, delayMs: 1 });
});
after(() => mock.close());

const apiKeyCredentials = JSON.stringify({ openai: { type: "api_key", key: "sk-test" } });

test("starts and offers Pi's two OpenAI sign-in methods", async () => {
	const host = await startHost();
	const status = await host.waitFor("status");
	const openai = status.providers.find((p) => p.id === "openai");
	assert.deepEqual(openai.methods.map((m) => m.type), ["oauth", "api_key"]);
	assert.equal(openai.methods[0].subscription, true);
	assert.equal(openai.credential, null);
	assert.ok(openai.models.length > 0, "Pi's model catalog is bundled");
	assert.ok(openai.models.every((m) => Array.isArray(m.thinkingLevels)), "each model lists Pi's thinking levels");
});

test("offers Pi's DeepSeek provider with its API-key sign-in and models", async () => {
	const host = await startHost();
	const status = await host.waitFor("status");
	const deepseek = status.providers.find((p) => p.id === "deepseek");
	assert.deepEqual(deepseek.methods.map((m) => m.type), ["api_key"]);
	assert.ok(deepseek.models.some((m) => m.id === "deepseek-flash"), "the app's default DeepSeek model is in Pi's catalog");
	host.call("login", { provider: "deepseek", type: "api_key" });
	const prompt = await host.waitFor("auth_prompt");
	host.call("answer", { id: prompt.id, value: "sk-ds" });
	await host.waitFor("login_done", (m) => m.provider === "deepseek");
	assert.deepEqual(JSON.parse(host.state.credentials).deepseek, { type: "api_key", key: "sk-ds" });
});

test("API-key sign-in goes through Pi's own login and is mirrored to Kotlin", async () => {
	const host = await startHost();
	host.call("login", { provider: "openai", type: "api_key" });
	const prompt = await host.waitFor("auth_prompt");
	assert.equal(prompt.prompt.type, "secret");
	host.call("answer", { id: prompt.id, value: "sk-test" });
	await host.waitFor("login_done");
	assert.deepEqual(JSON.parse(host.state.credentials).openai, { type: "api_key", key: "sk-test" });
	const status = await host.waitFor("status", (s) => s.providers[0].credential === "api_key");
	assert.ok(status);
});

test("saved credentials are restored on start", async () => {
	const host = await startHost({ credentials: apiKeyCredentials });
	const status = host.messages.findLast((m) => m.type === "status");
	assert.equal(status.providers.find((p) => p.id === "openai").credential, "api_key");
});

test("a prompt goes through Pi's Agent and OpenAI adapter, via native fetch, and streams back", async () => {
	const host = await startHost({ credentials: apiKeyCredentials });
	const before = mock.requests.length;
	host.call("configure", { session: "s1", provider: "openai", modelId: "gpt-5.4-mini", thinkingLevel: "low", baseUrl: mock.url });
	await host.waitFor("configured");
	host.call("prompt", { session: "s1", text: "你好" });
	await host.waitFor("idle");

	const end = host.messages.find((m) => m.type === "assistant_end");
	assert.equal(end.errorMessage, undefined);
	assert.equal(end.stopReason, "stop");
	const text = host.messages.filter((m) => m.type === "text_delta").map((m) => m.delta).join("");
	assert.equal(text, MOCK_REPLY);
	assert.equal(end.api, "openai-responses");

	const req = mock.requests[before];
	assert.equal(req.headers.authorization, "Bearer sk-test");
	const body = JSON.parse(req.body);
	assert.equal(body.model, "gpt-5.4-mini");
	assert.match(JSON.stringify(body.input), /你好/);
	assert.ok(host.fetches.some((f) => f.url === `${mock.url}/responses`), "the request went through the native-fetch bridge");
});

test("ChatGPT sign-in starts Pi's OAuth flow on the loopback port and can be cancelled", async () => {
	const host = await startHost();
	host.call("login", { provider: "openai", type: "oauth" });
	const ev = await host.waitFor("auth_event", (m) => m.event.type === "auth_url");
	const url = new URL(ev.event.url);
	assert.equal(url.host, "auth.openai.com");
	assert.equal(url.searchParams.get("agent_name_hint"), "WeiAssist");
	assert.equal(url.searchParams.get("redirect_uri"), "http://127.0.0.1:1455/auth/callback");
	assert.equal(url.searchParams.get("code_challenge_method"), "S256");
	assert.ok(host.listening.has(1455));

	// A forged callback (wrong state) is answered by Pi's own handler with an error page.
	host.call("http", { port: 1455, id: "r1", url: "/auth/callback?code=x&state=wrong&client_id=y", method: "GET" });
	const res = await host.waitFor("http_response", (m) => m.id === "r1");
	assert.equal(res.status, 400);
	assert.match(res.body, /state mismatch/);

	host.call("cancelLogin");
	const failed = await host.waitFor("login_failed");
	assert.equal(failed.message, "cancelled");
	await host.waitFor("http_close");
	assert.equal(host.listening.size, 0);
});

test("a model Pi doesn't know is reported as an error, not a crash", async () => {
	const host = await startHost({ credentials: apiKeyCredentials });
	host.call("configure", { session: "s1", provider: "openai", modelId: "no-such-model", baseUrl: mock.url });
	const err = await host.waitFor("error");
	assert.match(err.message, /no model openai\/no-such-model/);
});

// ---- phone tools (Pi AgentTools served by Kotlin's "phone" host call) ------------------------------

const phoneRun = async (host, script) => {
	host.call("configure", { session: "s1", provider: "openai", modelId: "gpt-5.4-mini", baseUrl: mock.url, phone: true });
	await host.waitFor("configured");
	host.call("prompt", { session: "s1", text: `do it [[script:${script}]]` });
	await host.waitFor("idle", () => true, 15_000);
};

test("tool arguments reach Kotlin intact and never clobber the bridge request id", async () => {
	const calls = [];
	const host = await startHost({
		credentials: apiKeyCredentials,
		hostCalls: { phone: (m) => { calls.push(m); return m.action === "tap" ? `tapped [${m.args.id}]` : "app: x"; } },
	});
	await phoneRun(host, "tap");
	assert.deepEqual(calls.map((c) => c.action), ["tap", "read_screen"]); // the action, then the screen it left
	assert.equal(calls[0].action, "tap");
	assert.deepEqual(calls[0].args, { id: 51 }); // the element id travels inside args …
	assert.match(calls[0].id, /^k\d+$/); // … so the bridge's own request id survives (the Gmail hang)
	const end = host.messages.find((m) => m.type === "tool_end" && m.tool === "tap");
	assert.equal(end.isError, false);
	assert.equal(end.output, "tapped [51]\n\nScreen now:\napp: x");
});

const sendScreen = 'app: com.google.android.gm\n[1] EditText "Subject" editable\n[2] ImageButton (Send) clickable\n';

test("a tap on Send waits for the user's confirmation (Pi's beforeToolCall)", async () => {
	const confirms = [];
	const taps = [];
	const host = await startHost({
		credentials: apiKeyCredentials,
		hostCalls: {
			phone: (m) => (m.action === "read_screen" ? sendScreen : (taps.push(m.args.id), "tapped")),
			confirm: (m) => { confirms.push(m); return true; },
		},
	});
	await phoneRun(host, "send");
	assert.equal(confirms.length, 1);
	assert.equal(confirms[0].label, "Send");
	assert.deepEqual(taps, [2], "allowed: the tap happened");
});

test("declining the confirmation blocks the tap and stops the task", async () => {
	const taps = [];
	const host = await startHost({
		credentials: apiKeyCredentials,
		hostCalls: {
			phone: (m) => (m.action === "read_screen" ? sendScreen : (taps.push(m.args.id), "tapped")),
			confirm: () => false,
		},
	});
	await phoneRun(host, "send");
	assert.deepEqual(taps, [], "declined: Kotlin was never asked to tap");
	const end = host.messages.find((m) => m.type === "tool_end" && m.tool === "tap");
	assert.equal(end.isError, true);
});

test("a coordinate tap on a buy button also needs confirmation", async () => {
	const confirms = [];
	const taps = [];
	const host = await startHost({
		credentials: apiKeyCredentials,
		hostCalls: {
			phone: (m) => (m.action === "label_at" ? "立即购买" : (taps.push(m.args), "tapped")),
			confirm: (m) => { confirms.push(m.label); return false; },
		},
	});
	await phoneRun(host, "buy_at");
	assert.deepEqual(confirms, ["立即购买"]);
	assert.deepEqual(taps, [], "declined: no tap");
});

test("old screens are pruned from the model's context, the latest two are kept", async () => {
	let n = 0;
	const host = await startHost({
		credentials: apiKeyCredentials,
		hostCalls: { phone: () => `app: x\n[1] TextView "screen ${++n}"` },
	});
	const before = mock.requests.length;
	await phoneRun(host, "screens");
	const last = JSON.parse(mock.requests.at(-1).body);
	const outputs = last.input.filter((i) => i.type === "function_call_output").map((i) => JSON.stringify(i.output));
	assert.equal(outputs.length, 4);
	assert.match(outputs[0], /omitted/);
	assert.match(outputs[1], /omitted/);
	assert.match(outputs[2], /screen 3/);
	assert.match(outputs[3], /screen 4/);
	assert.ok(mock.requests.length - before >= 5);
});

test("actions return the screen they leave; older ones keep their result, not the screen", async () => {
	let n = 0;
	const host = await startHost({
		credentials: apiKeyCredentials,
		hostCalls: { phone: (m) => (m.action === "read_screen" ? `app: x\n[1] TextView "screen ${++n}"` : `did ${m.action}`) },
	});
	await phoneRun(host, "actions");
	const outputs = JSON.parse(mock.requests.at(-1).body).input.filter((i) => i.type === "function_call_output").map((i) => JSON.stringify(i.output));
	assert.equal(outputs.length, 3);
	assert.match(outputs[0], /did tap.*screen omitted/);
	assert.doesNotMatch(outputs[0], /screen 1/);
	assert.match(outputs[1], /did press_key.*Screen now:.*screen 2/);
	assert.match(outputs[2], /did swipe.*Screen now:.*screen 3/);
});

test("notes stay in the model's context after their screen is pruned, and never reach Kotlin", async () => {
	const actions = [];
	let n = 0;
	const host = await startHost({
		credentials: apiKeyCredentials,
		hostCalls: { phone: (m) => { actions.push(m.action); return `app: x\n[1] TextView "screen ${++n}"`; } },
	});
	await phoneRun(host, "notes");
	const input = JSON.parse(mock.requests.at(-1).body).input;
	const note = input.find((i) => i.type === "function_call" && i.name === "note");
	assert.match(note.arguments, /from Ann, ship by Friday/);
	const outputs = input.filter((i) => i.type === "function_call_output").map((i) => JSON.stringify(i.output));
	assert.match(outputs[0], /omitted/); // screen 1, which the note was taken from
	assert.match(outputs[1], /noted/);
	assert.deepEqual(actions, ["read_screen", "read_screen", "read_screen", "read_screen"]);
});

// ---- sessions ---------------------------------------------------------------------------------------

const configure = async (host, session, extra = {}) => {
	host.call("configure", { session, provider: "openai", modelId: "gpt-5.4-mini", baseUrl: mock.url, ...extra });
	return host.waitFor("configured", (m) => m.session === session);
};

test("two sessions run at the same time, each with its own transcript and tools", async () => {
	const phoneCalls = [];
	const host = await startHost({
		credentials: apiKeyCredentials,
		hostCalls: { phone: (m) => { phoneCalls.push(m.session); return `app: x\n[1] TextView "s"`; } },
	});
	await configure(host, "a", { phone: true });
	await configure(host, "b");
	host.call("prompt", { session: "a", text: "do it [[script:screens]]" });
	host.call("prompt", { session: "b", text: "你好" });
	await Promise.all([host.waitFor("idle", (m) => m.session === "a", 15_000), host.waitFor("idle", (m) => m.session === "b", 15_000)]);
	assert.ok(phoneCalls.length >= 4);
	assert.ok(phoneCalls.every((s) => s === "a"), "phone requests name their session");
	assert.ok(!host.messages.some((m) => m.session === "b" && m.type === "tool_start"), "the chat session has no phone tools");
	const ta = host.messages.filter((m) => m.type === "transcript" && m.session === "a").at(-1);
	const tb = host.messages.filter((m) => m.type === "transcript" && m.session === "b").at(-1);
	assert.ok(ta.messages.some((m) => m.role === "toolResult"));
	assert.deepEqual(tb.messages.map((m) => m.role), ["user", "assistant"]);
	assert.ok(!JSON.stringify(tb).includes("script:screens"), "transcripts don't leak across sessions");
	assert.ok(host.messages.every((m) => !["text_delta", "tool_start", "idle", "transcript"].includes(m.type) || m.session), "session events are tagged");
});

test("a saved transcript is restored into a new runtime, and the model sees it", async () => {
	const host = await startHost({ credentials: apiKeyCredentials });
	await configure(host, "s");
	host.call("prompt", { session: "s", text: "remember the number 4711" });
	await host.waitFor("idle", (m) => m.session === "s");
	const saved = host.messages.filter((m) => m.type === "transcript").at(-1).messages;
	assert.ok(saved.every((m) => m.role !== "system"), "the system prompt is not saved; it is rebuilt");

	const fresh = await startHost({ credentials: apiKeyCredentials });
	const conf = await configure(fresh, "s", { messages: saved });
	assert.equal(conf.messages, 2);
	fresh.call("prompt", { session: "s", text: "which number?" });
	await fresh.waitFor("idle", (m) => m.session === "s");
	const body = JSON.stringify(JSON.parse(mock.requests.at(-1).body).input);
	assert.match(body, /remember the number 4711/);
	assert.match(body, /which number\?/);
});

// ---- compaction -------------------------------------------------------------------------------------

/** A saved conversation whose last reply reported [input] tokens, as a long one would. */
const longHistory = (input) => [
	{ role: "user", content: [{ type: "text", text: "old question: I like llamas" }], timestamp: 1 },
	{ role: "assistant", content: [{ type: "text", text: "noted, llamas" }], api: "openai-responses", provider: "openai", model: "gpt-5.4-mini",
		usage: { input, output: 10, cacheRead: 0, cacheWrite: 0, totalTokens: input + 10, cost: { input: 0, output: 0, cacheRead: 0, cacheWrite: 0, total: 0 } },
		stopReason: "stop", timestamp: 2 },
];

test("a conversation near the context window is compacted before the next task", async () => {
	const host = await startHost({ credentials: apiKeyCredentials });
	await configure(host, "c", { messages: longHistory(10_000_000) });
	const before = mock.requests.length;
	host.call("prompt", { session: "c", text: "what do I like?" });
	await host.waitFor("idle", (m) => m.session === "c");
	const sent = mock.requests.slice(before).map((r) => r.body);
	assert.equal(sent.length, 2, "one summary request, then the task");
	assert.match(sent[0], /You compact a conversation/);
	assert.match(sent[0], /old question: I like llamas/);
	assert.match(sent[1], /SUMMARY: the user likes llamas/);
	assert.doesNotMatch(sent[1], /old question/);
	assert.match(sent[1], /what do I like\?/);
	assert.ok(host.messages.some((m) => m.type === "compacted" && m.session === "c"));
	const saved = host.messages.filter((m) => m.type === "transcript" && m.session === "c").at(-1).messages;
	assert.match(JSON.stringify(saved[0]), /Summary of the earlier conversation/);
});

test("a small conversation is not compacted", async () => {
	const host = await startHost({ credentials: apiKeyCredentials });
	await configure(host, "c2", { messages: longHistory(500) });
	const before = mock.requests.length;
	host.call("prompt", { session: "c2", text: "hi" });
	await host.waitFor("idle", (m) => m.session === "c2");
	assert.equal(mock.requests.length - before, 1);
	assert.equal(host.messages.some((m) => m.type === "compacted"), false);
});

test("a reply that fails with 'context too long' is followed by compaction and a retry", async () => {
	const host = await startHost({ credentials: apiKeyCredentials });
	await configure(host, "o", { messages: longHistory(500) });
	const before = mock.requests.length;
	host.call("prompt", { session: "o", text: "go on [[overflow]]" });
	await host.waitFor("idle", (m) => m.session === "o");
	const sent = mock.requests.slice(before).map((r) => r.body);
	assert.equal(sent.length, 3, "the failed request, the summary, the retry");
	assert.match(sent[1], /You compact a conversation/);
	assert.match(sent[2], /SUMMARY: the user likes llamas/);
	assert.match(sent[2], /go on \[\[overflow\]\]/, "the task itself is kept word for word");
});

test("the user's message is saved before the first turn ends", async () => {
	const host = await startHost({ credentials: apiKeyCredentials, hostCalls: { phone: () => new Promise(() => {}) } });
	await configure(host, "u", { phone: true });
	host.call("prompt", { session: "u", text: "do it [[script:tap]]" });
	await host.waitFor("tool_start", (m) => m.session === "u"); // stuck in a tool, as when the runtime dies
	const t = host.messages.find((m) => m.type === "transcript" && m.session === "u");
	assert.deepEqual(t.messages.map((m) => m.role), ["user"]);
});

test("changing the model keeps the conversation", async () => {
	const host = await startHost({ credentials: apiKeyCredentials });
	await configure(host, "s");
	host.call("prompt", { session: "s", text: "first message" });
	await host.waitFor("idle", (m) => m.session === "s");
	host.clear();
	const conf = await configure(host, "s", { thinkingLevel: "high" });
	assert.equal(conf.messages, 2);
});

test("a paused session waits at the gate before its next action, and Stop at the gate ends it", async () => {
	let open;
	const taps = [];
	const host = await startHost({
		credentials: apiKeyCredentials,
		hostCalls: { gate: () => new Promise((r) => { open = r; }), phone: (m) => { taps.push(m.action); return "tapped"; } },
	});
	await configure(host, "p", { phone: true });
	host.call("prompt", { session: "p", text: "do it [[script:tap]]" });
	await host.waitFor("tool_start", (m) => m.session === "p");
	await new Promise((r) => setTimeout(r, 100));
	assert.deepEqual(taps, [], "paused: nothing happens on the phone");
	open(false); // the user stopped it while paused
	await host.waitFor("idle", (m) => m.session === "p");
	assert.deepEqual(taps, [], "stopped: the tap never happened");
});

test("type_text can submit, and press_key has enter", async () => {
	const calls = [];
	const host = await startHost({ credentials: apiKeyCredentials, hostCalls: { phone: (m) => { calls.push(m); return "typed into [3]; pressed enter"; } } });
	await configure(host, "e", { phone: true });
	host.call("prompt", { session: "e", text: "do it [[script:submit]]" });
	await host.waitFor("idle", (m) => m.session === "e");
	assert.deepEqual(calls.filter((c) => c.action !== "read_screen").map((c) => [c.action, c.args]), [
		["type_text", { id: 3, text: "example.com", submit: true }],
		["press_key", { key: "enter" }],
	]);
});

test("transcripts drop screenshots and old screens", async () => {
	const host = await startHost({
		credentials: apiKeyCredentials,
		hostCalls: { phone: () => `app: x\n[1] TextView "s"` },
	});
	await configure(host, "t", { phone: true });
	host.call("prompt", { session: "t", text: "do it [[script:screens]]" });
	await host.waitFor("idle", (m) => m.session === "t", 15_000);
	const t = host.messages.filter((m) => m.type === "transcript" && m.session === "t").at(-1).messages;
	const screens = t.filter((m) => m.role === "toolResult").map((m) => m.content[0].text);
	assert.equal(screens.length, 4);
	assert.match(screens[0], /omitted/);
	assert.match(screens[3], /TextView/);
});

// ---- the supervisor ---------------------------------------------------------------------------------

test("the supervisor's tools reach Kotlin with the session and their arguments, and the answers come back", async () => {
	const calls = [];
	const host = await startHost({
		credentials: apiKeyCredentials,
		hostCalls: { supervisor: (m) => { calls.push(m); return m.action === "create" ? "started session s9" : `ok ${m.action}`; } },
	});
	await configure(host, "sup", { tools: "supervisor" });
	host.call("prompt", { session: "sup", text: "go [[script:supervise]]" });
	await host.waitFor("idle", (m) => m.session === "sup", 15_000);
	assert.deepEqual(calls.map((c) => c.action), ["list", "create", "wait", "send", "wait", "get"]);
	assert.ok(calls.every((c) => c.session === "sup"));
	assert.deepEqual(calls[1].args, { task: "Say hello in one word.", phone: false, title: "Hello helper" });
	assert.deepEqual(calls[2].args, { ids: ["Hello helper"], timeout: 60 });
	assert.equal(host.messages.find((m) => m.type === "tool_end" && m.tool === "create_session").output, "started session s9");
	assert.ok(!host.messages.some((m) => m.type === "tool_start" && m.tool === "read_screen"), "no phone tools for the supervisor");
});

test("one request can start at most eight sessions", async () => {
	let creates = 0;
	const host = await startHost({ credentials: apiKeyCredentials, hostCalls: { supervisor: () => `started ${++creates}` } });
	await configure(host, "sup", { tools: "supervisor" });
	host.call("prompt", { session: "sup", text: "go [[script:creates]]" });
	await host.waitFor("idle", (m) => m.session === "sup", 15_000);
	assert.equal(creates, 8);
	const blocked = host.messages.filter((m) => m.type === "tool_end" && m.tool === "create_session" && m.isError);
	assert.equal(blocked.length, 1);
});

// ---- bash (Termux, served by Kotlin's "bash" host call) ------------------------------------------

test("a chat session runs bash in Termux; the command and the session reach Kotlin, the output reaches the model", async () => {
	const calls = [];
	const host = await startHost({
		credentials: apiKeyCredentials,
		hostCalls: { bash: (m) => { calls.push(m); return "exit code 0\n--- stdout ---\nHangzhou: 🌧 +14°C"; } },
	});
	await configure(host, "c1"); // a chat: no phone tools, but bash
	host.call("prompt", { session: "c1", text: "weather? [[script:bash]]" });
	await host.waitFor("idle", (m) => m.session === "c1", 15_000);
	assert.equal(calls.length, 1);
	assert.equal(calls[0].session, "c1");
	assert.equal(calls[0].command, "curl -s 'wttr.in/Hangzhou?format=3'");
	assert.equal(calls[0].timeout, 30);
	assert.match(calls[0].call, /^c\d+$/);
	assert.match(calls[0].id, /^k\d+$/);
	const end = host.messages.find((m) => m.type === "tool_end" && m.tool === "bash");
	assert.equal(end.isError, false);
	assert.match(end.output, /Hangzhou: 🌧/);
	const lastRequest = mock.requests.at(-1);
	assert.match(JSON.stringify(lastRequest), /Hangzhou: 🌧/, "the model saw the output");
});

test("Stop during a bash command asks Kotlin to kill that command", async () => {
	let finish, running;
	const started = new Promise((r) => { running = r; });
	const kills = [];
	const host = await startHost({
		credentials: apiKeyCredentials,
		hostCalls: {
			bash: () => new Promise((r) => { finish = r; running(); }),
			bash_kill: (m) => { kills.push(m); finish("killed (stopped)"); return null; },
		},
	});
	await configure(host, "c2");
	host.call("prompt", { session: "c2", text: "weather? [[script:bash]]" });
	await started; // the command is running in Termux
	host.call("abort", { session: "c2" });
	await host.waitFor("idle", (m) => m.session === "c2", 15_000);
	assert.equal(kills.length, 1);
	assert.equal(kills[0].session, "c2");
	assert.match(kills[0].call, /^c\d+$/);
});

test("the supervisor has no bash", async () => {
	const host = await startHost({ credentials: apiKeyCredentials, hostCalls: { supervisor: () => "ok" } });
	await configure(host, "sup2", { tools: "supervisor" });
	host.call("prompt", { session: "sup2", text: "weather? [[script:bash]]" });
	await host.waitFor("idle", (m) => m.session === "sup2", 15_000);
	const end = host.messages.find((m) => m.type === "tool_end" && m.session === "sup2");
	assert.equal(end.isError, true, "Pi rejects a tool the session doesn't have");
});

/** Until [f] gives something (polled). */
async function until(f, timeoutMs = 5000) {
	const end = Date.now() + timeoutMs;
	while (!f()) {
		if (Date.now() > end) throw new Error("timed out");
		await new Promise((r) => setTimeout(r, 5));
	}
}

test("a message sent while a session works reaches the model after the current turn (Pi's steering)", async () => {
	let finishTap;
	const host = await startHost({
		credentials: apiKeyCredentials,
		hostCalls: { phone: (m) => (m.action === "tap" ? new Promise((r) => { finishTap = () => r("tapped"); }) : "app: x") },
	});
	await configure(host, "st", { phone: true });
	host.call("prompt", { session: "st", text: "do it [[script:tap]]" });
	await until(() => finishTap); // in the middle of a tap on the phone
	const before = mock.requests.length;
	host.call("steer", { session: "st", text: "also check the inbox" });
	finishTap();
	const taken = await host.waitFor("message_end", (m) => m.session === "st" && m.role === "user" && m.text === "also check the inbox");
	assert.ok(taken, "Pi took the steered message into the conversation");
	await host.waitFor("idle", (m) => m.session === "st");
	const next = mock.requests.slice(before).map((r) => r.body).join("\n");
	assert.match(next, /also check the inbox/, "the model's next request has the message");
	assert.equal(host.messages.some((m) => m.type === "steer_late"), false);
	assert.equal(host.messages.filter((m) => m.type === "idle" && m.session === "st").length, 1, "one run, one idle");
});

test("a message steered after the run ended comes back as steer_late, for Kotlin to send", async () => {
	const host = await startHost({ credentials: apiKeyCredentials });
	await configure(host, "late");
	host.call("prompt", { session: "late", text: "hi" });
	await host.waitFor("idle", (m) => m.session === "late");
	host.call("steer", { session: "late", text: "one more thing" });
	const late = await host.waitFor("steer_late", (m) => m.session === "late");
	assert.equal(late.text, "one more thing");
	assert.equal(host.messages.some((m) => m.type === "error"), false);
});

test("Stop drops a steered message the model hasn't read yet", async () => {
	let finishTap;
	const host = await startHost({
		credentials: apiKeyCredentials,
		hostCalls: { phone: () => new Promise((r) => { finishTap = () => r("tapped"); }) },
	});
	await configure(host, "ab", { phone: true });
	host.call("prompt", { session: "ab", text: "do it [[script:tap]]" });
	await until(() => finishTap);
	const before = mock.requests.length;
	host.call("steer", { session: "ab", text: "never mind that" });
	host.call("abort", { session: "ab" });
	finishTap();
	await host.waitFor("idle", (m) => m.session === "ab");
	assert.equal(host.messages.some((m) => m.type === "message_end" && m.text === "never mind that"), false);
	assert.doesNotMatch(mock.requests.slice(before).map((r) => r.body).join("\n"), /never mind that/);
});
