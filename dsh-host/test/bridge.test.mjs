// The bridge end to end: a real dsh (the weiassist profile, with this plugin installed) against a fake app side that
// speaks the app's core contract, and the mock model server. Needs a dsh installation:
//   DSH_RUN   a directory with node_modules/.bin/dsh (npm install @deepseek-ai/dsh)
//   DSH_HOME  a Harness home whose profiles/weiassist has this plugin and profile/cordis.patch.yml
// Skipped without them.
import assert from "node:assert/strict";
import { spawn } from "node:child_process";
import { readFileSync } from "node:fs";
import net from "node:net";
import { join } from "node:path";
import { after, before, test } from "node:test";
import { MOCK_REPLY, startMock } from "../../tools/mock-openai.mjs";

const { DSH_RUN, DSH_HOME } = process.env;
const skip = !DSH_RUN || !DSH_HOME ? "set DSH_RUN and DSH_HOME to run against a dsh installation" : false;

const catalog = JSON.parse(readFileSync(new URL("../../agent/src/main/resources/com/lanqi/weiassist/agent/tools.json", import.meta.url), "utf8")).tools;
const specs = (set) => catalog.filter((t) => t.set === set).map(({ set: _s, ...spec }) => spec);

/** The app's side of the bridge: a loopback server, the events it got, and host-call handlers per test. */
class FakeApp {
	messages = [];
	waiters = [];
	handlers = {};
	toolCalls = [];
	secret = "s3cret";

	async listen() {
		this.server = net.createServer((sock) => {
			this.sock = sock;
			sock.setEncoding("utf8");
			let buf = "";
			sock.on("data", (c) => {
				buf += c;
				let nl;
				while ((nl = buf.indexOf("\n")) >= 0) {
					const line = buf.slice(0, nl);
					buf = buf.slice(nl + 1);
					if (line.trim()) this.onMessage(JSON.parse(line));
				}
			});
		});
		await new Promise((r) => this.server.listen(0, "127.0.0.1", r));
		return this.server.address().port;
	}

	onMessage(m) {
		this.messages.push(m);
		for (const w of [...this.waiters]) if (w.match(m)) { this.waiters.splice(this.waiters.indexOf(w), 1); w.resolve(m); }
		if (m.id && ["before_tool", "tool", "tool_cancel", "ask_user", "ask_user_cancel"].includes(m.type)) {
			if (m.type === "tool") this.toolCalls.push(m);
			const h = this.handlers[m.type] ?? (() => null);
			Promise.resolve(h(m)).then(
				(value) => this.send({ type: "reply", id: m.id, ok: true, value: value ?? null }),
				(e) => this.send({ type: "reply", id: m.id, ok: false, value: { message: String(e?.message ?? e) } }),
			);
		}
	}

	send(m) { this.sock.write(JSON.stringify(m) + "\n"); }

	waitFor(type, pred = () => true, ms = 60_000) {
		const match = (m) => m.type === type && pred(m);
		const seen = this.messages.find(match);
		if (seen) return Promise.resolve(seen);
		return new Promise((resolve, reject) => {
			const t = setTimeout(() => reject(new Error(`timed out waiting for ${type}`)), ms);
			this.waiters.push({ match, resolve: (m) => { clearTimeout(t); resolve(m); } });
		});
	}
}

let app, mock, dsh, stderr = "";
/** Session ids unique to this run: dsh keeps every session in DSH_HOME, and a resumed one has its old conversation. */
const run = Date.now().toString(36);
const id = (name) => `${name}-${run}`;

before(async () => {
	if (skip) return;
	mock = await startMock(0, { log: () => {}, delayMs: 1 });
	app = new FakeApp();
	const port = await app.listen();
	dsh = spawn(join(DSH_RUN, "node_modules/.bin/dsh"), ["--profile", "weiassist"], {
		cwd: DSH_RUN,
		env: { ...process.env, DSH_HOME, WEIASSIST_BRIDGE_PORT: String(port), WEIASSIST_BRIDGE_SECRET: app.secret, WEIASSIST_MOCK_URL: mock.url, WEIASSIST_KEY_MOCK: "test-key" },
		stdio: ["ignore", "ignore", "pipe"],
	});
	dsh.stderr.on("data", (c) => { stderr += c; });
	await app.waitFor("hello", () => true, 120_000).catch((e) => { throw new Error(`${e.message}\n${stderr}`); });
	// The app's providers as dsh's model routes (here only the mock server, without the key proxy).
	app.send({ type: "routes", providers: { mock: {
		displayName: "Mock", api: "openai-responses", apiKeyEnv: "WEIASSIST_KEY_MOCK", baseURL: mock.url,
		models: [{ id: "gpt-6.1-terra", contextWindow: 128000, input: ["text", "image"], reasoningEfforts: { low: "low", medium: "medium", high: "high" } }],
	} } });
});

