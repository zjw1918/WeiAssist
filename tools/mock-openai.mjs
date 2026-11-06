// A tiny stand-in for api.openai.com/v1/responses, for testing without spending tokens.
// It records every request it receives (path, headers, body) so we can see exactly what Pi sent,
// and streams back a short Responses-API answer.
//
//   CLI:   node tools/mock-openai.mjs [port]      (default 8787; logs each request)
//          on a phone: adb reverse tcp:8787 tcp:8787, then use base URL http://127.0.0.1:8787/v1
//   Tests: const mock = await startMock(0, { log: () => {}, delayMs: 1 }); … mock.requests … await mock.close()
import { createServer } from "node:http";
import { pathToFileURL } from "node:url";

export const MOCK_REPLY = "你好！我是假的 OpenAI（本地模拟服务器）。";

/**
 * Scripted tool use for tests: a user message containing [[script:NAME]] gets these turns in order,
 * one per model request (counted by the tool results already in the conversation).
 */
export const SCRIPTS = {
	tap: [{ tool: "tap", args: { id: 51 } }, { text: "tapped it" }],
	send: [{ tool: "read_screen", args: {} }, { tool: "tap", args: { id: 2 } }, { text: "sent" }],
	buy_at: [{ tool: "tap_at", args: { x: 900, y: 2200 } }, { text: "ordered" }],
	shot: [{ tool: "screenshot", args: {} }, { text: "looked" }],
	// A conversation from Telegram sends a file it made in Termux.
	send_file: [{ tool: "send_file", args: { path: "chart.png", caption: "The chart" } }, { text: "sent you the chart" }],
	screens: [1, 2, 3, 4].map(() => ({ tool: "read_screen", args: {} })).concat([{ text: "read four screens" }]),
	// A whole little task on the phone (used on a device: a session's own virtual display).
	settings: [
		{ tool: "open_app", args: { app: "com.android.settings" } },
		{ tool: "read_screen", args: {} },
		{ tool: "swipe", args: { direction: "up" } },
		{ tool: "read_screen", args: {} },
		{ tool: "screenshot", args: {} },
		{ tool: "press_key", args: { key: "back" } },
		{ text: "worked in Settings" },
	],
	// Slow browsing, to watch live previews on a device.
	browse_settings: [{ tool: "open_app", args: { app: "com.android.settings" } }].concat(
		[1, 2, 3, 4, 5, 6].flatMap(() => [{ tool: "swipe", args: { direction: "up" } }, { tool: "wait", args: { ms: 2500 } }]),
		[{ text: "browsed Settings" }],
	),
	browse_gmail: [{ tool: "open_app", args: { app: "com.google.android.gm" } }].concat(
		[1, 2, 3, 4, 5, 6].flatMap((i) => [{ tool: "swipe", args: { direction: i % 3 ? "up" : "down" } }, { tool: "wait", args: { ms: 2500 } }]),
		[{ text: "browsed Gmail" }],
	),
	// The bash tool (Termux): one command, then an answer.
	bash: [
		{ tool: "bash", args: { command: "curl -s 'wttr.in/Hangzhou?format=3'", timeout: 30 } },
		{ text: "Hangzhou: rain." },
	],
	// The supervisor: start a chat session, wait for it, pass something on, read it back.
	supervise: [
		{ tool: "list_sessions", args: {} },
		{ tool: "create_session", args: { task: "Say hello in one word.", phone: false, title: "Hello helper" } },
		{ tool: "wait_for", args: { ids: ["Hello helper"], timeout_seconds: 60 } },
		{ tool: "message_session", args: { id: "Hello helper", text: "Now say goodbye in one word." } },
		{ tool: "wait_for", args: { ids: ["Hello helper"], timeout_seconds: 60 } },
		{ tool: "get_session", args: { id: "Hello helper" } },
		{ text: "The helper said hello and goodbye." },
	],
	// Two phone sessions at once, coordinated by the supervisor.
	supervise_phone: [
		{ tool: "create_session", args: { task: "do it [[script:settings]]", phone: true, title: "Settings worker" } },
		{ tool: "create_session", args: { task: "do it [[script:browse_gmail]]", phone: true, title: "Gmail worker" } },
		{ tool: "list_sessions", args: {} },
		{ tool: "pause_session", args: { id: "Gmail worker" } },
		{ tool: "wait_for", args: { ids: ["Settings worker"], timeout_seconds: 120 } },
		{ tool: "resume_session", args: { id: "Gmail worker" } },
		{ tool: "wait_for", args: { ids: ["Gmail worker"], timeout_seconds: 120 } },
		{ tool: "delete_session", args: { id: "Settings worker" } },
		{ text: "Both workers finished." },
	],
	creates: Array.from({ length: 9 }, (_, i) => ({ tool: "create_session", args: { task: `t${i}`, phone: false } })).concat([{ text: "tried nine" }]),
	// Open a web address in Chrome: tap the address bar, type, press Enter (on a device).
	// "$find:<regex>" is the id of the first element in the latest read_screen whose line matches.
	chrome_url: [
		{ tool: "open_app", args: { app: "com.android.chrome" } },
		{ tool: "read_screen", args: {} },
		{ tool: "tap", args: { id: "$find:id=(search_box_text|url_bar)" } },
		{ tool: "read_screen", args: {} },
		{ tool: "type_text", args: { id: "$find:editable", text: "example.com", submit: true } },
		{ tool: "wait", args: { ms: 3000 } },
		{ tool: "read_screen", args: {} },
		{ text: "opened example.com" },
	],
	actions: [
		{ tool: "tap", args: { id: 1 } },
		{ tool: "press_key", args: { key: "back" } },
		{ tool: "swipe", args: { direction: "up" } },
		{ text: "done" },
	],
	notes: [
		{ tool: "read_screen", args: {} },
		{ tool: "note", args: { text: "email 1: from Ann, ship by Friday" } },
		{ tool: "read_screen", args: {} },
		{ tool: "read_screen", args: {} },
		{ tool: "read_screen", args: {} },
		{ text: "summarised from notes" },
	],
	submit: [{ tool: "type_text", args: { id: 3, text: "example.com", submit: true } }, { tool: "press_key", args: { key: "enter" } }, { text: "submitted" }],
	// DeepSeek Harness's own tools (an AI core's native capabilities): its bash, and a subagent that keeps a todo list.
	dsh_bash: [{ tool: "bash", args: { description: "Print a greeting", command: "echo hi from dsh" } }, { text: "ran it" }],
	dsh_where: [{ tool: "bash", args: { description: "Show the working folder", command: "pwd; echo made by dsh > from-dsh.txt; ls" } }, { text: "looked around" }],
	delegate: [{ tool: "subagent", args: { description: "Keep a todo list", prompt: "Write the todo list [[script:child_todo]]" } }, { text: "delegated" }],
	child_todo: [{ tool: "todo_write", args: { todos: [{ content: "Check the inbox", status: "pending" }] } }, { text: "listed" }],
	// Performance on a device: about 20 s of streamed reasoning, then a ~250-line code answer streamed over ~20 s
	// (chunks of 12 characters, one per event).
	perf: [{ think: perfThinking(), text: perfAnswer(), chunk: 12 }],
	douban: [
		{ tool: "open_app", args: { app: "com.douban.frodo" } },
		{ tool: "read_screen", args: {} },
		{ tool: "wait", args: { ms: 3000 } },
		{ tool: "read_screen", args: {} },
		{ tool: "screenshot", args: {} },
		{ text: "looked at Douban" },
	],
};

