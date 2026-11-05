#!/data/data/com.termux/files/usr/bin/bash
# Sets up DeepSeek Harness (dsh) for WeiAssist inside an Ubuntu userland in Termux (proot-distro), where Node is
# plain linux-arm64 and dsh's published native modules load as they are (Termux's own Node is "android", which they
# don't support).
#
# WeiAssist runs this itself (Settings → AI engine → Set up DeepSeek Harness): it writes the materials into
# ~/.weiassist-dsh/materials and runs `bash setup-proot.sh <materials> <stamp>` in Termux, then follows the log.
# By hand: `node build.mjs --out <dir>` in dsh-host/ writes the materials to <dir>/dsh; copy that folder into Termux.
#   bash setup-proot.sh <materials folder> [stamp]
# The materials folder holds the bridge (index.js, package.json), the profile patch (cordis.patch.yml) and this script.
# Optional env: NODE_VERSION (default v24.13.0), NODE_MIRROR (default https://nodejs.org/dist; e.g.
# https://npmmirror.com/mirrors/node), NPM_REGISTRY (e.g. https://registry.npmmirror.com).
#
# Ubuntu sees Termux's home as /root (--termux-home), so everything lives in ~/.weiassist-dsh/linux:
#   run/ (the dsh installation), home/ (DSH_HOME: its profile and sessions).
# What the app reads (one run at a time):
#   setup.log   this run's output; each step starts with a line "== [id] text", and the run ends with "== [done]"
#               or "== [failed] <exit code>"
#   setup.pid   this run's pid while it runs
#   installed   the stamp (the app's hash of the materials) once everything worked
set -euo pipefail

DSH_VERSION="0.2.2-rc.1"
NODE_VERSION="${NODE_VERSION:-v24.13.0}"
NODE_MIRROR="${NODE_MIRROR:-https://nodejs.org/dist}"
NPM_REGISTRY="${NPM_REGISTRY:-}"
d="$HOME/.weiassist-dsh"
src="${1:-$d/materials}"
stamp="${2:-manual}"
mkdir -p "$d/linux/run" "$d/linux/home" "$d/bridge"
exec > >(tee "$d/setup.log") 2>&1
echo $$ > "$d/setup.pid"
finish() {
	c=$?
	rm -f "$d/setup.pid"
	if [ "$c" = 0 ]; then printf '\n== [done]\n'; else printf '\n== [failed] %s\n' "$c"; fi
}
trap finish EXIT

step() { id="$1"; shift; printf '\n== [%s] %s\n' "$id" "$*"; }

# Termux's packages without questions (a changed config file keeps the user's version).
export DEBIAN_FRONTEND=noninteractive
apt_opts=(-o Dpkg::Options::=--force-confdef -o Dpkg::Options::=--force-confold)

step materials "the bridge and the profile from $src"
[ -f "$src/index.js" ] && [ -f "$src/package.json" ] && [ -f "$src/cordis.patch.yml" ] || { echo "Missing index.js, package.json or cordis.patch.yml in $src."; exit 2; }
rm -rf "$d/bridge/lib" && mkdir -p "$d/bridge/lib"
cp "$src/index.js" "$d/bridge/lib/index.js"
cp "$src/package.json" "$d/bridge/package.json"
cp "$src/cordis.patch.yml" "$d/bridge/cordis.patch.yml"

