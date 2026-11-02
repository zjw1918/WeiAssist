// The one channel to Kotlin. bridge() posts a message; bridgeCall() posts a request and waits for
// Kotlin's PiHost.reply(id, ok, json).
declare const PiBridge: { postMessage(message: string): void };

export const bridge = (msg: Record<string, unknown>) => PiBridge.postMessage(JSON.stringify(msg));

let next = 0;
const pending = new Map<string, { resolve: (v: any) => void; reject: (e: any) => void }>();

export function bridgeCall(type: string, args: Record<string, unknown>): Promise<any> {
	const id = `k${++next}`;
	return new Promise((resolve, reject) => {
		pending.set(id, { resolve, reject });
		bridge({ ...args, type, id }); // our type/id always win over anything in args
	});
}

export function settle(id: string, ok: boolean, value: any) {
	const p = pending.get(id);
	if (!p) return;
	pending.delete(id);
	// Kotlin rejects with { message, code? }: make it a real Error, keeping fields like `code`.
	ok ? p.resolve(value) : p.reject(Object.assign(new Error(value?.message ?? String(value)), value));
}
