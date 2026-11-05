// The session's tools, as the app sends them (Kotlin's ToolCatalog, from tools.json): name, label, description
// and JSON Schema, declared to Pi as given. Every call goes to Kotlin's "tool" host call (ToolRouter), which runs
// it and answers { text, isError?, image? }. Stop during a call sends "tool_cancel" (e.g. to end a bash command).
import type { AgentTool } from "@earendil-works/pi-agent-core";
import { bridgeCall } from "./bridge.ts";

export type ToolSpec = { name: string; label: string; description: string; parameters: Record<string, unknown>; parallel?: boolean };

type ToolAnswer = { text?: string; isError?: boolean; image?: { data: string; mimeType: string } };

export const catalogTools = (session: string, specs: ToolSpec[]): AgentTool<any>[] =>
	specs.map((spec) => ({
		name: spec.name,
		label: spec.label,
		description: spec.description,
		parameters: spec.parameters as any,
		...(spec.parallel ? {} : { executionMode: "sequential" as const }),
		execute: async (toolCallId: string, args: any, signal?: AbortSignal) => {
			signal?.throwIfAborted(); // the user pressed Stop: don't start another call
			// The model's id for this call: before_tool saw the same one, and the app runs only a call it allowed.
			const call = toolCallId;
			const cancel = () => void bridgeCall("tool_cancel", { session, call }).catch(() => {});
			signal?.addEventListener("abort", cancel, { once: true });
			try {
				// Arguments are nested under `args`: spreading them would let a tool's own `id` (an element id)
				// overwrite the bridge's request id, and the reply would never find its way back.
				const r: ToolAnswer = await bridgeCall("tool", { session, call, name: spec.name, args });
				const content: any[] = [{ type: "text", text: r?.text ?? String(r) }];
				if (r?.image) content.push({ type: "image", data: r.image.data, mimeType: r.image.mimeType });
				return { content, details: {}, ...(r?.isError ? { isError: true } : {}) } as any;
			} finally {
				signal?.removeEventListener("abort", cancel);
			}
		},
	}));