step termux "Termux packages (proot-distro, tmux)"
if ! command -v proot-distro > /dev/null || ! command -v tmux > /dev/null; then
	# A fresh Termux must upgrade first, or newly installed tools can fail on mismatched libraries (OpenSSL).
	# pkg picks a random mirror, and some lag behind by years (packages older than Termux's own then can't install:
	# "unmet dependencies"). If that happens, pin a mirror known to be current and use apt-get, which keeps it.
	if ! { pkg update -y "${apt_opts[@]}" && pkg upgrade -y "${apt_opts[@]}" && pkg install -y "${apt_opts[@]}" proot-distro tmux; }; then
		mirrors=(https://packages-cf.termux.dev/apt/termux-main https://mirrors.tuna.tsinghua.edu.cn/termux/apt/termux-main
			https://mirrors.ustc.edu.cn/termux/apt/termux-main https://mirrors.aliyun.com/termux/termux-main)
		# Mainland China (the app asked for its mirrors): the official repository is slow there, so try it last.
		case "$NODE_MIRROR" in *npmmirror*) mirrors=("${mirrors[@]:1}" "${mirrors[0]}") ;; esac
		ok=
		for m in "${mirrors[@]}"; do
			echo "Trying the Termux mirror $m"
			echo "deb $m stable main" > "$PREFIX/etc/apt/sources.list"
			if apt-get update "${apt_opts[@]}" && apt-get -y "${apt_opts[@]}" full-upgrade && apt-get -y "${apt_opts[@]}" install proot-distro tmux; then
				ok=1; break
			fi
		done
		[ -n "$ok" ] || { echo "No Termux mirror could install proot-distro and tmux."; exit 3; }
	fi
fi

step ubuntu "Ubuntu (proot-distro)"
# Ask proot-distro itself whether Ubuntu is there: newer versions keep containers elsewhere than installed-rootfs/.
# proot-distro 5 pulls ubuntu:latest from Docker Hub, which mainland China blocks. Ubuntu's own minimal rootfs
# (ubuntu-base) is on Chinese university mirrors: proot-distro 5 installs a plain rootfs tarball as well. With the
# app's China mirrors that comes first, otherwise it is the fallback. proot-distro 4 only installs its own tarballs.
cn=; case "$NODE_MIRROR" in *npmmirror*) cn=1 ;; esac
# Its version from Termux's package database (proot-distro 5 has no --version).
pd_major="$(dpkg-query -W -f='${Version}' proot-distro 2> /dev/null | cut -d. -f1)"
echo "proot-distro ${pd_major:-?}.x"
ubuntu_from_mirror() {
	[ "${pd_major:-0}" -ge 5 ] 2> /dev/null || { echo "proot-distro ${pd_major:-?} can't install Ubuntu from a mirror."; return 1; }
	base=https://mirrors.tuna.tsinghua.edu.cn/ubuntu-cdimage/ubuntu-base/releases/26.04/release
	sums="$(curl -fsSL --retry 2 "$base/SHA256SUMS")" || return 1
	f="$(printf '%s\n' "$sums" | grep -o 'ubuntu-base-[0-9.]*-base-arm64\.tar\.gz' | sort -V | tail -1)"
	[ -n "$f" ] || return 1
	echo "Downloading $f from mirrors.tuna.tsinghua.edu.cn"
	curl -fL --retry 2 -o "$d/$f" "$base/$f" || return 1
	(cd "$d" && printf '%s\n' "$sums" | grep " \*$f\$" | sha256sum -c -) || { rm -f "$d/$f"; return 1; }
	# A container that doesn't log in (half made by a failed install) would block this name.
	proot-distro remove ubuntu > /dev/null 2>&1 || true
	proot-distro install --name ubuntu "$d/$f"; c=$?
	rm -f "$d/$f"
	return $c
}
if ! proot-distro login ubuntu -- true > /dev/null 2>&1; then
	if [ -n "$cn" ]; then
		ubuntu_from_mirror || { echo "The mirror didn't work; trying proot-distro's own source."; proot-distro install ubuntu; }
	else
		proot-distro install ubuntu || { echo "proot-distro's own source didn't work; trying Ubuntu from mirrors.tuna.tsinghua.edu.cn."; ubuntu_from_mirror; }
	fi
fi
proot-distro login ubuntu -- sh -c '. /etc/os-release && echo "$PRETTY_NAME"'

in_ubuntu() { proot-distro login ubuntu --termux-home -- env NODE_VERSION="$NODE_VERSION" NODE_MIRROR="$NODE_MIRROR" NPM_REGISTRY="$NPM_REGISTRY" DSH_VERSION="$DSH_VERSION" DEBIAN_FRONTEND=noninteractive bash -euo pipefail -c "$1"; }