function perfThinking() {
	const s = "The user wants a small library catalog in Kotlin. I should model books, members and loans, keep it in memory, " +
		"and show a short demo at the end. Let me think about the classes: Book with isbn, title, author, year and copies; " +
		"Member with an id and a name; Loan linking them with a due date. The catalog needs add, find, lend and return. ";
	return s.repeat(10);
}

function perfAnswer() {
	const lines = ["Here is the catalog:", "", "```kotlin", "import java.time.LocalDate", ""];
	for (let i = 0; lines.length < 250; i++) {
		lines.push(`/** Book number ${i}: a title held by the library. */`);
		lines.push(`data class Book${i}(val isbn: String, val title: String, val copies: Int = ${i % 4 + 1}) {`);
		lines.push(`    fun available(loans: List<String>): Boolean = loans.count { it == isbn } < copies // ${i}`);
		lines.push(`    override fun toString() = "$title ($isbn)"`);
		lines.push("}");
		lines.push("");
	}
	lines.push("```", "", "Run it with `kotlinc catalog.kt -include-runtime -d catalog.jar`.");
	return lines.join("\n");
}

function scriptedTurn(body) {
	const input = JSON.stringify(body.input ?? []);
	const name = input.match(/\[\[script:(\w+)\]\]/)?.[1];
	if (!name) return null;
	const outputs = (body.input ?? []).filter((i) => i.type === "function_call_output");
	const turn = SCRIPTS[name][outputs.length] ?? { text: "end of script" };
	if (!turn.args) return turn;
	// Resolve "$find:<regex>" against the latest read_screen.
	const screen = outputs.map((o) => (typeof o.output === "string" ? o.output : JSON.stringify(o.output))).findLast((o) => /app: \S+  screen:/.test(o)) ?? "";
	const args = Object.fromEntries(Object.entries(turn.args).map(([k, v]) => {
		const re = typeof v === "string" && v.startsWith("$find:") ? new RegExp(v.slice(6)) : null;
		if (!re) return [k, v];
		const line = screen.split(/\\n|\n/).find((l) => /^\[\d+\]/.test(l) && re.test(l));
		return [k, line ? Number(line.match(/^\[(\d+)\]/)[1]) : -1];
	}));
	return { ...turn, args };
}

