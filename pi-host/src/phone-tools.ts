// Phone tools for Pi's Agent. Each tool is a plain Pi AgentTool whose execute() asks Kotlin's
// AccessibilityService (AgentAccessibilityService.kt) to look at or act on the screen.
// Risky taps (send, pay, delete…) go through Pi's beforeToolCall hook, which asks the user first.
import type { AgentTool, BeforeToolCallContext, BeforeToolCallResult } from "@earendil-works/pi-agent-core";
import { Type } from "@earendil-works/pi-ai";
import { bridgeCall } from "./bridge.ts";

// Tool arguments are nested under `args`: spreading them would let a tool's own `id` (an element id)
// overwrite the bridge's request id, and the reply would never find its way back.
const phone = (action: string, args: Record<string, unknown> = {}) =>
	bridgeCall("phone", { action, args }) as Promise<string>;

const text = (t: string) => ({ content: [{ type: "text" as const, text: t }], details: {} });

/** Labels of the elements in the last screen read, by id, so the confirm hook knows what a tap hits. */
let lastScreen = new Map<number, string>();
/** What the agent typed during this task, shown in the confirmation. */
let typed: string[] = [];

export function resetPhoneSession() {
	lastScreen = new Map();
	typed = [];
}

async function readScreen() {
	const screen = await phone("read_screen");
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
		label: "打开应用",
		description: "Open an installed app by its name or package name, e.g. \"Gmail\" or \"com.google.android.gm\".",
		parameters: Type.Object({ app: Type.String({ description: "App name or package name" }) }),
		executionMode: "sequential",
		execute: async (_id, p: any) => text(await phone("open_app", { app: p.app })),
	},
	{
		name: "read_screen",
		label: "看屏幕",
		description:
			"Read what is on the screen now: one line per element, `[id] Class \"text\" (description) flags`. " +
			"Ids are only valid until the next read_screen. Read again after every action.",
		parameters: Type.Object({}),
		executionMode: "sequential",
		execute: async () => text(await readScreen()),
	},
	{
		name: "tap",
		label: "点一下",
		description: "Tap the element with this id from the latest read_screen.",
		parameters: Type.Object({ id: Type.Integer() }),
		executionMode: "sequential",
		execute: async (_id, p: any) => text(await phone("tap", { id: p.id })),
	},
	{
		name: "type_text",
		label: "输入文字",
		description: "Replace the text of an editable element (from the latest read_screen) with the given text.",
		parameters: Type.Object({ id: Type.Integer(), text: Type.String() }),
		executionMode: "sequential",
		execute: async (_id, p: any) => {
			typed.push(`${lastScreen.get(p.id) ?? `#${p.id}`} ← ${p.text}`);
			return text(await phone("type_text", { id: p.id, text: p.text }));
		},
	},
	{
		name: "scroll",
		label: "滑动",
		description: "Scroll a scrollable element (or the first scrollable one if id is omitted) forward (down) or backward (up).",
		parameters: Type.Object({
			id: Type.Optional(Type.Integer()),
			direction: Type.Union([Type.Literal("forward"), Type.Literal("backward")]),
		}),
		executionMode: "sequential",
		execute: async (_id, p: any) => text(await phone("scroll", { id: p.id ?? -1, direction: p.direction })),
	},
	{
		name: "press_key",
		label: "按键",
		description: "Press a system key: back, home or recents.",
		parameters: Type.Object({ key: Type.Union([Type.Literal("back"), Type.Literal("home"), Type.Literal("recents")]) }),
		executionMode: "sequential",
		execute: async (_id, p: any) => text(await phone("press_key", { key: p.key })),
	},
	{
		name: "wait",
		label: "等一下",
		description: "Wait for the screen to settle, in milliseconds (max 5000).",
		parameters: Type.Object({ ms: Type.Integer() }),
		executionMode: "sequential",
		execute: async (_id, p: any) => {
			await new Promise((r) => setTimeout(r, Math.min(Math.max(p.ms, 0), 5000)));
			return text("waited");
		},
	},
];

const RISKY = /^(send|post|pay|buy|place order|delete|remove|transfer|submit)\b|^(发送|发布|支付|付款|购买|下单|删除|移除|转账|提交)/i;

/** Pi's beforeToolCall hook: a tap on something that looks irreversible needs the user's OK. */
export async function confirmRiskyTap({ toolCall, args }: BeforeToolCallContext): Promise<BeforeToolCallResult | undefined> {
	if (toolCall.name !== "tap") return undefined;
	const line = lastScreen.get((args as any).id) ?? "";
	// The button's own words: its text, else its description. Long texts are content (an email
	// subject in a list), not a command, so only short labels count.
	const label = (line.match(/"([^"]*)"/)?.[1] || line.match(/\(([^)]*)\)/)?.[1] || "").trim();
	if (!label || label.length > 20 || !RISKY.test(label)) return undefined;
	const ok = await bridgeCall("confirm", {
		title: `小维助手要点「${label}」`,
		detail: typed.length ? typed.join("\n") : "（这次没有输入内容）",
	});
	return ok ? undefined : { block: true, reason: "The user declined this action. Stop and tell the user.", terminate: true };
}

export const PHONE_SYSTEM_PROMPT = `You are 小维助手, an assistant that operates the user's Android phone for them.
You see the screen only through read_screen and act only through the tools.
Work step by step: read_screen, do one action, then read_screen again to check what happened.
Prefer the most direct route in the app's own UI. If a dialog or tip appears, dismiss it sensibly.
Never invent information the user did not give you. Ask instead.
Do not ask for permission in chat before sending, paying or deleting: just tap the button. The app itself stops
that tap and shows the user a confirmation dialog; if they decline, the tap is blocked and you should stop.
When the task is done, reply briefly in the user's language with what you did.`;
