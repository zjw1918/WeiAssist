#!/data/data/com.termux/files/usr/bin/bash
# Sets up DeepSeek Harness (dsh) for WeiAssist inside an Ubuntu userland in Termux (proot-distro), where Node is
# plain linux-arm64 and dsh's published native modules load as they are (Termux's own Node is "android", which they
# don't support). A prototype.
#   bash setup-proot.sh [materials folder]
# The materials folder (default: ~/storage/downloads/weiassist-dsh) holds the bridge plugin
# (weiassist-dsh-bridge-*.tgz) and the profile patch (cordis.patch.yml). Optional env:
#   NODE_VERSION (default v24.13.0), NODE_MIRROR (default https://nodejs.org/dist; e.g. https://npmmirror.com/mirrors/node),
#   NPM_REGISTRY (e.g. https://registry.npmmirror.com).
# Ubuntu sees Termux's home as /root (--termux-home), so everything lives in ~/.weiassist-dsh/linux:
#   run/ (the dsh installation), home/ (DSH_HOME: its profile and sessions). Log: ~/.weiassist-dsh/setup-proot.log
set -euo pipefail

DSH_VERSION="0.2.2-rc.1"
NODE_VERSION="${NODE_VERSION:-v24.13.0}"
NODE_MIRROR="${NODE_MIRROR:-https://nodejs.org/dist}"
NPM_REGISTRY="${NPM_REGISTRY:-}"
src="${1:-$HOME/storage/downloads/weiassist-dsh}"
d="$HOME/.weiassist-dsh"
mkdir -p "$d/linux/run" "$d/linux/home" "$d/materials"
exec > >(tee "$d/setup-proot.log") 2>&1

step() { printf '\n== %s\n' "$*"; }

step "materials from $src"
plugin="$(ls "$src"/weiassist-dsh-bridge-*.tgz 2> /dev/null | tail -1 || true)"
[ -n "$plugin" ] && [ -f "$src/cordis.patch.yml" ] || { echo "Missing the plugin .tgz or cordis.patch.yml in $src (run termux-setup-storage first if it is in Downloads)."; exit 2; }
cp "$plugin" "$d/materials/weiassist-dsh-bridge.tgz"
cp "$src/cordis.patch.yml" "$d/materials/cordis.patch.yml"

step "proot-distro and Ubuntu"
command -v proot-distro > /dev/null || pkg install -y proot-distro
command -v tmux > /dev/null || pkg install -y tmux
# Ask proot-distro itself whether Ubuntu is there: newer versions keep containers elsewhere than installed-rootfs/.
if ! proot-distro login ubuntu -- true > /dev/null 2>&1; then
	proot-distro install ubuntu
fi

in_ubuntu() { proot-distro login ubuntu --termux-home -- env NODE_VERSION="$NODE_VERSION" NODE_MIRROR="$NODE_MIRROR" NPM_REGISTRY="$NPM_REGISTRY" DSH_VERSION="$DSH_VERSION" bash -euo pipefail -c "$1"; }

step "Node $NODE_VERSION in Ubuntu"
in_ubuntu '
	if ! command -v node > /dev/null || [ "$(node -v)" != "$NODE_VERSION" ]; then
		command -v curl > /dev/null && command -v xz > /dev/null || { apt-get update -y && apt-get install -y curl ca-certificates xz-utils; }
		curl -fsSL "$NODE_MIRROR/$NODE_VERSION/node-$NODE_VERSION-linux-arm64.tar.xz" | tar -xJ -C /usr/local --strip-components=1
	fi
	node -v
	node -p "\"platform: \" + process.platform + \"-\" + process.arch"
'

step "dsh $DSH_VERSION"
in_ubuntu '
	cd /root/.weiassist-dsh/linux/run
	[ -f package.json ] || echo "{\"name\":\"weiassist-dsh\",\"private\":true,\"type\":\"module\"}" > package.json
	[ -z "$NPM_REGISTRY" ] || npm config set registry "$NPM_REGISTRY"
	npm install "@deepseek-ai/dsh@$DSH_VERSION" --no-audit --no-fund
	du -sh node_modules
	export DSH_HOME=/root/.weiassist-dsh/linux/home
	node ./node_modules/.bin/dsh --version
'

step "the weiassist profile and the bridge"
in_ubuntu '
	cd /root/.weiassist-dsh/linux/run
	export DSH_HOME=/root/.weiassist-dsh/linux/home
	node ./node_modules/.bin/dsh --profile weiassist --from-default-profile headless --dump-config > /dev/null
	p="$DSH_HOME/profiles/weiassist"
	rm -rf "$p/node_modules/@weiassist/dsh-bridge"
	mkdir -p "$p/node_modules/@weiassist/dsh-bridge"
	tar -xzf /root/.weiassist-dsh/materials/weiassist-dsh-bridge.tgz -C "$p/node_modules/@weiassist/dsh-bridge" --strip-components=1
	node -e "const f=process.argv[1]; const fs=require(\"fs\"); const j=JSON.parse(fs.readFileSync(f)); j.dependencies={...j.dependencies,\"@weiassist/dsh-bridge\":\"0.1.0\"}; fs.writeFileSync(f, JSON.stringify(j,null,2))" "$p/package.json"
	cp /root/.weiassist-dsh/materials/cordis.patch.yml "$p/cordis.patch.yml"
	node ./node_modules/.bin/dsh --profile weiassist --dump-config | grep -E "id: weiassist-bridge|mock:"
'

step "check: dsh boots (the bridge has no app to connect to here, so it only says so)"
in_ubuntu '
	cd /root/.weiassist-dsh/linux/run
	export DSH_HOME=/root/.weiassist-dsh/linux/home
	timeout 120 node ./node_modules/.bin/dsh --profile weiassist > /root/.weiassist-dsh/boot-check.log 2>&1 &
	pid=$!
	for i in $(seq 1 120); do
		grep -q "weiassist-bridge" /root/.weiassist-dsh/boot-check.log 2> /dev/null && break
		kill -0 $pid 2> /dev/null || break
		sleep 1
	done
	kill $pid 2> /dev/null || true
	tail -5 /root/.weiassist-dsh/boot-check.log
'

step "done"
echo "WeiAssist starts dsh itself in Ubuntu (tmux session 'dsh', log ~/.weiassist-dsh/dsh.log)."