/**
 * What GET /models lists: one entry with a server-side context length (as vLLM reports it), one id
 * Pi's catalog knows (as a relay passes it through), one the app knows nothing about.
 */
export const MOCK_MODELS = [
	{ id: "mock-vision", max_model_len: 32768 },
	{ id: "deepseek-flash" },
	{ id: "mock-chat" },
];

/** A streamed chat completion that answers MOCK_REPLY, chunk by chunk, as OpenAI-compatible servers do. */
function chatCompletion(res, body, delayMs) {
	res.writeHead(200, { ...cors, "content-type": "text/event-stream", "cache-control": "no-cache" });
	const base = { id: `chatcmpl_mock_${Date.now()}`, object: "chat.completion.chunk", created: Math.floor(Date.now() / 1000), model: body.model ?? "mock" };
	const chunks = [
		{ ...base, choices: [{ index: 0, delta: { role: "assistant", content: "" }, finish_reason: null }] },
		...[...MOCK_REPLY].map((ch) => ({ ...base, choices: [{ index: 0, delta: { content: ch }, finish_reason: null }] })),
		{ ...base, choices: [{ index: 0, delta: {}, finish_reason: "stop" }] },
		{ ...base, choices: [], usage: { prompt_tokens: 12, completion_tokens: 9, total_tokens: 21 } },
	];
	let i = 0;
	const tick = () => {
		if (i < chunks.length) {
			res.write(`data: ${JSON.stringify(chunks[i++])}\n\n`);
			setTimeout(tick, delayMs);
		} else res.end("data: [DONE]\n\n");
	};
	tick();
}

const cors = {
	"access-control-allow-origin": "*",
	"access-control-allow-headers": "*",
	"access-control-allow-methods": "GET, POST, OPTIONS",
};

/** Start the mock. Port 0 picks a free port; the resolved `url` is the base URL for Pi. */
export function startMock(port = 0, { log = console.log, delayMs = 40 } = {}) {
	const requests = [];
	const server = createServer((req, res) => handle(req, res, { requests, log, delayMs }));
	return new Promise((resolve) =>
		server.listen(port, "127.0.0.1", () => {
			const url = `http://127.0.0.1:${server.address().port}/v1`;
			log(`mock OpenAI on ${url}`);
			resolve({ url, requests, close: () => new Promise((r) => server.close(r)) });
		}),
	);
}

