import java.io.File
import javax.inject.Inject

plugins {
    alias(libs.plugins.android.library)
}

// DeepSeek Harness as an AI core: DshEngine talks to dsh (run in Termux, see dsh-host/) over a loopback socket.
android {
    namespace = "com.lanqi.weiassist.dsh"
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
 * Builds the dsh bridge (dsh-host/, bundled by esbuild) and puts what the in-app setup installs in Termux (the bridge,
 * the profile patch, the setup script) into generated assets under dsh/. Needs Node and npm on PATH, like pi-host.
 */
abstract class BuildDshHost @Inject constructor(private val exec: ExecOperations) : DefaultTask() {
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val sources: ConfigurableFileCollection

    @get:Internal
    abstract val dshHostDir: DirectoryProperty

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun build() {
        val dir = dshHostDir.get().asFile
        val npm = if (System.getProperty("os.name").startsWith("Windows")) "npm.cmd" else "npm"
        val installed = File(dir, "node_modules/.package-lock.json")
        if (!installed.exists() || installed.lastModified() < File(dir, "package-lock.json").lastModified()) {
            exec.exec { workingDir = dir; commandLine(npm, "ci", "--ignore-scripts", "--no-audit", "--no-fund") }
        }
        val out = outputDir.get().asFile.also { it.deleteRecursively(); it.mkdirs() }
        exec.exec { workingDir = dir; commandLine(npm, "run", "build", "--", "--out", out.absolutePath) }
    }
}

val dshHost = rootProject.layout.projectDirectory.dir("dsh-host")

androidComponents {
    onVariants { variant ->
        val task = tasks.register<BuildDshHost>("buildDshHost${variant.name.replaceFirstChar(Char::uppercase)}") {
            description = "Bundles the dsh bridge and its Termux setup into this variant's assets."
            dshHostDir.set(dshHost)
            sources.from(
                dshHost.dir("src"), dshHost.dir("profile"), dshHost.dir("termux"),
                dshHost.file("build.mjs"), dshHost.file("package.json"), dshHost.file("package-lock.json"),
            )
        }
        variant.sources.assets?.addGeneratedSourceDirectory(task, BuildDshHost::outputDir)
    }
}

dependencies {
    api(project(":core"))
    testImplementation(libs.junit)
    // Android's org.json is a stub in local unit tests; use the real library there.
    testImplementation(libs.org.json)
}
