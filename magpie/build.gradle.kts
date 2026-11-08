import javax.inject.Inject

plugins {
    alias(libs.plugins.android.library)
}

// The AI gateway: magpie (upstream/, a git submodule pinned to one commit) built for Android as a program and
// shipped as lib/<abi>/libmagpie.so, so it is extracted to nativeLibraryDir, where an app may run programs
// (see MagpieHost). It serves every device on the network an OpenAI/Anthropic/Gemini-compatible API. Only the
// WeiAssist Gateway app (:gateway) uses this module; WeiAssist itself doesn't carry it.
android {
    namespace = "com.lanqi.weiassist.magpie"
    compileSdk {
        version = release(37)
    }
    defaultConfig {
        minSdk = 26
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

/**
 * `go build`s magpie for each ABI with the NDK's clang. cgo is on so Go resolves names with Android's own resolver
 * (bionic getaddrinfo: Private DNS, VPNs). Without it Go looks for /etc/resolv.conf, which Android doesn't have,
 * and every lookup fails. Needs Go on PATH (go.mod in upstream/ says which). Up to date while upstream's tree is unchanged.
 *
 * Size (measured 2026-11-07, arm64): packed relocations (Android's APS2, API 23+) take 3.5 MB off the installed
 * program, and no inlining (-l) another 6 MB, 3 MB of it in the APK, at no CPU cost we could measure (40 translated
 * streams through it on the Realme: 122–146 ms of CPU each, either way). Most of what is left is the gateway itself.
 */
abstract class BuildMagpie @Inject constructor(private val exec: ExecOperations) : DefaultTask() {
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val sources: ConfigurableFileCollection

    @get:Internal
    abstract val sourceDir: DirectoryProperty

    @get:Internal
    abstract val ndkDir: DirectoryProperty

    @get:Input
    abstract val abis: ListProperty<String>

    @get:Input
    abstract val version: Property<String>

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun build() {
        val src = sourceDir.get().asFile
        if (!src.resolve("go.mod").isFile) {
            throw GradleException("magpie's source is missing: run `git submodule update --init magpie/upstream`")
        }
        val os = System.getProperty("os.name").lowercase()
        val host = when {
            os.startsWith("mac") -> "darwin-x86_64" // also on Apple silicon: the NDK names its one macOS build so
            os.startsWith("windows") -> "windows-x86_64"
            else -> "linux-x86_64"
        }
        val bin = ndkDir.get().asFile.resolve("toolchains/llvm/prebuilt/$host/bin")
        val out = outputDir.get().asFile.also { it.deleteRecursively(); it.mkdirs() }
        for (abi in abis.get()) {
            val (goarch, clang) = when (abi) {
                "arm64-v8a" -> "arm64" to "aarch64-linux-android"
                "x86_64" -> "amd64" to "x86_64-linux-android"
                "armeabi-v7a" -> "arm" to "armv7a-linux-androideabi"
                "x86" -> "386" to "i686-linux-android"
                else -> throw GradleException("magpie: unknown ABI $abi")
            }
            val cc = bin.resolve("${clang}26-clang" + if (host.startsWith("windows")) ".cmd" else "")
            val target = out.resolve("$abi/libmagpie.so").also { it.parentFile.mkdirs() }
            exec.exec {
                workingDir = src
                environment("CGO_ENABLED", "1")
                environment("GOOS", "android")
                environment("GOARCH", goarch)
                if (goarch == "arm") environment("GOARM", "7")
                environment("CC", cc.absolutePath)
                commandLine(
                    "go", "build", "-tags", "nogui", "-trimpath", "-buildvcs=false", "-gcflags=all=-l",
                    "-ldflags", "-s -w -X main.version=${version.get()} -extldflags=-Wl,--pack-dyn-relocs=android",
                    "-o", target.absolutePath, ".",
                )
            }
        }
    }
}

val upstream = layout.projectDirectory.dir("upstream")

val buildMagpie = tasks.register<BuildMagpie>("buildMagpie") {
    description = "Builds magpie (the AI gateway) for Android into generated jniLibs."
    sourceDir.set(upstream)
    sources.from(fileTree(upstream) {
        include("**/*.go", "go.mod", "go.sum", "**/*.html", "**/*.js", "**/*.css", "**/*.json", "**/*.svg", "**/*.png", "**/*.woff2", "**/*.txt")
        exclude(".git", "site/**", "docs/**", "**/*_test.go", "**/testdata/**")
    })
    ndkDir.set(androidComponents.sdkComponents.ndkDirectory)
    // -PmagpieAbis=arm64-v8a,x86_64 adds the emulator's. One ABI is ~58 MB, so only phones' by default.
    // -PmagpieAbis=none: build without the program (quicker local builds); the gateway app then says it isn't in it.
    abis.set((providers.gradleProperty("magpieAbis").orNull ?: "arm64-v8a").split(',').map { it.trim() }
        .filter { it.isNotEmpty() && it != "none" })
    version.set(providers.exec { workingDir = upstream.asFile; commandLine("git", "rev-parse", "--short", "HEAD"); isIgnoreExitValue = true }
        .standardOutput.asText.map { it.trim().ifEmpty { "dev" } })
    outputDir.set(layout.buildDirectory.dir("generated/magpie/jniLibs"))
}

// magpie's MIT license, and the notices of what it compiles in, ship with the app as assets/magpie/ (MIT asks
// that its notice go with every copy).
abstract class MagpieNotices : DefaultTask() {
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val files: ConfigurableFileCollection

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun copy() {
        val out = outputDir.get().asFile.resolve("magpie").also { it.deleteRecursively(); it.mkdirs() }
        files.forEach { it.copyTo(out.resolve(it.name)) }
    }
}

val magpieNotices = tasks.register<MagpieNotices>("magpieNotices") {
    files.from(upstream.file("LICENSE"), upstream.file("THIRD_PARTY_NOTICES"))
    outputDir.set(layout.buildDirectory.dir("generated/magpie/assets"))
}

androidComponents {
    onVariants { variant ->
        variant.sources.jniLibs?.addGeneratedSourceDirectory(buildMagpie, BuildMagpie::outputDir)
        variant.sources.assets?.addGeneratedSourceDirectory(magpieNotices, MagpieNotices::outputDir)
    }
}

dependencies {
    implementation(project(":core"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.kotlinx.coroutines.android)
}
