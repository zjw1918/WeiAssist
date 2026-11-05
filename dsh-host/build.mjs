// Bundles the bridge plugin to lib/index.js. dsh's own packages stay imports: the plugin uses the ones its dsh
// installation resolves (the runtime resolution of the profile it is installed in).
//
// `node build.mjs --out <dir>` (Gradle passes core-dsh's generated-assets dir) also writes <dir>/dsh/: what the app's
// dsh setup puts into Termux (the bridge, its package.json, the profile patch and the setup script).
import { build } from "esbuild";
import { copyFileSync, mkdirSync, readFileSync, writeFileSync } from "node:fs";

await build({
	entryPoints: ["src/index.ts"],
	outfile: "lib/index.js",
	bundle: true,
	platform: "node",
	format: "esm",
	target: "node24",
	external: ["@deepseek-ai/*"],
	logLevel: "warning",
});
console.log("dsh-bridge → lib/index.js");

const outArg = process.argv.indexOf("--out");
if (outArg > 0) {
	const out = `${process.argv[outArg + 1]}/dsh`;
	mkdirSync(out, { recursive: true });
	const pkg = JSON.parse(readFileSync("package.json", "utf8"));
	// Only what dsh's plugin loader reads: the installed plugin is lib/index.js and this.
	const { name, version, type, main, exports } = pkg;
	writeFileSync(`${out}/package.json`, JSON.stringify({ name, version, type, main, exports }, null, 2) + "\n");
	copyFileSync("lib/index.js", `${out}/index.js`);
	copyFileSync("profile/cordis.patch.yml", `${out}/cordis.patch.yml`);
	copyFileSync("termux/setup-proot.sh", `${out}/setup-proot.sh`);
	console.log(`dsh setup materials → ${out}`);
}