step node "Node $NODE_VERSION in Ubuntu"
in_ubuntu '
	if ! command -v node > /dev/null || [ "$(node -v)" != "$NODE_VERSION" ]; then
		# With the China mirrors, Ubuntu'"'"'s packages too (ports.ubuntu.com is slow there). http: a minimal Ubuntu has no
		# CA certificates yet; apt checks the packages'"'"' signatures itself.
		case "$NODE_MIRROR" in *npmmirror*)
			sed -i "s#http://ports.ubuntu.com/ubuntu-ports#http://mirrors.tuna.tsinghua.edu.cn/ubuntu-ports#g" /etc/apt/sources.list.d/ubuntu.sources /etc/apt/sources.list 2> /dev/null || true ;;
		esac
		command -v curl > /dev/null && command -v xz > /dev/null || { apt-get update -y && apt-get install -y curl ca-certificates xz-utils; }
		curl -fsSL "$NODE_MIRROR/$NODE_VERSION/node-$NODE_VERSION-linux-arm64.tar.xz" | tar -xJ -C /usr/local --strip-components=1
	fi
	node -v
	node -p "\"platform: \" + process.platform + \"-\" + process.arch"
'

step dsh "dsh $DSH_VERSION"
in_ubuntu '
	cd /root/.weiassist-dsh/linux/run
	[ -f package.json ] || echo "{\"name\":\"weiassist-dsh\",\"private\":true,\"type\":\"module\"}" > package.json
	[ -z "$NPM_REGISTRY" ] || npm config set registry "$NPM_REGISTRY"
	if [ "$(node -p "require(\"./node_modules/@deepseek-ai/dsh/package.json\").version" 2> /dev/null)" != "$DSH_VERSION" ]; then
		npm install "@deepseek-ai/dsh@$DSH_VERSION" --no-audit --no-fund
	fi
	du -sh node_modules
	export DSH_HOME=/root/.weiassist-dsh/linux/home
	node ./node_modules/.bin/dsh --version
'

step profile "the weiassist profile and the bridge"
in_ubuntu '
	cd /root/.weiassist-dsh/linux/run
	export DSH_HOME=/root/.weiassist-dsh/linux/home
	p="$DSH_HOME/profiles/weiassist"
	[ -f "$p/package.json" ] || node ./node_modules/.bin/dsh --profile weiassist --from-default-profile headless --dump-config > /dev/null
	rm -rf "$p/node_modules/@weiassist/dsh-bridge"
	mkdir -p "$p/node_modules/@weiassist/dsh-bridge"
	cp -r /root/.weiassist-dsh/bridge/lib /root/.weiassist-dsh/bridge/package.json "$p/node_modules/@weiassist/dsh-bridge/"
	v="$(node -p "require(\"/root/.weiassist-dsh/bridge/package.json\").version")"
	node -e "const f=process.argv[1]; const fs=require(\"fs\"); const j=JSON.parse(fs.readFileSync(f)); j.dependencies={...j.dependencies,\"@weiassist/dsh-bridge\":process.argv[2]}; fs.writeFileSync(f, JSON.stringify(j,null,2))" "$p/package.json" "$v"
	cp /root/.weiassist-dsh/bridge/cordis.patch.yml "$p/cordis.patch.yml"
	node ./node_modules/.bin/dsh --profile weiassist --dump-config | grep -E "id: weiassist-bridge|mock:"
'

step check "dsh starts (the bridge has no app to connect to here, so it only says so)"
in_ubuntu '
	cd /root/.weiassist-dsh/linux/run
	export DSH_HOME=/root/.weiassist-dsh/linux/home
	timeout 180 node ./node_modules/.bin/dsh --profile weiassist > /root/.weiassist-dsh/boot-check.log 2>&1 &
	pid=$!
	ok=
	for i in $(seq 1 180); do
		grep -q "weiassist-bridge" /root/.weiassist-dsh/boot-check.log 2> /dev/null && { ok=1; break; }
		kill -0 $pid 2> /dev/null || break
		sleep 1
	done
	kill $pid 2> /dev/null || true
	tail -5 /root/.weiassist-dsh/boot-check.log
	[ -n "$ok" ] || { echo "dsh did not load the bridge."; exit 5; }
'

echo "$stamp" > "$d/installed"
echo "WeiAssist starts dsh itself in Ubuntu (tmux session 'dsh', log ~/.weiassist-dsh/dsh.log)."
