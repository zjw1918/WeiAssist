// A tiny stand-in for api.openai.com/v1/responses, for testing without spending tokens.
// It logs every request it receives (path, headers, body) so we can see exactly what Pi sent,
// and streams back a short Responses-API answer. Usage: node tools/mock-openai.mjs [port]
// On a phone: adb reverse tcp:8787 tcp:8787, then use base URL http://127.0.0.1:8787/v1
import { createServer } from "node:http";

const port = Number(process.argv[2] ?? 8787);
const reply = "你好！我是假的 OpenAI（本地模拟服务器）。";
const cors = {
	"access-control-allow-origin": "*",
	"access-control-allow-headers": "*",
	"access-control-allow-methods": "GET, POST, OPTIONS",
};

createServer((req, res) => {
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
		console.log(`\n=== ${new Date().toISOString()} ${req.method} ${req.url}`);
		console.log(JSON.stringify(shown, null, 2));
		console.log(body);
		if (req.method !== "POST" || !req.url?.endsWith("/responses")) {
			res.writeHead(404, cors).end();
			return;
		}
		const model = JSON.parse(body || "{}").model ?? "mock";
		const id = `resp_mock_${Date.now()}`;
		const msg = { id: "msg_mock", type: "message", role: "assistant", status: "in_progress", content: [] };
		const part = { type: "output_text", text: "", annotations: [] };
		const usage = { input_tokens: 12, output_tokens: 9, total_tokens: 21, input_tokens_details: { cached_tokens: 0 }, output_tokens_details: { reasoning_tokens: 0 } };
		const base = { id, object: "response", model, created_at: Math.floor(Date.now() / 1000) };
		const events = [
			{ type: "response.created", response: { ...base, status: "in_progress", output: [] } },
			{ type: "response.output_item.added", output_index: 0, item: msg },
			{ type: "response.content_part.added", item_id: msg.id, output_index: 0, content_index: 0, part },
			...[...reply].map((ch) => ({ type: "response.output_text.delta", item_id: msg.id, output_index: 0, content_index: 0, delta: ch })),
			{ type: "response.output_text.done", item_id: msg.id, output_index: 0, content_index: 0, text: reply },
			{ type: "response.content_part.done", item_id: msg.id, output_index: 0, content_index: 0, part: { ...part, text: reply } },
			{ type: "response.output_item.done", output_index: 0, item: { ...msg, status: "completed", content: [{ ...part, text: reply }] } },
			{ type: "response.completed", response: { ...base, status: "completed", output: [{ ...msg, status: "completed", content: [{ ...part, text: reply }] }], usage } },
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
		}, 40);
	});
}).listen(port, () => console.log(`mock OpenAI on http://127.0.0.1:${port}/v1`));
