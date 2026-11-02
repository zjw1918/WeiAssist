// Browser stand-in for the one `node:crypto` call Pi's OAuth flows make: randomBytes(n).toString("base64url" | "hex").
export function randomBytes(size: number) {
	const bytes = crypto.getRandomValues(new Uint8Array(size));
	return {
		toString(encoding: string) {
			if (encoding === "hex") return [...bytes].map((b) => b.toString(16).padStart(2, "0")).join("");
			const b64 = btoa(String.fromCharCode(...bytes));
			if (encoding === "base64") return b64;
			return b64.replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");
		},
	};
}
export function randomUUID() {
	return crypto.randomUUID();
}
export default { randomBytes, randomUUID };
