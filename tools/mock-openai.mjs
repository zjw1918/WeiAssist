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
};

function scriptedTurn(body) {
	const input = JSON.stringify(body.input ?? []);
	const name = input.match(/\[\[script:(\w+)\]\]/)?.[1];
	if (!name) return null;
	const done = (body.input ?? []).filter((i) => i.type === "function_call_output").length;
	return SCRIPTS[name][done] ?? { text: "end of script" };
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
		if (req.method !== "POST" || !req.url?.endsWith("/responses")) {
			res.writeHead(404, cors).end();
			return;
		}
		const parsed = JSON.parse(body || "{}");
		const model = parsed.model ?? "mock";
		const turn = scriptedTurn(parsed);
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
		const events = call ? [
			{ type: "response.created", response: { ...base, status: "in_progress", output: [] } },
			{ type: "response.output_item.added", output_index: 0, item: { ...call, status: "in_progress", arguments: "" } },
			{ type: "response.function_call_arguments.delta", item_id: call.id, output_index: 0, delta: call.arguments },
			{ type: "response.function_call_arguments.done", item_id: call.id, output_index: 0, arguments: call.arguments },
			{ type: "response.output_item.done", output_index: 0, item: call },
			{ type: "response.completed", response: { ...base, status: "completed", output: [call], usage } },
		] : [
			{ type: "response.created", response: { ...base, status: "in_progress", output: [] } },
			{ type: "response.output_item.added", output_index: 0, item: msg },
			{ type: "response.content_part.added", item_id: msg.id, output_index: 0, content_index: 0, part },
			...[...text].map((ch) => ({ type: "response.output_text.delta", item_id: msg.id, output_index: 0, content_index: 0, delta: ch })),
			{ type: "response.output_text.done", item_id: msg.id, output_index: 0, content_index: 0, text },
			{ type: "response.content_part.done", item_id: msg.id, output_index: 0, content_index: 0, part: { ...part, text } },
			{ type: "response.output_item.done", output_index: 0, item: done },
			{ type: "response.completed", response: { ...base, status: "completed", output: [done], usage } },
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