after(async () => {
	dsh?.kill();
	app?.server.close();
	await mock?.close();
});

const configure = async (session, extra = {}) => {
	const seen = app.messages.length; // a new configured, not one from earlier
	app.send({ type: "configure", session, provider: "openai", modelId: "gpt-6.1-terra", baseUrl: mock.url, toolSpecs: [], ...extra });
	return app.waitFor("configured", (m) => m.session === session && app.messages.indexOf(m) >= seen);
};

test("dsh starts clean: no plugin fails or stays pending", { skip }, async () => {
	await app.waitFor("hello");
	await new Promise((r) => setTimeout(r, 1000)); // late activation reports
	assert.doesNotMatch(stderr, /did not activate|^error:|failed/im, stderr);
});

test("the bridge says hello with the app's secret", { skip }, async () => {
	const hello = await app.waitFor("hello");
	assert.equal(hello.secret, app.secret);
});

test("a chat: the answer streams as text_delta, ends with assistant_end, then idle", { skip }, async () => {
	await configure(id("c1"));
	app.send({ type: "prompt", session: id("c1"), text: "hello" });
	const end = await app.waitFor("assistant_end", (m) => m.session === id("c1"));
	assert.equal(end.text, MOCK_REPLY);
	const deltas = app.messages.filter((m) => m.type === "text_delta" && m.session === id("c1")).map((m) => m.delta).join("");
	assert.equal(deltas, MOCK_REPLY, "the answer streamed");
	await app.waitFor("idle", (m) => m.session === id("c1"));
	assert.ok(app.messages.some((m) => m.type === "agent_start" && m.session === id("c1")));
	assert.ok(app.messages.some((m) => m.type === "transcript" && m.session === id("c1")));
	const req = JSON.parse(mock.requests.at(-1).body);
	assert.equal(req.model, "gpt-6.1-terra");
	assert.match(mock.requests.at(-1).headers.authorization ?? "", /test-key/, "the app's key reached the provider");
});

test("phone tools: the app's specs reach the model, calls go through before_tool then tool", { skip }, async () => {
	const order = [];
	app.handlers = {
		before_tool: (m) => { order.push(["before_tool", m.tool, m.call]); return null; },
		tool: (m) => { order.push(["tool", m.name, m.call]); return { text: `tapped [${m.args.id}]\n\nScreen now:\napp: x` }; },
	};
	await configure(id("p1"), { tools: "phone", toolSpecs: specs("phone") });
	const before = mock.requests.length;
	app.send({ type: "prompt", session: id("p1"), text: "do it [[script:tap]]" });
	await app.waitFor("idle", (m) => m.session === id("p1"));
	const tools = JSON.parse(mock.requests[before].body).tools.map((t) => t.name);
	// The app's tools on top of dsh's own (it keeps its native capabilities).
	for (const t of specs("phone")) assert.ok(tools.includes(t.name), t.name);
	for (const native of ["read", "subagent", "todo_write", "web_fetch"]) assert.ok(tools.includes(native), native);
	assert.deepEqual(order.map(([k, n]) => [k, n]), [["before_tool", "tap"], ["tool", "tap"]]);
	assert.equal(order[0][2], order[1][2], "the same call id in both, so the app can match them");
	const call = app.toolCalls.at(-1);
	assert.deepEqual([call.session, call.args], [id("p1"), { id: 51 }]);
	const start = app.messages.find((m) => m.type === "tool_start" && m.session === id("p1"));
	assert.deepEqual([start.tool, start.args], ["tap", { id: 51 }]);
	const end = app.messages.find((m) => m.type === "tool_end" && m.session === id("p1"));
	assert.equal(end.isError, false);
	assert.match(end.output, /tapped \[51\]/);
	assert.match(mock.requests.at(-1).body, /Screen now/, "the model saw the result");
});

