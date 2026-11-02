// Compaction: a long conversation would outgrow the model's context window, and every message after
// that would fail the same way. pi-agent-core leaves this to the app, so before a task (and once after
// a "context too long" error) the older part of a session's conversation becomes one summary message,
// written by the session's own model; the recent turns stay as they are.
import type { AgentMessage } from "@earendil-works/pi-agent-core";
import type { Context, Model } from "@earendil-works/pi-ai";
import { pruneScreens } from "./phone-tools.ts";

/** Compact when the conversation is estimated above this share of the context window. */
export const COMPACT_AT = 0.7;
/** The recent part kept word for word: about this share of the window. */
const KEEP_RECENT = 0.25;
/** Characters per token, roughly (English text and JSON; Chinese packs more per token, so this errs high). */
const CHARS_PER_TOKEN = 4;
const SUMMARY_PREFIX = "[Summary of the earlier conversation, written to save space]\n";

const chars = (m: AgentMessage) => JSON.stringify((m as any).content ?? "").length;

/**
 * Tokens the next request will carry, about: the last reply's reported usage (what the provider counted,
 * after screen pruning) plus what came after it; a character estimate when no usage is known.
 */
export function estimateTokens(messages: AgentMessage[]): number {
	let i = messages.length - 1;
	while (i >= 0 && !((messages[i] as any).role === "assistant" && (messages[i] as any).usage?.input)) i--;
	const tail = messages.slice(i + 1).reduce((n, m) => n + chars(m), 0) / CHARS_PER_TOKEN;
	if (i < 0) return Math.ceil(tail);
	const u = (messages[i] as any).usage;
	return Math.ceil((u.input ?? 0) + (u.cacheRead ?? 0) + (u.cacheWrite ?? 0) + (u.output ?? 0) + tail);
}

export const needsCompaction = (messages: AgentMessage[], contextWindow: number) =>
	contextWindow > 0 && estimateTokens(messages) > contextWindow * COMPACT_AT;

/**
 * Where the kept part starts: at a user message (so a tool call is never split from its result), as early
 * as fits in [keepChars]. [keepTask]: the latest user message is the task under way and is always kept;
 * otherwise (before a new task) everything may go into the summary. 0 when there is nothing to compact.
 */
export function splitPoint(messages: AgentMessage[], keepChars: number, keepTask: boolean): number {
	let size = 0;
	let latest = keepTask ? -1 : messages.length; // the latest place the cut may be
	let cut = -1;
	for (let i = messages.length - 1; i >= 0; i--) {
		size += chars(messages[i]);
		if ((messages[i] as any).role !== "user") continue;
		if (latest < 0) latest = i;
		if (size <= keepChars) cut = i;
		else break;
	}
	if (latest < 0) return 0;
	return cut < 0 ? latest : cut; // even the latest part is over budget: cut there all the same
}

/** The older messages as plain text for the summariser: screens and images out, long tool output cut. */
export function transcriptText(messages: AgentMessage[], maxChars: number): string {
	const clip = (s: string, n: number) => (s.length > n ? `${s.slice(0, n)}…[cut]` : s);
	const lines = pruneScreens(messages, 0).map((m: any) => {
		const parts = (Array.isArray(m.content) ? m.content : [{ type: "text", text: String(m.content ?? "") }]).map((c: any) => {
			if (c.type === "text") return c.text;
			if (c.type === "toolCall") return `(calls ${c.name} ${clip(JSON.stringify(c.arguments ?? {}), 600)})`;
			if (c.type === "image") return "(image)";
			return "";
		}).filter(Boolean).join("\n");
		if (m.role === "toolResult") return `Tool result (${m.toolName}): ${clip(parts, 800)}`;
		return `${m.role === "user" ? "User" : "Assistant"}: ${parts}`;
	});
	// Oldest first out if it is still too long for one request.
	let text = lines.join("\n\n");
	if (text.length > maxChars) text = "…[earliest part left out]\n" + text.slice(text.length - maxChars);
	return text;
}

const SUMMARY_PROMPT = `You compact a conversation between a user and an assistant (that may operate the user's phone or run
commands) so it can go on in a smaller context. Write a summary that lets the assistant continue as if it remembered
everything that matters:
- what the user asked for, their preferences and decisions, and anything they told it to remember;
- what was done, found and answered (keep facts, names, numbers, dates, links, file paths, ids exactly);
- anything still open or in progress.
Leave out step-by-step navigation and screen contents unless a result depends on them. Use the user's language.
Answer with the summary only.`;

type Complete = (model: Model<any>, context: Context, options?: { signal?: AbortSignal }) => Promise<any>;

/**
 * The conversation with its older part replaced by a summary, or null if there is nothing to compact
 * or the summary failed (the caller then goes on as before).
 */
export async function compact(all: AgentMessage[], model: Model<any>, complete: Complete, keepTask: boolean, signal?: AbortSignal): Promise<AgentMessage[] | null> {
	// Pi keeps the system prompt as the leading message: it stays as it is.
	const system = all.filter((m: any) => m.role === "system");
	const messages = all.filter((m: any) => m.role !== "system");
	const window = model.contextWindow || 128_000;
	// The kept part in characters, at this conversation's own density (Chinese has far fewer chars per token).
	const total = messages.reduce((n, m) => n + chars(m), 0);
	// After a "context too long" error the conversation is at least a full window, whatever the estimate says.
	const tokens = Math.max(estimateTokens(all), keepTask ? window : 0);
	const perToken = Math.min(CHARS_PER_TOKEN, total / Math.max(1, tokens));
	const cut = splitPoint(messages, window * KEEP_RECENT * perToken, keepTask);
	if (cut <= 0) return null;
	const older = transcriptText(messages.slice(0, cut), Math.floor(window * 0.5 * CHARS_PER_TOKEN));
	const reply = await complete(model, {
		systemPrompt: SUMMARY_PROMPT,
		messages: [{ role: "user", content: [{ type: "text", text: older }], timestamp: Date.now() }],
	} as Context, { signal });
	const summary = (reply?.content ?? []).filter((c: any) => c.type === "text").map((c: any) => c.text).join("\n").trim();
	if (!summary || reply?.stopReason === "error" || reply?.stopReason === "aborted") return null;
	const head: AgentMessage = { role: "user", content: [{ type: "text", text: SUMMARY_PREFIX + summary }], timestamp: Date.now() } as AgentMessage;
	return [...system, head, ...messages.slice(cut)];
}
