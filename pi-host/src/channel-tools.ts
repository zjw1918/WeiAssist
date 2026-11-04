// For a conversation the user has from a messaging app (Telegram): the user is on another device, reading the
// answers there. send_file sends a file from the session's Termux folder to that chat (Kotlin fetches it from
// Termux and uploads it; the bot's token never reaches Termux).
import type { AgentTool } from "@earendil-works/pi-agent-core";
import { obj, opt, str } from "@earendil-works/pi-schema";
import { bridgeCall } from "./bridge.ts";

const text = (t: string) => ({ content: [{ type: "text" as const, text: t }], details: {} });

export const sendFileTool = (session: string): AgentTool<any> => ({
	name: "send_file",
	label: "Send file",
	description:
		"Send a file from Termux to the user's chat: a picture, chart, PDF, audio, video or any file up to 50 MB " +
		"(images up to 10 MB are shown as photos). The path is relative to this session's folder, or absolute.",
	parameters: obj({
		path: str({ description: "The file's path in Termux, e.g. chart.png or ~/sessions/<id>/out/report.pdf" }),
		caption: opt(str({ description: "A short caption shown with it" })),
	}),
	executionMode: "sequential",
	execute: async (_id, p: any, signal) => {
		signal?.throwIfAborted();
		// Kotlin answers { ok, text }: a file that didn't go is a failed step (the model reads why in text).
		const r = await bridgeCall("send_file", { session, path: p.path, caption: p.caption ?? "" });
		return { ...text(r?.text ?? String(r)), isError: r?.ok !== true } as any;
	},
});

/** [withFiles]: the session has Termux, so it can send files and finds the user's files in inbox/. */
export const channelPrompt = (channel: string, withFiles: boolean) => `
The user is talking to you from ${channel === "telegram" ? "Telegram" : channel} on another device: your answers are sent to that chat,
and they can't see this phone's screen. Keep answers easy to read on a phone.` + (withFiles ? `
To give the user a file you made or found in Termux (an image, a chart, a PDF, a video…), use send_file: don't just name its path.
Files the user sends you come as attachments; those you can't read directly are saved in this session's folder under inbox/.` : "");