test("a screenshot reaches the model as an image", { skip }, async () => {
	// A real (tiny) JPEG: dsh's image admission decodes it.
	const jpeg = "/9j/4AAQSkZJRgABAQEASABIAAD/2wBDAP//////////////////////////////////////////////////////////////////////////////////////wAALCAABAAEBAREA/8QAFAABAAAAAAAAAAAAAAAAAAAAA//EABQQAQAAAAAAAAAAAAAAAAAAAAD/2gAIAQEAAD8AN//Z";
	app.handlers = {
		before_tool: () => null,
		tool: () => ({ text: "Screenshot (720 px wide; for tap_at, scale x and y by screen width / 720).", image: { data: jpeg, mimeType: "image/jpeg" } }),
	};
	await configure(id("img"), { tools: "phone", toolSpecs: specs("phone") });
	const before = mock.requests.length;
	app.send({ type: "prompt", session: id("img"), text: "look [[script:shot]]" });
	await app.waitFor("idle", (m) => m.session === id("img"));
	const outputs = mock.requests.slice(before).flatMap((r) => JSON.parse(r.body).input.filter((i) => i.type === "function_call_output"));
	assert.match(JSON.stringify(outputs), /input_image/, `the picture went to the model: ${JSON.stringify(outputs).slice(0, 300)}`);
});

test("a call before_tool blocks never runs, and a terminating block ends the run", { skip }, async () => {
	app.handlers = {
		before_tool: (m) => (m.tool === "tap" ? { block: true, terminate: true, reason: "The user declined this action. Stop and tell the user." } : null),
		tool: () => ({ text: "should not run" }),
	};
	const toolCallsBefore = app.toolCalls.length;
	await configure(id("p2"), { tools: "phone", toolSpecs: specs("phone") });
	const before = mock.requests.length;
	app.send({ type: "prompt", session: id("p2"), text: "do it [[script:tap]]" });
	await app.waitFor("idle", (m) => m.session === id("p2"));
	assert.equal(app.toolCalls.length, toolCallsBefore, "declined: the app was never asked to tap");
	const end = app.messages.find((m) => m.type === "tool_end" && m.session === id("p2"));
	assert.equal(end.isError, true);
	assert.match(end.output, /declined/);
	assert.equal(mock.requests.length - before, 1, "ended: the model was not asked again");
});

test("dsh's own tool of the same name stays, and its calls go through the app's before_tool too", { skip }, async () => {
	const checked = [];
	const ran = [];
	app.handlers = { before_tool: (m) => { checked.push(m.tool); return null; }, tool: (m) => { ran.push(m.name); return { text: "app" }; } };
	const conf = await configure(id("n1"), { toolSpecs: specs("bash") });
	assert.deepEqual(conf.skipped, ["bash"], "the app's bash is left out: dsh has its own");
	app.send({ type: "prompt", session: id("n1"), text: "greet [[script:dsh_bash]]" });
	await app.waitFor("idle", (m) => m.session === id("n1"));
	assert.deepEqual(checked, ["bash"]);
	assert.deepEqual(ran, [], "dsh ran it, not the app");
	const end = app.messages.find((m) => m.type === "tool_end" && m.session === id("n1"));
	assert.equal(end.isError, false, end.output);
	assert.match(end.output, /hi from dsh/);
});

