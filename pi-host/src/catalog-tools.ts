// The session's tools, as the app sends them (Kotlin's ToolCatalog, from tools.json): name, label, description
// and JSON Schema, declared to Pi as given. Every call goes to Kotlin's "tool" host call (ToolRouter), which runs
// it and answers { text, isError?, image? }. Stop during a call sends "tool_cancel" (e.g. to end a bash command).
import type { AgentTool } from "@earendil-works/pi-agent-core";
import { bridgeCall } from "./bridge.ts";

export type ToolSpec = { name: string; label: string; description: string; parameters: Record<string, unknown>; parallel?: boolean };

type ToolAnswer = { text?: string; isError?: boolean; image?: { data: string; mimeType: string } };

/** A command that may go on in the background: the run stops waiting for it, it keeps working (see detachJobs). */
type Job = { detach: () => void; detached: boolean; abandoned: boolean };
const DETACHABLE = "bash";
const jobs = new Map<string, Map<string, Job>>();
const jobsOf = (session: string) => jobs.get(session) ?? jobs.set(session, new Map()).get(session)!;

/** What a detached command said when it ended, for the model; [command] is what it was running. */
export type LateResult = { call: string; tool: string; command: string; text: string; isError: boolean };

/** The user wants to be read now: commands still running leave the run and go on in the background. */
export function detachJobs(session: string) {
	jobs.get(session)?.forEach((j) => j.detach());
}

/** Stop or the session's end: end what went on in the background; nobody waits for its result any more. */
export function cancelDetachedJobs(session: string) {
	for (const [call, j] of jobs.get(session) ?? []) {
		if (!j.detached) continue;
		j.abandoned = true;
		void bridgeCall("tool_cancel", { session, call }).catch(() => {});
	}
}

function answer(r: ToolAnswer) {
	const content: any[] = [{ type: "text", text: r?.text ?? String(r) }];
	if (r?.image) content.push({ type: "image", data: r.image.data, mimeType: r.image.mimeType });
	return { content, details: {}, ...(r?.isError ? { isError: true } : {}) } as any;
}

/**
 * Wait for [running], unless the user asks for the model's attention first. Then the model gets, in place of the
 * result, what the app can say about the command so far (its output, how to stop it). The real result comes later,
 * through [onLate]. If the command ended meanwhile (the app has nothing to say), the real result is simply returned.
 */
async function detachable(session: string, call: string, tool: string, args: any, running: Promise<ToolAnswer>, onLate: (r: LateResult) => void) {
	let detach!: () => void;
	const asked = new Promise<void>((r) => { detach = r; });
	const job: Job = { detach, detached: false, abandoned: false };
	const table = jobsOf(session);
	table.set(call, job);
	const first = await Promise.race([running.then(() => "done", () => "done"), asked.then(() => "detach")]);
	const note = first === "detach" ? await bridgeCall("tool_peek", { session, call }).catch(() => null) : null;
	if (!note?.text) {
		table.delete(call);
		return answer(await running);
	}
	job.detached = true;
	const late = (text: string, isError: boolean) => {
		table.delete(call);
		if (!job.abandoned) onLate({ call, tool, command: String(args?.command ?? ""), text, isError });
	};
	running.then((r) => late(r?.text ?? String(r), !!r?.isError), (e) => late(String(e?.message ?? e), true));
	return answer({ text: note.text });
}

export const catalogTools = (session: string, specs: ToolSpec[], onLate: (r: LateResult) => void): AgentTool<any>[] =>
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
				const running: Promise<ToolAnswer> = bridgeCall("tool", { session, call, name: spec.name, args });
				return spec.name === DETACHABLE ? await detachable(session, call, spec.name, args, running, onLate) : answer(await running);
			} finally {
				signal?.removeEventListener("abort", cancel);
			}
		},
	}));
