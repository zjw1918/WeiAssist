// Bundles Pi (pi-agent-core + pi-ai) and the host adapter into one browser script for the app's WebView.
import { build } from "esbuild";
import { existsSync, mkdirSync, readFileSync, readdirSync, writeFileSync } from "node:fs";

const out = "../pi-runtime/src/main/assets/pi";
const pi = JSON.parse(readFileSync("node_modules/@earendil-works/pi-agent-core/package.json", "utf8")).version;
mkdirSync(out, { recursive: true });
const result = await build({
	entryPoints: ["src/host.ts"],
	bundle: true,
	platform: "browser",
	format: "iife",
	target: "chrome100",
	minify: true,
	sourcemap: false,
	legalComments: "linked",
	metafile: true,
	// Pi's OAuth flows import node:http (callback server) and node:crypto (randomBytes); see src/shims.
	alias: { "node:http": "./src/shims/http.ts", "node:crypto": "./src/shims/crypto.ts" },
	define: { __PI_VERSION__: JSON.stringify(pi) },
	outfile: `${out}/host.js`,
});
writeFileSync(`${out}/index.html`, `<!doctype html><meta charset="utf-8"><title>pi-host</title><script src="host.js"></script>\n`);
const inputs = Object.keys(result.metafile.inputs);
const pkgs = [...new Set(inputs.map((i) => i.match(/node_modules\/((?:@[^/]+\/)?[^/]+)/)?.[1]).filter(Boolean))].sort();
// Every bundled package's license text ships with the app (MIT and Apache-2.0 both require it).
const licenses = pkgs.map((name) => {
	const dir = `node_modules/${name}`;
	const meta = JSON.parse(readFileSync(`${dir}/package.json`, "utf8"));
	const file = readdirSync(dir).find((f) => /^(license|licence)(\.|$)/i.test(f));
	// Pi's npm packages ship without a LICENSE file; use the copy of the upstream repo's LICENSE.
	const vendored = name.startsWith("@earendil-works/") ? "licenses/earendil-works-pi.LICENSE" : null;
	const path = file ? `${dir}/${file}` : vendored && existsSync(vendored) ? vendored : null;
	if (!path) throw new Error(`No license text for bundled package ${name}: add one to pi-host/licenses/`);
	const text = readFileSync(path, "utf8").trim();
	return `==== ${name} ${meta.version} (${meta.license}) ====\n\n${text}\n`;
});
writeFileSync(`${out}/THIRD_PARTY_LICENSES.txt`, licenses.join("\n"));
console.log(`pi ${pi} → ${out}/host.js (${(result.metafile.outputs[`${out}/host.js`].bytes / 1024).toFixed(0)} KiB)`);
console.log("packages:", pkgs.join(", "));
