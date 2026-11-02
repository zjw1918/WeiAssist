// Phone tools for Pi's Agent. Each tool is a plain Pi AgentTool whose execute() asks Kotlin's
// AccessibilityService (AgentAccessibilityService.kt) to look at or act on the screen.
// Risky taps (send, pay, delete…) go through Pi's beforeToolCall hook, which asks the user first.
import type { AgentTool, BeforeToolCallContext, BeforeToolCallResult } from "@earendil-works/pi-agent-core";
import { Type } from "@earendil-works/pi-ai";
import { bridgeCall } from "./bridge.ts";

// Tool arguments are nested under `args`: spreading them would let a tool's own `id` (an element id)
// overwrite the bridge's request id, and the reply would never find its way back.
const phone = (action: string, args: Record<string, unknown> = {}, signal?: AbortSignal) => {
	signal?.throwIfAborted(); // the user pressed Stop: don't start another action
	return bridgeCall("phone", { action, args }) as Promise<string>;
};

const text = (t: string) => ({ content: [{ type: "text" as const, text: t }], details: {} });

/** Labels of the elements in the last screen read, by id, so the confirm hook knows what a tap hits. */
let lastScreen = new Map<number, string>();
/** What the agent typed during this task, shown in the confirmation. */
let typed: string[] = [];

/** A readable name for an input field from its read_screen line: hint, text, description or view id. */
function fieldName(line?: string) {
	if (!line) return "field";
	return line.match(/hint="([^"]*)"/)?.[1] || line.match(/"([^"]*)"/)?.[1] || line.match(/\(([^)]*)\)/)?.[1] || line.match(/id=(\S+)/)?.[1] || "field";
}

export function resetPhoneSession() {
	lastScreen = new Map();
	typed = [];
}

async function readScreen(signal?: AbortSignal) {
	const screen = await phone("read_screen", {}, signal);
	lastScreen = new Map();
	for (const line of screen.split("\n")) {
		const m = line.match(/^\[(\d+)\]\s+(.*)$/);
		if (m) lastScreen.set(Number(m[1]), m[2]);
	}
	return screen;
}

export const phoneTools: AgentTool<any>[] = [
	{
		name: "open_app",
		label: "Open app",
		description: "Open an installed app by its name or package name, e.g. \"Gmail\" or \"com.google.android.gm\".",
		parameters: Type.Object({ app: Type.String({ description: "App name or package name" }) }),
		executionMode: "sequential",
		execute: async (_id, p: any, signal) => text(await phone("open_app", { app: p.app }, signal)),
	},
	{
		name: "read_screen",
		label: "Read screen",
		description:
			"Read what is on the screen now: one line per element, `[id] Class \"text\" (description) flags`. " +
			"Ids are only valid until the next read_screen. Read again after every action.",
		parameters: Type.Object({}),
		executionMode: "sequential",
		execute: async (_id, _p, signal) => text(await readScreen(signal)),
	},
	{
		name: "tap",
		label: "Tap",
		description: "Tap the element with this id from the latest read_screen.",
		parameters: Type.Object({ id: Type.Integer() }),
		executionMode: "sequential",
		execute: async (_id, p: any, signal) => text(await phone("tap", { id: p.id }, signal)),
	},
	{
		name: "type_text",
		label: "Type text",
		description: "Replace the text of an editable element (from the latest read_screen) with the given text.",
		parameters: Type.Object({ id: Type.Integer(), text: Type.String() }),
		executionMode: "sequential",
		execute: async (_id, p: any, signal) => {
			typed.push(`${fieldName(lastScreen.get(p.id))}: ${p.text}`);
			return text(await phone("type_text", { id: p.id, text: p.text }, signal));
		},
	},
	{
		name: "scroll",
		label: "Scroll",
		description: "Scroll a scrollable element (or the first scrollable one if id is omitted) forward (down) or backward (up).",
		parameters: Type.Object({
			id: Type.Optional(Type.Integer()),
			direction: Type.Union([Type.Literal("forward"), Type.Literal("backward")]),
		}),
		executionMode: "sequential",
		execute: async (_id, p: any, signal) => text(await phone("scroll", { id: p.id ?? -1, direction: p.direction }, signal)),
	},
	{
		name: "press_key",
		label: "Press key",
		description: "Press a system key: back, home or recents.",
		parameters: Type.Object({ key: Type.Union([Type.Literal("back"), Type.Literal("home"), Type.Literal("recents")]) }),
		executionMode: "sequential",
		execute: async (_id, p: any, signal) => text(await phone("press_key", { key: p.key }, signal)),
	},
	{
		name: "wait",
		label: "Wait",
		description: "Wait for the screen to settle, in milliseconds (max 5000).",
		parameters: Type.Object({ ms: Type.Integer() }),
		executionMode: "sequential",
		execute: async (_id, p: any, signal) => {
			signal?.throwIfAborted();
			await new Promise((r) => setTimeout(r, Math.min(Math.max(p.ms, 0), 5000)));
			signal?.throwIfAborted();
			return text("waited");
		},
	},
];

// Button labels that mean "irreversible", in English and Chinese UIs.
const RISKY = /^(send|post|pay|buy|place order|delete|remove|transfer|submit)\b|^(发送|发布|支付|付款|购买|下单|删除|移除|转账|提交)/i;

/** Pi's beforeToolCall hook: a tap on something that looks irreversible needs the user's OK. */
export async function confirmRiskyTap({ toolCall, args }: BeforeToolCallContext): Promise<BeforeToolCallResult | undefined> {
	if (toolCall.name !== "tap") return undefined;
	const line = lastScreen.get((args as any).id) ?? "";
	// The button's own words: its text, else its description. Long texts are content (an email
	// subject in a list), not a command, so only short labels count.
	const label = (line.match(/"([^"]*)"/)?.[1] || line.match(/\(([^)]*)\)/)?.[1] || "").trim();
	if (!label || label.length > 20 || !RISKY.test(label)) return undefined;
	// Kotlin words the card in the user's language; we pass the button label and what was typed.
	const ok = await bridgeCall("confirm", { label, typed });
	return ok ? undefined : { block: true, reason: "The user declined this action. Stop and tell the user.", terminate: true };
}

export const phoneSystemPrompt = (name: string) => `You are ${name}, an assistant that operates the user's Android phone for them.
You see the screen only through read_screen and act only through the tools.
Work step by step: read_screen, do one action, then read_screen again to check what happened.
Prefer the most direct route in the app's own UI. If a dialog or tip appears, dismiss it sensibly.
Never invent information the user did not give you. Ask instead.
Do not ask for permission in chat before sending, paying or deleting: just tap the button. The app itself stops
that tap and shows the user a confirmation dialog; if they decline, the tap is blocked and you should stop.
When the task is done, reply briefly in the user's language with what you did.`;