function handle(req, res, { requests, log, delayMs }) {
	if (req.method === "OPTIONS") {
		res.writeHead(204, cors).end();
		return;
	}
	let body = "";
	req.on("data", (c) => (body += c));
	req.on("end", () => {
		const auth = req.headers.authorization ?? "";
		const shown = Object.fromEntries(
			Object.entries(req.headers).map(([k, v]) => [k, k === "authorization" ? `${auth.slice(0, 10)}…(${auth.length} chars)` : v]),
		);
		log(`\n=== ${new Date().toISOString()} ${req.method} ${req.url}`);
		log(JSON.stringify(shown, null, 2));
		log(body);
		requests.push({ method: req.method, url: req.url, headers: req.headers, body });
		// An OpenAI-compatible server's model list and chat completions, for the app's custom providers.
		if (req.method === "GET" && req.url?.endsWith("/models")) {
			res.writeHead(200, { ...cors, "content-type": "application/json" });
			res.end(JSON.stringify({ object: "list", data: MOCK_MODELS.map((m) => ({ ...m, object: "model", owned_by: "mock" })) }));
			return;
		}
		if (req.method === "POST" && req.url?.endsWith("/chat/completions")) {
			chatCompletion(res, JSON.parse(body || "{}"), delayMs);
			return;
		}
		if (req.method !== "POST" || !req.url?.endsWith("/responses")) {
			res.writeHead(404, cors).end();
			return;
		}
		const parsed = JSON.parse(body || "{}");
		// [[overflow]]: answer as OpenAI does when the input is over the context window, until the
		// conversation has been compacted (a summary request itself is answered normally).
		const summarising = body.includes("You compact a conversation");
		if (body.includes("[[overflow]]") && !summarising && !body.includes("[Summary of the earlier conversation")) {
			res.writeHead(400, { ...cors, "content-type": "application/json" });
			res.end(JSON.stringify({ error: { message: "Your input exceeds the context window of this model. Please adjust your input and try again.", type: "invalid_request_error", code: "context_length_exceeded" } }));
			return;
		}
		const model = parsed.model ?? "mock";
		const turn = summarising ? { text: "SUMMARY: the user likes llamas" } : scriptedTurn(parsed);
		const id = `resp_mock_${Date.now()}`;
		const msg = { id: "msg_mock", type: "message", role: "assistant", status: "in_progress", content: [] };
		const part = { type: "output_text", text: "", annotations: [] };
		const usage = { input_tokens: 12, output_tokens: 9, total_tokens: 21, input_tokens_details: { cached_tokens: 0 }, output_tokens_details: { reasoning_tokens: 0 } };
		const base = { id, object: "response", model, created_at: Math.floor(Date.now() / 1000) };
		const done = { ...msg, status: "completed", content: [{ ...part, text: turn?.text ?? MOCK_REPLY }] };
		const call = turn?.tool && {
			id: `fc_${Date.now()}`, type: "function_call", status: "completed",
			call_id: `call_${Date.now()}`, name: turn.tool, arguments: JSON.stringify(turn.args),
		};
		const text = turn?.text ?? MOCK_REPLY;
		const chunk = turn?.chunk ?? 1;
		const pieces = (t) => { const out = []; for (let k = 0; k < t.length; k += chunk) out.push(t.slice(k, k + chunk)); return out; };
		const think = turn?.think;
		const reasoning = think && { id: "rs_mock", type: "reasoning", summary: [] };
		const thinkEvents = think ? [
			{ type: "response.output_item.added", output_index: 0, item: reasoning },
			{ type: "response.reasoning_summary_part.added", item_id: reasoning.id, output_index: 0, summary_index: 0, part: { type: "summary_text", text: "" } },
			...pieces(think).map((d) => ({ type: "response.reasoning_summary_text.delta", item_id: reasoning.id, output_index: 0, summary_index: 0, delta: d })),
			{ type: "response.reasoning_summary_text.done", item_id: reasoning.id, output_index: 0, summary_index: 0, text: think },
			{ type: "response.reasoning_summary_part.done", item_id: reasoning.id, output_index: 0, summary_index: 0, part: { type: "summary_text", text: think } },
			{ type: "response.output_item.done", output_index: 0, item: { ...reasoning, summary: [{ type: "summary_text", text: think }] } },
		] : [];
		const at = think ? 1 : 0;
		const events = call ? [
			{ type: "response.created", response: { ...base, status: "in_progress", output: [] } },
			{ type: "response.output_item.added", output_index: 0, item: { ...call, status: "in_progress", arguments: "" } },
			{ type: "response.function_call_arguments.delta", item_id: call.id, output_index: 0, delta: call.arguments },
			{ type: "response.function_call_arguments.done", item_id: call.id, output_index: 0, arguments: call.arguments },
			{ type: "response.output_item.done", output_index: 0, item: call },
			{ type: "response.completed", response: { ...base, status: "completed", output: [call], usage } },
		] : [
			{ type: "response.created", response: { ...base, status: "in_progress", output: [] } },
			...thinkEvents,
			{ type: "response.output_item.added", output_index: at, item: msg },
			{ type: "response.content_part.added", item_id: msg.id, output_index: at, content_index: 0, part },
			...(chunk > 1 ? pieces(text) : [...text]).map((d) => ({ type: "response.output_text.delta", item_id: msg.id, output_index: at, content_index: 0, delta: d })),
			{ type: "response.output_text.done", item_id: msg.id, output_index: at, content_index: 0, text },
			{ type: "response.content_part.done", item_id: msg.id, output_index: at, content_index: 0, part: { ...part, text } },
			{ type: "response.output_item.done", output_index: at, item: done },
			{ type: "response.completed", response: { ...base, status: "completed", output: [...(think ? [{ ...reasoning, summary: [{ type: "summary_text", text: think }] }] : []), done], usage } },
		];
		res.writeHead(200, { ...cors, "content-type": "text/event-stream", "cache-control": "no-cache" });
		let i = 0;
		const timer = setInterval(() => {
			const e = events[i++];
			res.write(`event: ${e.type}\ndata: ${JSON.stringify({ ...e, sequence_number: i })}\n\n`);
			if (i === events.length) {
				clearInterval(timer);
				res.end();
			}
		}, delayMs);
	});
}

if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) startMock(Number(process.argv[2] ?? 8787));
