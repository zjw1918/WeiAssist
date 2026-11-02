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
	host.call("configure", { provider: "openai", modelId: "gpt-5.4-mini", thinkingLevel: "low", baseUrl: mock.url });
	await host.waitFor("configured");
	host.call("prompt", { text: "你好" });
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
	host.call("configure", { provider: "openai", modelId: "no-such-model", baseUrl: mock.url });
	const err = await host.waitFor("error");
	assert.match(err.message, /no model openai\/no-such-model/);
});

// ---- phone tools (Pi AgentTools served by Kotlin's "phone" host call) ------------------------------

const phoneRun = async (host, script) => {
	host.call("configure", { provider: "openai", modelId: "gpt-5.4-mini", baseUrl: mock.url, phone: true });
	await host.waitFor("configured");
	host.call("prompt", { text: `do it [[script:${script}]]` });
	await host.waitFor("idle", () => true, 15_000);
};

test("tool arguments reach Kotlin intact and never clobber the bridge request id", async () => {
	const calls = [];
	const host = await startHost({
		credentials: apiKeyCredentials,
		hostCalls: { phone: (m) => { calls.push(m); return `tapped [${m.args.id}]`; } },
	});
	await phoneRun(host, "tap");
	assert.equal(calls.length, 1);
	assert.equal(calls[0].action, "tap");
	assert.deepEqual(calls[0].args, { id: 51 }); // the element id travels inside args …
	assert.match(calls[0].id, /^k\d+$/); // … so the bridge's own request id survives (the Gmail hang)
	const end = host.messages.find((m) => m.type === "tool_end" && m.tool === "tap");
	assert.equal(end.isError, false);
	assert.equal(end.output, "tapped [51]");
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
