// The server for core's HttpClientBenchmark (an instrumented test that compares HTTP clients on a device).
//
//   node tools/http-bench-server.mjs [port]     (default 8790)
//   adb reverse tcp:8790 tcp:8790
//   ./gradlew :core:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.lanqi.weiassist.core.HttpClientBenchmark
//
// GET  /small            a 1 KB JSON answer (like a /models list)
// GET  /stream?n=N       N server-sent events of ~120 bytes, flushed one by one (like a model's token stream)
// GET  /bulk?mb=N        N MiB of bytes (a file download)
// POST /upload           reads the body, answers with its length (a file or screenshot upload)
import { createServer } from "node:http";

const port = Number(process.argv[2] ?? 8790);
const small = JSON.stringify({ object: "list", data: Array.from({ length: 12 }, (_, i) => ({ id: `model-${i}-${"x".repeat(40)}`, object: "model" })) });
const event = `data: ${JSON.stringify({ type: "response.output_text.delta", delta: "token ".repeat(12) })}\n\n`;
const chunk = Buffer.alloc(64 * 1024, 97);

createServer((req, res) => {
	const url = new URL(req.url, "http://x");
	if (req.method === "GET" && url.pathname === "/small") {
		res.writeHead(200, { "content-type": "application/json" });
		return res.end(small);
	}
	if (req.method === "GET" && url.pathname === "/stream") {
		const n = Number(url.searchParams.get("n") ?? 500);
		res.writeHead(200, { "content-type": "text/event-stream", "cache-control": "no-cache" });
		let i = 0;
		const next = () => {
			// One event per write, so each arrives as its own chunk, as tokens do.
			while (i < n) {
				i++;
				if (!res.write(event)) return res.once("drain", next);
				if (i % 25 === 0) return setImmediate(next);
			}
			res.end("data: [DONE]\n\n");
		};
		return next();
	}
	if (req.method === "GET" && url.pathname === "/bulk") {
		const total = Number(url.searchParams.get("mb") ?? 20) * 1024 * 1024;
		res.writeHead(200, { "content-type": "application/octet-stream", "content-length": total });
		let sent = 0;
		const next = () => {
			while (sent < total) {
				const n = Math.min(chunk.length, total - sent);
				sent += n;
				if (!res.write(n === chunk.length ? chunk : chunk.subarray(0, n))) return res.once("drain", next);
			}
			res.end();
		};
		return next();
	}
	if (req.method === "POST" && url.pathname === "/upload") {
		let n = 0;
		req.on("data", (d) => { n += d.length; });
		req.on("end", () => { res.writeHead(200, { "content-type": "application/json" }); res.end(JSON.stringify({ bytes: n })); });
		return;
	}
	res.writeHead(404);
	res.end();
}).listen(port, "127.0.0.1", () => console.log(`http bench server on 127.0.0.1:${port}`));