test("a subagent's calls go through the app's before_tool, and its work shows in its own section", { skip }, async () => {
	const checked = [];
	app.handlers = { before_tool: (m) => { checked.push([m.tool, m.subagent !== undefined]); return null; } };
	await configure(id("sub"));
	app.send({ type: "prompt", session: id("sub"), text: "plan [[script:delegate]]" });
	// Subagents run in the background: the session answers at once, and its subagent works on.
	await app.waitFor("assistant_end", (m) => m.session === id("sub") && m.text === "delegated", 60_000);
	const childEnd = await app.waitFor("tool_end", (m) => m.session === id("sub") && m.subagent !== undefined && m.tool === "todo_write", 30_000);
	const kid = childEnd.subagent;
	// The session announced its subagent (dsh's catalog: its id and label) before the subagent's work.
	const started = app.messages.findIndex((m) => m.type === "subagent_start" && m.session === id("sub") && m.subagent === kid);
	assert.ok(started >= 0 && started < app.messages.indexOf(childEnd), "no subagent_start before its work");
	assert.ok(app.messages.some((m) => m.type === "subagent_status" && m.subagent === kid && m.running === true), "no running status");
	assert.deepEqual(checked, [["subagent", false], ["todo_write", true]]);
	// The session works until its subagent is done too: no idle while it ran (the app would end the task, and its
	// gate would refuse the subagent's calls as "stopped").
	const at = app.messages.indexOf(childEnd);
	assert.ok(!app.messages.slice(0, at).some((m) => m.type === "idle" && m.session === id("sub")), "idle came while the subagent worked");
	const steps = app.messages.filter((m) => m.type === "tool_start" && m.session === id("sub")).map((m) => [m.tool, m.subagent ?? null]);
	assert.deepEqual(steps, [["subagent", null], ["todo_write", kid]]);
	assert.equal(childEnd.isError, false, childEnd.output);
	// The subagent's own words ("listed") are its section's, marked with its id; the session's answers are not.
	await app.waitFor("idle", (m) => m.session === id("sub"));
	const said = (sub) => app.messages.filter((m) => m.type === "assistant_end" && m.session === id("sub") && m.subagent === sub).map((m) => m.text).filter(Boolean);
	const answers = said(undefined);
	assert.ok(answers.length > 0 && !answers.includes("listed"), JSON.stringify(answers));
	assert.deepEqual(said(kid), ["listed"]);
	assert.ok(app.messages.some((m) => m.type === "subagent_status" && m.subagent === kid && m.running === false), "no idle status");
	// dsh woke the session when its subagent finished: the app gets that notice, before the session's reply to it.
	const trigger = app.messages.findIndex((m) => m.type === "trigger" && m.session === id("sub") && m.source === "subagent-settled");
	assert.ok(trigger > app.messages.indexOf(childEnd), "no trigger after the subagent finished");
	assert.ok(app.messages[trigger].text.length > 0, "the notice's text");
	// It names the subagent and how it ended.
	assert.equal(app.messages[trigger].subagent, kid);
	assert.equal(app.messages[trigger].outcome, "done");
	assert.ok(app.messages.slice(trigger).some((m) => m.type === "assistant_end" && m.session === id("sub")), "no reply after the notice");
	// And the session said idle only after that reply, as dsh's own hosts decide (agent idle, no subagent left).
	const idles = app.messages.map((m, i) => (m.type === "idle" && m.session === id("sub") ? i : -1)).filter((i) => i >= 0);
	assert.deepEqual(idles.length, 1, "one idle, at the end");
	assert.ok(idles[0] > trigger, "idle came before the subagent's result reached the session");
});

test("dsh's question reaches the app (ask_user), and the app's answer reaches the model", { skip }, async () => {
	let asked;
	app.handlers = {
		ask_user: (m) => { asked = m; return { answers: [{ id: "city", selected: [], custom: "Shanghai" }, { id: "unit", selected: ["°C", "°F"] }] }; },
	};
	await configure(id("ask"));
	app.send({ type: "prompt", session: id("ask"), text: "weather [[script:dsh_ask]]" });
	const end = await app.waitFor("tool_end", (m) => m.session === id("ask") && m.tool === "ask_user_question");
	await app.waitFor("idle", (m) => m.session === id("ask"));
	assert.equal(asked.session, id("ask"));
	assert.match(asked.ask, /^q\d+$/);
	assert.deepEqual(asked.questions[0], {
		id: "city", question: "Which city?", header: "City", multiSelect: false,
		options: [{ label: "Hangzhou (Recommended)", description: "Where you are" }, { label: "Beijing" }],
	});
	assert.equal(asked.questions[1].multiSelect, true);
	assert.equal(end.isError, false, end.output);
	assert.match(end.output, /Shanghai/);
	assert.match(end.output, /°F/);
});

test("a question the app can't answer fails the tool call, not the run", { skip }, async () => {
	app.handlers = { ask_user: () => ({ reason: "Nobody answered." }) };
	await configure(id("ask2"));
	app.send({ type: "prompt", session: id("ask2"), text: "weather [[script:dsh_ask]]" });
	const end = await app.waitFor("tool_end", (m) => m.session === id("ask2") && m.tool === "ask_user_question");
	await app.waitFor("idle", (m) => m.session === id("ask2"));
	assert.equal(end.isError, true);
	assert.match(end.output, /Nobody answered/);
});

