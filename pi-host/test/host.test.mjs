// End-to-end tests of the shipped Pi bundle: Pi's own login, Agent, OpenAI adapter and our adapter
// code, talking to a mock OpenAI server through the same bridge protocol Kotlin implements.
import assert from "node:assert/strict";
import { after, before, test } from "node:test";
import { MOCK_REPLY, startMock } from "../../tools/mock-openai.mjs";
import { buildBundle, startHost, toolSpecsFor } from "./harness.mjs";

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

test("offers more of Pi's providers, by API key (xAI also by subscription), each with a model that reads images", async () => {
	const host = await startHost();
	const status = await host.waitFor("status");
	const ids = status.providers.map((p) => p.id);
	for (const id of ["opencode", "opencode-go", "anthropic", "google", "openrouter", "xai", "moonshotai", "mistral"]) assert.ok(ids.includes(id), id);
	for (const p of status.providers) {
		const oauth = p.id === "openai" || p.id === "xai";
		assert.deepEqual(p.methods.map((m) => m.type), oauth ? ["oauth", "api_key"] : ["api_key"], p.id);
		assert.ok(p.models.some((m) => m.input?.includes("image")), `${p.id} has a model that reads images`);
	}
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

test("OpenCode requests carry the session's id, as OpenCode requires", async () => {
	const host = await startHost({ credentials: JSON.stringify({ opencode: { type: "api_key", key: "sk-oc" } }) });
	const before = mock.requests.length;
	host.call("configure", { session: "oc1", provider: "opencode", modelId: "gpt-5.4-mini", thinkingLevel: "low", baseUrl: mock.url });
	await host.waitFor("configured");
	host.call("prompt", { session: "oc1", text: "你好" });
	await host.waitFor("idle");
	assert.equal(host.messages.find((m) => m.type === "assistant_end").errorMessage, undefined);
	const req = mock.requests[before];
	assert.equal(req.headers.authorization, "Bearer sk-oc");
	assert.equal(req.headers["x-opencode-session"], "oc1");
});

test("a custom OpenAI-compatible provider: offered, keyed, used for chat, and removable", async () => {
	const custom = { id: "custom-mock", name: "My server", baseUrl: mock.url, models: ["mock-vision", "mock-chat"], images: true };
	const host = await startHost({ customProviders: [custom] });
	const status = await host.waitFor("status");
	const p = status.providers.find((x) => x.id === "custom-mock");
	assert.equal(p.name, "My server");
	assert.equal(p.custom, true);
	assert.deepEqual(p.methods.map((m) => m.type), ["api_key"]);
	assert.deepEqual(p.models.map((m) => m.id), ["mock-vision", "mock-chat"]);
	assert.ok(p.models.every((m) => m.input.includes("image")));

	// The app saves the key with the provider (no separate sign-in step).
	host.call("setCustomProviders", { providers: [custom], provider: "custom-mock", key: "sk-mine" });
	await host.waitFor("status", (s) => s.providers.find((x) => x.id === "custom-mock")?.credential === "api_key");
	assert.deepEqual(JSON.parse(host.state.credentials)["custom-mock"], { type: "api_key", key: "sk-mine" });

	const before = mock.requests.length;
	host.call("configure", { session: "c1", provider: "custom-mock", modelId: "mock-vision", thinkingLevel: "off" });
	await host.waitFor("configured");
	host.call("prompt", { session: "c1", text: "你好" });
	await host.waitFor("idle");
	const end = host.messages.find((m) => m.type === "assistant_end");
	assert.equal(end.errorMessage, undefined);
	assert.equal(end.api, "openai-completions");
	assert.equal(host.messages.filter((m) => m.type === "text_delta").map((m) => m.delta).join(""), MOCK_REPLY);
	const req = mock.requests[before];
	assert.equal(req.url, "/v1/chat/completions");
	assert.equal(req.headers.authorization, "Bearer sk-mine");
	const body = JSON.parse(req.body);
	assert.equal(body.model, "mock-vision");
	assert.equal(body.store, undefined, "no OpenAI-only store field");
	assert.ok(!body.messages.some((m) => m.role === "developer"), "system role, not developer");

	// Removing it drops the provider and its key.
	host.call("setCustomProviders", { providers: [] });
	await host.waitFor("status", (s) => !s.providers.some((x) => x.id === "custom-mock"));
	assert.equal(JSON.parse(host.state.credentials)["custom-mock"], undefined);
});

test("Pi's catalog fills in what a custom server's model list lacks, matching relays' spellings", async () => {
	const host = await startHost();
	await host.waitFor("status");
	const found = JSON.parse(host.call("lookupModels", { ids: ["claude-sonnet-5.5", "anthropic/claude-sonnet-5-5", "deepseek-flash", "DeepSeek-V4.1-Flash:free", "my-finetune"] }));
	// A vendor's own entry wins over an aggregator's listing of the same model.
	assert.equal(found["claude-sonnet-5.5"].match, "anthropic/claude-sonnet-5-5");
	assert.equal(found["anthropic/claude-sonnet-5-5"].match, "anthropic/claude-sonnet-5-5");
	assert.ok(found["claude-sonnet-5.5"].contextWindow > 0);
	assert.equal(found["claude-sonnet-5.5"].images, true);
	assert.equal(found["deepseek-flash"].match, "deepseek/deepseek-flash");
	assert.equal(found["deepseek-flash"].contextWindow, 1_000_000);
	assert.ok(found["DeepSeek-V4.1-Flash:free"], "matched through an aggregator's listing");
	assert.equal(found["my-finetune"], undefined);
	const effort = JSON.parse(host.call("lookupModels", { ids: ["gemini-3.8-flash-high", "deepseek-flash-thinking"] }));
	assert.equal(effort["gemini-3.8-flash-high"].match, "google/gemini-3.8-flash");
	assert.equal(effort["deepseek-flash-thinking"].match, "deepseek/deepseek-flash");
});

test("a custom provider's models keep their own context window and image support", async () => {
	const custom = { id: "custom-x", name: "X", baseUrl: mock.url, images: true,
		models: ["plain", { id: "small", contextWindow: 32_000, images: false }] };
	const host = await startHost({ customProviders: [custom] });
	const p = (await host.waitFor("status")).providers.find((x) => x.id === "custom-x");
	const [plain, small] = p.models;
	assert.equal(plain.contextWindow, 128_000);
	assert.ok(plain.input.includes("image"));
	assert.equal(small.contextWindow, 32_000);
	assert.deepEqual(small.input, ["text"]);
});

test("a custom model that thinks offers the efforts its server takes, and sends the one chosen", async () => {
	const custom = { id: "custom-g", name: "G", baseUrl: mock.url, images: true, models: [
		{ id: "gw-thinker", reasoning: true, efforts: ["low", "medium", "high", "xhigh", "max", "ultra"] },
		{ id: "plain-thinker", reasoning: true },
		"no-thinking",
	] };
	const host = await startHost({ customProviders: [custom], credentials: JSON.stringify({ "custom-g": { type: "api_key", key: "k" } }) });
	const p = (await host.waitFor("status")).providers.find((x) => x.id === "custom-g");
	const levels = Object.fromEntries(p.models.map((m) => [m.id, m.thinkingLevels]));
	// No "none" among the server's efforts: it always thinks, so no Off; "ultra" has no Pi level.
	assert.deepEqual(levels["gw-thinker"], ["low", "medium", "high", "xhigh", "max"]);
	assert.deepEqual(levels["plain-thinker"], ["off", "minimal", "low", "medium", "high"]);
	assert.deepEqual(levels["no-thinking"], ["off"]);

	const before = mock.requests.length;
	host.call("configure", { session: "g1", provider: "custom-g", modelId: "gw-thinker", thinkingLevel: "xhigh" });
	await host.waitFor("configured");
	host.call("prompt", { session: "g1", text: "hi" });
	await host.waitFor("idle");
	assert.equal(JSON.parse(mock.requests[before].body).reasoning_effort, "xhigh");
});

// A 1×1 PNG.
const PIXEL = "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==";

test("attached images and files reach the model with the typed text; only the typed text is echoed", async () => {
	const host = await startHost({ credentials: apiKeyCredentials });
	const before = mock.requests.length;
	host.call("configure", { session: "a1", provider: "openai", modelId: "gpt-5.4-mini", thinkingLevel: "low", baseUrl: mock.url });
	await host.waitFor("configured");
	host.call("prompt", { session: "a1", text: "what is this?", content: [
		{ type: "text", text: '<attachment name="pic.png" type="image"/>' },
		{ type: "image", mimeType: "image/png", data: PIXEL },
		{ type: "text", text: '<attachment name="notes.txt">\nbuy llamas\n</attachment>' },
	] });
	await host.waitFor("idle");
	assert.equal(host.messages.find((m) => m.type === "message_end" && m.role === "user").text, "what is this?");
	const input = JSON.stringify(JSON.parse(mock.requests[before].body).input);
	assert.match(input, /what is this\?/);
	assert.match(input, /buy llamas/);
	assert.ok(input.includes(`data:image/png;base64,${PIXEL}`), "the image is sent as an image");
});

test("a model that can't read images gets a note in place of an attached image", async () => {
	const custom = { id: "custom-t", name: "T", baseUrl: mock.url, images: false, models: ["text-only"] };
	const host = await startHost({ customProviders: [custom], credentials: JSON.stringify({ "custom-t": { type: "api_key", key: "k" } }) });
	const before = mock.requests.length;
	host.call("configure", { session: "a2", provider: "custom-t", modelId: "text-only", thinkingLevel: "off" });
	await host.waitFor("configured");
	host.call("prompt", { session: "a2", text: "", content: [{ type: "image", mimeType: "image/png", data: PIXEL }] });
	await host.waitFor("idle");
	const body = mock.requests[before].body;
	assert.ok(!body.includes(PIXEL), "no image data sent");
	assert.match(body, /can't see images/);
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

test("xAI sign-in shows Pi's device code and saves the token once it is approved", async () => {
	let polls = 0;
	const host = await startHost({
		fakeFetch: (m) => {
			if (m.url === "https://auth.x.ai/oauth2/device/code") {
				return { status: 200, json: { device_code: "dev", user_code: "ABCD-1234", verification_uri: "https://accounts.x.ai/device", verification_uri_complete: "https://accounts.x.ai/device?code=ABCD-1234", interval: 1, expires_in: 600 } };
			}
			if (m.url === "https://auth.x.ai/oauth2/token") {
				return ++polls === 1
					? { status: 400, json: { error: "authorization_pending" } }
					: { status: 200, json: { access_token: "xai-at", refresh_token: "xai-rt", expires_in: 3600 } };
			}
			return null;
		},
	});
	const status = await host.waitFor("status");
	const xai = status.providers.find((p) => p.id === "xai");
	assert.equal(xai.methods[0].type, "oauth");
	assert.equal(xai.methods[0].subscription, true);
	host.call("login", { provider: "xai", type: "oauth" });
	const ev = await host.waitFor("auth_event", (m) => m.event.type === "device_code");
	assert.equal(ev.event.userCode, "ABCD-1234");
	assert.equal(ev.event.verificationUri, "https://accounts.x.ai/device?code=ABCD-1234");
	await host.waitFor("login_done", (m) => m.provider === "xai", 15_000);
	const saved = JSON.parse(host.state.credentials).xai;
	assert.equal(saved.type, "oauth");
	assert.equal(saved.access, "xai-at");
	const after = await host.waitFor("status", (m) => m.providers.find((p) => p.id === "xai").credential === "oauth");
	assert.ok(after.providers.find((p) => p.id === "xai").models.length > 0, "an xAI sign-in reaches xAI's models");
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
		// Kotlin's PhoneTools answers an action with its result and the screen it left.
		hostCalls: { phone: (m) => { calls.push(m); return `tapped [${m.args.id}]\n\nScreen now:\napp: x`; } },
	});
	await phoneRun(host, "tap");
	assert.deepEqual(calls.map((c) => c.action), ["tap"]);
	assert.deepEqual(calls[0].args, { id: 51 }); // the element id travels inside args …
	assert.match(calls[0].id, /^k\d+$/); // … so the bridge's own request id survives (the Gmail hang)
	const end = host.messages.find((m) => m.type === "tool_end" && m.tool === "tap");
	assert.equal(end.isError, false);
	assert.equal(end.output, "tapped [51]\n\nScreen now:\napp: x");
});

test("the model gets the app's catalog exactly: each session's tools, with their descriptions and schemas", async () => {
	const host = await startHost({ credentials: apiKeyCredentials, hostCalls: { phone: () => "app: x" } });
	const before = mock.requests.length;
	await phoneRun(host, "tap");
	const sent = JSON.parse(mock.requests[before].body).tools;
	const expected = toolSpecsFor({ phone: true });
	assert.deepEqual(sent.map((t) => t.name), expected.map((t) => t.name));
	for (const t of expected) {
		const got = sent.find((x) => x.name === t.name);
		assert.equal(got.description, t.description, t.name);
		assert.deepEqual(got.parameters, t.parameters, t.name);
	}
	// The raw call: the session, the core's call id, the tool's name and the model's arguments.
	const tap = host.toolCalls.find((c) => c.name === "tap");
	assert.deepEqual([tap.session, tap.args], ["s1", { id: 51 }]);
	assert.match(tap.call, /./);
});

test("Kotlin's before_tool sees every tool call with its arguments, and allowing lets it run", async () => {
	const checks = [];
	const calls = [];
	const taps = [];
	const host = await startHost({
		credentials: apiKeyCredentials,
		hostCalls: {
			before_tool: (m) => { checks.push([m.session, m.tool, m.args]); calls.push(m.call); return null; },
			phone: (m) => (m.action === "read_screen" ? "app: x" : (taps.push(m.args.id), "tapped")),
		},
	});
	await phoneRun(host, "send");
	assert.deepEqual(checks, [["s1", "read_screen", {}], ["s1", "tap", { id: 2 }]]);
	assert.deepEqual(taps, [2], "allowed: the tap happened");
	// The same id in before_tool and tool: the app runs only a call it allowed.
	assert.deepEqual(host.toolCalls.map((c) => c.call), calls);
	assert.ok(calls.every((c) => typeof c === "string" && c.length > 0));
});

test("a call before_tool blocks never reaches the phone, and ends the task", async () => {
	const taps = [];
	const host = await startHost({
		credentials: apiKeyCredentials,
		hostCalls: {
			before_tool: (m) => (m.tool === "tap" ? { block: true, terminate: true, reason: "The user declined this action. Stop and tell the user." } : null),
			phone: (m) => (m.action === "read_screen" ? "app: x" : (taps.push(m.args.id), "tapped")),
		},
	});
	const before = mock.requests.length;
	await phoneRun(host, "send");
	assert.deepEqual(taps, [], "declined: Kotlin was never asked to tap");
	const end = host.messages.find((m) => m.type === "tool_end" && m.tool === "tap");
	assert.equal(end.isError, true);
	assert.match(end.output, /declined/);
	// Ended: the model was not asked again after the blocked tap.
	assert.equal(mock.requests.length - before, 2, "the read_screen turn and the tap turn, nothing after");
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
		// As Kotlin's PhoneTools: an action's result, then the screen it left.
		hostCalls: { phone: (m) => `${m.action === "read_screen" ? "" : `did ${m.action}\n\nScreen now:\n`}app: x\n[1] TextView "screen ${++n}"` },
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
		hostCalls: { before_tool: () => new Promise((r) => { open = r; }), phone: (m) => { taps.push(m.action); return "tapped"; } },
	});
	await configure(host, "p", { phone: true });
	host.call("prompt", { session: "p", text: "do it [[script:tap]]" });
	await host.waitFor("tool_start", (m) => m.session === "p");
	await new Promise((r) => setTimeout(r, 100));
	assert.deepEqual(taps, [], "paused: nothing happens on the phone");
	open({ block: true, terminate: true, reason: "Stopped by the user." }); // the user stopped it while paused
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

test("a screenshot reaches the model as an image, with the app's caption", async () => {
	const jpeg = "/9j/" + Buffer.from("a tiny fake jpeg").toString("base64");
	const host = await startHost({ credentials: apiKeyCredentials, hostCalls: { phone: () => jpeg } });
	await configure(host, "img", { phone: true });
	const before = mock.requests.length;
	host.call("prompt", { session: "img", text: "look [[script:shot]]" });
	await host.waitFor("idle", (m) => m.session === "img", 15_000);
	const outputs = mock.requests.slice(before).flatMap((r) => JSON.parse(r.body).input.filter((i) => i.type === "function_call_output"));
	const parts = JSON.stringify(outputs);
	assert.match(parts, /Screenshot \(720 px wide/);
	assert.match(parts, /input_image/);
	assert.ok(parts.includes(jpeg.slice(0, 20)), "the picture's bytes went to the model");
});

test("a screenshot that comes back as a message (not a JPEG) reaches the model as text, not as an image", async () => {
	const host = await startHost({
		credentials: apiKeyCredentials,
		hostCalls: { phone: () => "小维助手 (this assistant) is in front of the screen: this task hasn't opened an app yet." },
	});
	await configure(host, "s", { phone: true });
	host.call("prompt", { session: "s", text: "look [[script:shot]]" });
	await host.waitFor("idle", (m) => m.session === "s", 15_000);
	const t = host.messages.filter((m) => m.type === "transcript" && m.session === "s").at(-1).messages;
	const shot = t.find((m) => m.role === "toolResult" && m.toolName === "screenshot");
	assert.ok(shot.content.every((c) => c.type === "text"), "no image part");
	assert.match(shot.content[0].text, /hasn't opened an app/);
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
	// pi-host sends each call as the model made it: the tool's name and arguments (Kotlin's ToolRouter shapes them).
	const raw = host.toolCalls.filter((c) => c.session === "sup");
	assert.deepEqual(raw.map((c) => c.name), ["list_sessions", "create_session", "wait_for", "message_session", "wait_for", "get_session"]);
	assert.deepEqual(raw[1].args, { task: "Say hello in one word.", phone: false, title: "Hello helper" });
	assert.deepEqual(raw[2].args, { ids: ["Hello helper"], timeout_seconds: 60 });
	assert.ok(raw.every((c) => /./.test(c.call)), "each call has the core's own id");
	assert.equal(host.messages.find((m) => m.type === "tool_end" && m.tool === "create_session").output, "started session s9");
	assert.ok(!host.messages.some((m) => m.type === "tool_start" && m.tool === "read_screen"), "no phone tools for the supervisor");
});

test("a block that doesn't end the run (the supervisor's limit of eight new sessions) lets the model go on", async () => {
	let creates = 0;
	let asked = 0;
	const host = await startHost({
		credentials: apiKeyCredentials,
		hostCalls: {
			supervisor: () => `started ${++creates}`,
			// As Kotlin's before_tool: the ninth create_session in one request is refused, and the run goes on.
			before_tool: (m) => (m.tool === "create_session" && ++asked > 8 ? { block: true, terminate: false, reason: "At most 8 new sessions per request." } : null),
		},
	});
	await configure(host, "sup", { tools: "supervisor" });
	host.call("prompt", { session: "sup", text: "go [[script:creates]]" });
	await host.waitFor("idle", (m) => m.session === "sup", 15_000);
	assert.equal(creates, 8);
	const blocked = host.messages.filter((m) => m.type === "tool_end" && m.tool === "create_session" && m.isError);
	assert.equal(blocked.length, 1);
	assert.match(blocked[0].output, /At most 8/);
	// Not terminated: the model was asked again after the blocked call, and answered.
	assert.ok(host.messages.some((m) => m.type === "assistant_end" && m.session === "sup" && m.text));
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
	assert.match(calls[0].call, /./);
	assert.match(calls[0].id, /^k\d+$/);
	const end = host.messages.find((m) => m.type === "tool_end" && m.tool === "bash");
	assert.equal(end.isError, false);
	assert.match(end.output, /Hangzhou: 🌧/);
	const lastRequest = mock.requests.at(-1);
	assert.match(JSON.stringify(lastRequest), /Hangzhou: 🌧/, "the model saw the output");
});

test("a session configured without bash has no bash tool, and the model isn't told about Termux", async () => {
	const calls = [];
	const host = await startHost({ credentials: apiKeyCredentials, hostCalls: { bash: (m) => { calls.push(m); return "ran"; } } });
	await configure(host, "nb", { bash: false });
	const before = mock.requests.length;
	host.call("prompt", { session: "nb", text: "hello" });
	await host.waitFor("idle", (m) => m.session === "nb", 15_000);
	const body = mock.requests[before].body;
	assert.doesNotMatch(body, /"name":"bash"/);
	assert.doesNotMatch(body, /Termux/);
	assert.equal(calls.length, 0);
});

test("a conversation from Telegram is told so, and send_file asks Kotlin to send the file", async () => {
	const sent = [];
	const host = await startHost({
		credentials: apiKeyCredentials,
		hostCalls: { send_file: (m) => { sent.push(m); return sent.length === 1 ? { ok: true, text: "sent chart.png (12 KB) to the chat" } : { ok: false, text: "No such file in Termux: chart.png" }; } },
	});
	await configure(host, "tg", { channel: "telegram" });
	const before = mock.requests.length;
	host.call("prompt", { session: "tg", text: "hello" });
	await host.waitFor("idle", (m) => m.session === "tg", 15_000);
	const body = mock.requests[before].body;
	assert.match(body, /from Telegram/);
	assert.match(body, /"name":"send_file"/);
	assert.equal(sent.length, 0);
	host.call("prompt", { session: "tg", text: "the chart please [[script:send_file]]" });
	await host.waitFor("idle", (m) => m.session === "tg" && host.messages.some((x) => x.type === "tool_end" && x.tool === "send_file"), 15_000);
	assert.deepEqual([sent[0].session, sent[0].path, sent[0].caption], ["tg", "chart.png", "The chart"]);
	const ok = host.messages.find((m) => m.type === "tool_end" && m.tool === "send_file");
	assert.match(ok.output, /sent chart.png/);
	assert.equal(ok.isError, false);
	// A file that didn't go is a failed step (a new conversation: the mock's script counts this one's tool results).
	await configure(host, "tg3", { channel: "telegram" });
	host.call("prompt", { session: "tg3", text: "again [[script:send_file]]" });
	await host.waitFor("idle", (m) => m.session === "tg3", 15_000);
	const failed = host.messages.find((m) => m.type === "tool_end" && m.tool === "send_file" && m.session === "tg3");
	assert.equal(failed.isError, true);
	assert.match(failed.output, /No such file/);
	// Without bash it can't reach Termux, so no send_file.
	await configure(host, "tg2", { channel: "telegram", bash: false });
	const b2 = mock.requests.length;
	host.call("prompt", { session: "tg2", text: "hello" });
	await host.waitFor("idle", (m) => m.session === "tg2", 15_000);
	assert.doesNotMatch(mock.requests[b2].body, /"name":"send_file"/);
	assert.match(mock.requests[b2].body, /from Telegram/);
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
	assert.match(kills[0].call, /./);
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

// ---- other AI cores (DeepSeek Harness), through the app's key proxy ---------------------------------

const query = async (host, fn, args = {}) => {
	const id = `q${Math.random().toString(36).slice(2)}`;
	host.call("query", { id, fn, args });
	const r = await host.waitFor("query_result", (m) => m.id === id);
	if (!r.ok) throw new Error(r.value?.message);
	return r.value;
};

test("another core gets the signed-in providers as routes, with their models", async () => {
	const host = await startHost({ credentials: apiKeyCredentials });
	const routes = await query(host, "coreRoutes");
	const openai = routes.find((r) => r.id === "openai");
	assert.ok(openai, JSON.stringify(routes.map((r) => r.id)));
	assert.equal(openai.api, "openai-responses");
	const m = openai.models.find((x) => x.id === "gpt-5.4-mini");
	assert.ok(m && m.contextWindow > 0 && m.input.includes("text"));
	assert.ok(!routes.some((r) => r.id === "anthropic"), "only providers with a credential");
});

test("the key proxy gets a provider's current key and endpoint; unknown providers have none", async () => {
	const host = await startHost({ credentials: apiKeyCredentials });
	const auth = await query(host, "providerAuth", { provider: "openai" });
	assert.equal(auth.apiKey, JSON.parse(apiKeyCredentials).openai.key);
	assert.equal(auth.baseUrl, "https://api.openai.com/v1");
	assert.equal(await query(host, "providerAuth", { provider: "nope" }), null);
	await assert.rejects(query(host, "noSuchQuery"));
});

test("a custom provider is a route with its endpoint's quirks, and its key and base URL reach the proxy", async () => {
	const host = await startHost({ credentials: apiKeyCredentials, customProviders: [{ id: "custom-x", name: "X", baseUrl: "http://10.0.0.2:8000/v1", models: [{ id: "qwen-9", contextWindow: 32768, images: false }], images: false }] });
	host.call("setCustomProviders", { providers: [{ id: "custom-x", name: "X", baseUrl: "http://10.0.0.2:8000/v1", models: [{ id: "qwen-9", contextWindow: 32768, images: false }], images: false }], provider: "custom-x", key: "sk-x" });
	await host.waitFor("status", (m) => m.providers.some((p) => p.id === "custom-x" && p.credential));
	const route = (await query(host, "coreRoutes")).find((r) => r.id === "custom-x");
	assert.equal(route.api, "openai-completions");
	assert.deepEqual(route.models.map((m) => [m.id, m.contextWindow, m.input]), [["qwen-9", 32768, ["text"]]]);
	assert.equal(route.models[0].compat.supportsDeveloperRole, false);
	const auth = await query(host, "providerAuth", { provider: "custom-x" });
	assert.deepEqual([auth.apiKey, auth.baseUrl], ["sk-x", "http://10.0.0.2:8000/v1"]);
});
