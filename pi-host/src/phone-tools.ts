// Phone sessions in Pi: the pruning of old screens from the model's context (Pi's own context handling). The
// system prompt is the app's (Kotlin's Prompts). The tools themselves come from the app's catalog (catalog-tools.ts); what they do and the
// safety checks before them are Kotlin's PhoneTools.
import type { AgentMessage } from "@earendil-works/pi-agent-core";

/** Separates an action's result from the screen it left (see pruneScreens). Must equal Kotlin's PhonePolicy.SCREEN_NOW. */
export const SCREEN_NOW = "\n\nScreen now:\n";

/**
 * Screens are big and only the latest ones matter: all but the latest [keep] screens (read_screen and
 * screenshot results, and the screen an action returned) become a short note. An action keeps its result.
 */
export function pruneScreens(messages: AgentMessage[], keep: number): AgentMessage[] {
	const textOf = (m: any) => (Array.isArray(m.content) ? m.content.find((c: any) => c.type === "text")?.text ?? "" : "");
	const screens = messages
		.map((m: any, i) => (m.role === "toolResult" && (m.toolName === "read_screen" || m.toolName === "screenshot" || textOf(m).includes(SCREEN_NOW)) ? i : -1))
		.filter((i) => i >= 0);
	const old = new Set(screens.slice(0, Math.max(0, screens.length - keep)));
	if (old.size === 0) return messages;
	return messages.map((m: any, i) => {
		if (!old.has(i)) return m;
		const t = textOf(m);
		const note = t.includes(SCREEN_NOW) ? `${t.slice(0, t.indexOf(SCREEN_NOW))}\n[screen omitted; it has changed since]` : `[earlier ${m.toolName} omitted; the screen has changed since]`;
		return { ...m, content: [{ type: "text", text: note }] };
	});
}

/** Pi's transformContext hook: the model sees the latest two screens (the transcript itself is untouched). */
export async function pruneOldScreens(messages: AgentMessage[]): Promise<AgentMessage[]> {
	return pruneScreens(messages, 2);
}