test("Stop also stops the session's subagents", { skip }, async () => {
	let release;
	const held = new Promise((r) => { release = r; });
	// The subagent's call waits at the app's gate, as a paused session's would.
	app.handlers = { before_tool: (m) => (m.subagent ? held.then(() => null) : null) };
	await configure(id("stop"));
	app.send({ type: "prompt", session: id("stop"), text: "plan [[script:delegate]]" });
	await app.waitFor("before_tool", (m) => m.session === id("stop") && m.subagent !== undefined, 30_000);
	app.send({ type: "abort", session: id("stop") });
	await new Promise((r) => setTimeout(r, 300));
	release();
	await new Promise((r) => setTimeout(r, 500));
	const child = app.messages.filter((m) => m.type === "tool_end" && m.session === id("stop") && m.tool === "↳ todo_write");
	assert.ok(child.length === 0 || child[0].isError, "the subagent's call didn't run after Stop");
	assert.ok(!app.messages.some((m) => m.type === "assistant_end" && m.session === id("stop") && m.text === "listed"));
});

test("a message while a subagent works reaches the session at once, not after the subagent", { skip }, async () => {
	let release;
	const held = new Promise((r) => { release = r; });
	// The subagent's call waits at the app's gate: the session's own agent is idle meanwhile, its run goes on.
	app.handlers = { before_tool: (m) => (m.subagent ? held.then(() => null) : null) };
	await configure(id("talk"));
	app.send({ type: "prompt", session: id("talk"), text: "plan [[script:delegate]]" });
	await app.waitFor("before_tool", (m) => m.session === id("talk") && m.subagent !== undefined, 30_000);
	app.send({ type: "steer", session: id("talk"), text: "how is it going?" });
	// dsh wakes the session's agent for it (as dsh's own chat does): delivered while the subagent still waits.
	await app.waitFor("message_end", (m) => m.session === id("talk") && m.role === "user" && m.text === "how is it going?", 30_000);
	assert.ok(!app.messages.some((m) => m.type === "steer_late" && m.session === id("talk")), "handed back as late while the run went on");
	assert.ok(!app.messages.some((m) => m.type === "idle" && m.session === id("talk")), "idle while the subagent worked");
	release();
	await app.waitFor("idle", (m) => m.session === id("talk"), 60_000);
});

test("a message after the run is over is handed back to the app (steer_late)", { skip }, async () => {
	app.handlers = {};
	await configure(id("late"));
	app.send({ type: "prompt", session: id("late"), text: "hi" });
	await app.waitFor("idle", (m) => m.session === id("late"));
	app.send({ type: "steer", session: id("late"), text: "one more thing" });
	await app.waitFor("steer_late", (m) => m.session === id("late") && m.text === "one more thing", 10_000);
});

test("a native call the app blocks is denied", { skip }, async () => {
	app.handlers = { before_tool: (m) => (m.tool === "bash" ? { block: true, terminate: false, reason: "Paused by the user." } : null) };
	await configure(id("n2"));
	app.send({ type: "prompt", session: id("n2"), text: "greet [[script:dsh_bash]]" });
	await app.waitFor("idle", (m) => m.session === id("n2"));
	const end = app.messages.find((m) => m.type === "tool_end" && m.session === id("n2"));
	assert.equal(end.isError, true);
	assert.match(end.output, /Paused by the user/);
});

test("a provider added in the app while dsh runs is a route at once (no restart)", { skip }, async () => {
	app.send({ type: "routes", providers: {
		mock: { displayName: "Mock", api: "openai-responses", apiKeyEnv: "WEIASSIST_KEY_MOCK", baseURL: mock.url, models: [{ id: "gpt-6.1-terra", input: ["text", "image"] }] },
		"custom-y": { displayName: "My server", api: "openai-completions", apiKeyEnv: "WEIASSIST_KEY_MOCK", baseURL: mock.url,
			models: [{ id: "qwen-9", contextWindow: 32768, input: ["text"], compat: { supportsDeveloperRole: false, supportsStore: false } }] },
	} });
	const before = mock.requests.length;
	app.send({ type: "configure", session: id("cy"), provider: "custom-y", modelId: "qwen-9", toolSpecs: [] });
	const conf = await app.waitFor("configured", (m) => m.session === id("cy"));
	assert.equal(conf.provider, "custom-y");
	app.send({ type: "prompt", session: id("cy"), text: "hello" });
	const end = await app.waitFor("assistant_end", (m) => m.session === id("cy"));
	assert.equal(end.text, MOCK_REPLY);
	const req = mock.requests.slice(before).find((r) => r.url.endsWith("/chat/completions"));
	assert.ok(req, "the custom route spoke its own protocol (chat completions)");
	assert.equal(JSON.parse(req.body).model, "qwen-9");
});

