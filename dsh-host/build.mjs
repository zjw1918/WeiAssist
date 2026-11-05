// Bundles the bridge plugin to lib/index.js. dsh's own packages stay imports: the plugin uses the ones its dsh
// installation resolves (the runtime resolution of the profile it is installed in).
import { build } from "esbuild";

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
