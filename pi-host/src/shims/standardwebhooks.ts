// Stand-in for `standardwebhooks`, which the Anthropic SDK imports only to verify incoming webhooks.
// The app never receives webhooks, so the package (and its dependencies) stay out of the bundle.
export class Webhook {
	constructor() {
		throw new Error("webhooks are not supported in the app");
	}
}