test("the app's prompt goes to the model, without the section for a tool dsh has its own of", { skip }, async () => {
	const sections = [
		{ id: "chat", text: "You are Ada, a helpful assistant." },
		{ id: "bash", text: "\n\nLinux (Termux): use pkg install.", forTool: "bash" },
		{ id: "channel", text: "\n\nThe user is talking to you from Telegram." },
	];
	const before = mock.requests.length;
	await configure(id("pr"), { toolSpecs: specs("bash"), promptSections: sections });
	app.send({ type: "prompt", session: id("pr"), text: "hello" });
	await app.waitFor("idle", (m) => m.session === id("pr"));
	const system = JSON.stringify(JSON.parse(mock.requests[before].body).input[0]);
	assert.match(system, /You are Ada, a helpful assistant\.\\n\\nThe user is talking to you from Telegram\./);
	assert.doesNotMatch(system, /Termux/, "dsh runs its own bash: no Termux guide");
	assert.match(system, /DeepSeek Harness/, "dsh's own words stay");
});

test("an image attached to the user's message reaches the model", { skip }, async () => {
	const jpeg = "/9j/4AAQSkZJRgABAQEASABIAAD/2wBDAP//////////////////////////////////////////////////////////////////////////////////////wAALCAABAAEBAREA/8QAFAABAAAAAAAAAAAAAAAAAAAAA//EABQQAQAAAAAAAAAAAAAAAAAAAAD/2gAIAQEAAD8AN//Z";
	await configure(id("att"));
	const before = mock.requests.length;
	app.send({ type: "prompt", session: id("att"), text: "what is this?", content: [
		{ type: "text", text: "<attachment name=\"cat.jpg\" type=\"image\"/>" },
		{ type: "image", data: jpeg, mimeType: "image/jpeg" },
		{ type: "image", data: "AAAA", mimeType: "image/heic" },
	] });
	await app.waitFor("idle", (m) => m.session === id("att"));
	const user = JSON.stringify(JSON.parse(mock.requests[before].body).input.find((i) => i.role === "user"));
	assert.match(user, /what is this\?/);
	assert.match(user, /cat\.jpg/);
	assert.match(user, /input_image/, "the picture went to the model");
	assert.match(user, /image\/heic, that this engine couldn't take/, "an unknown format becomes a note");
});

test("the app's thinking level becomes the nearest effort the model offers", { skip }, async () => {
	// A model that offers low, medium and high (its wire spellings the same).
	app.send({ type: "routes", providers: { mock: {
		displayName: "Mock", api: "openai-responses", apiKeyEnv: "WEIASSIST_KEY_MOCK", baseURL: mock.url,
		models: [{ id: "gpt-6.1-terra", input: ["text", "image"], reasoningEfforts: { low: "low", medium: "medium", high: "high" } }],
	} } });
	const effortOf = async (level, name) => {
		await configure(id(name), { thinkingLevel: level });
		const before = mock.requests.length;
		app.send({ type: "prompt", session: id(name), text: "think" });
		await app.waitFor("idle", (m) => m.session === id(name));
		return JSON.parse(mock.requests[before].body).reasoning?.effort;
	};
	assert.equal(await effortOf("high", "th1"), "high");
	assert.equal(await effortOf("off", "th2"), "low", "off on a model that always reasons: its lowest");
	assert.equal(await effortOf("max", "th3"), "high", "above what it offers: its highest");
	assert.equal(await effortOf("minimal", "th4"), "low");
});

test("a session comes back after its agent is gone (dispose, then configure again)", { skip }, async () => {
	app.send({ type: "dispose", session: id("c1") });
	const conf = await configure(id("c1"), { messages: [{ dsh: id("c1") }] });
	assert.equal(conf.messages, 1, `restored from dsh own log: ${conf.resumeError}`);
	const before = mock.requests.length;
	app.send({ type: "prompt", session: id("c1"), text: "again" });
	await app.waitFor("idle", (m) => m.session === id("c1") && app.messages.filter((x) => x.type === "idle" && x.session === id("c1")).length >= 2);
	assert.match(mock.requests[before].body, /hello/, "the earlier conversation was in the request");
});
