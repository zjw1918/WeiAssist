import java.io.File
import javax.inject.Inject

plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.lanqi.weiassist.pi"
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
 * Builds the Pi host bundle (pi-host/: Pi + our adapter, bundled by esbuild) into generated assets,
 * so the APK always contains exactly what the sources say. Needs Node (see .nvmrc) and npm on PATH.
 * Up to date when pi-host's sources and lockfile are unchanged.
 */
abstract class BuildPiHost @Inject constructor(private val exec: ExecOperations) : DefaultTask() {
    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val sources: DirectoryProperty

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val buildFiles: ConfigurableFileCollection

    @get:Internal
    abstract val piHostDir: DirectoryProperty

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun build() {
        val dir = piHostDir.get().asFile
        val npm = if (System.getProperty("os.name").startsWith("Windows")) "npm.cmd" else "npm"
        val installed = File(dir, "node_modules/.package-lock.json")
        if (!installed.exists() || installed.lastModified() < File(dir, "package-lock.json").lastModified()) {
            exec.exec { workingDir = dir; commandLine(npm, "ci", "--ignore-scripts", "--no-audit", "--no-fund") }
        }
        val out = outputDir.get().asFile.also { it.deleteRecursively(); it.mkdirs() }
        exec.exec { workingDir = dir; commandLine(npm, "run", "build", "--", "--out", out.absolutePath) }
    }
}

val piHost = rootProject.layout.projectDirectory.dir("pi-host")

androidComponents {
    onVariants { variant ->
        val task = tasks.register<BuildPiHost>("buildPiHost${variant.name.replaceFirstChar(Char::uppercase)}") {
            description = "Bundles Pi and the pi-host adapter into this variant's assets."
            piHostDir.set(piHost)
            sources.set(piHost.dir("src"))
            buildFiles.from(
                piHost.file("build.mjs"), piHost.file("package.json"), piHost.file("package-lock.json"),
                piHost.file("tsconfig.json"), piHost.dir("licenses"),
            )
        }
        variant.sources.assets?.addGeneratedSourceDirectory(task, BuildPiHost::outputDir)
    }
}

dependencies {
    api(project(":core"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.webkit)
}
