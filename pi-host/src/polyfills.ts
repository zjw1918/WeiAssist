// Web APIs that Pi's code calls and old WebViews lack (an Android 12 tablet can still have Chrome 99). The build's
// target only rewrites syntax. Without throwIfAborted, for one, Pi's credential store can't save any key.
const Signal = AbortSignal as any;

AbortSignal.prototype.throwIfAborted ??= function (this: AbortSignal) {
	if (this.aborted) throw this.reason;
};

Signal.timeout ??= (ms: number) => {
	const c = new AbortController();
	setTimeout(() => c.abort(new DOMException("The operation timed out.", "TimeoutError")), ms);
	return c.signal;
};

Signal.any ??= (signals: Iterable<AbortSignal>) => {
	const c = new AbortController();
	for (const s of signals) {
		if (s.aborted) {
			c.abort(s.reason);
			break;
		}
		s.addEventListener("abort", () => c.abort(s.reason), { once: true, signal: c.signal });
	}
	return c.signal;
};

(URL as any).canParse ??= (url: string, base?: string) => {
	try {
		new URL(url, base);
		return true;
	} catch {
		return false;
	}
};
