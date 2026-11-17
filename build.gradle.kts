import org.jetbrains.intellij.platform.gradle.TestFrameworkType
import java.net.URI
import java.security.MessageDigest

plugins {
    id("org.jetbrains.kotlin.jvm") version "2.4.21"
    id("org.jetbrains.intellij.platform") version "2.20.1"
}

group = "dev.akamitori"
version = "1.0.0"

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    intellijPlatform {
        // Compile against CLion; the LSP API ships with it from 2025.2.1 on.
        // Set localClionPath in ~/.gradle/gradle.properties (per machine) to use an installed CLion instead of downloading one.
        // Ignored if the path doesn't exist, so the same gradle.properties works on other machines.
        val local_clion = providers.gradleProperty("localClionPath").orNull?.takeIf { file(it).isDirectory }
        if (local_clion != null) local(local_clion) else clion("2026.2")
        // Puts the IDE jars on the test classpath. The tests are plain JUnit on pure functions,
        // no headless IDE, so they're fast and don't depend on CLion Nova starting up.
        testFramework(TestFrameworkType.Platform)
    }
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.opentest4j:opentest4j:1.3.0")
}

kotlin {
    jvmToolchain(21)
    compilerOptions {
        // Kotlin 2.4 is needed to read CLion 2026.3's class metadata, but the plugin runs against
        // the IDE's bundled stdlib, so stay within what the oldest supported CLion (2026.2) ships.
        apiVersion = org.jetbrains.kotlin.gradle.dsl.KotlinVersion.KOTLIN_2_3
    }
}

intellijPlatform {
    buildSearchableOptions = false
    // ./gradlew verifyPlugin: checks the built plugin for API problems against the oldest supported CLion
    // (sinceBuild 262) and, if present, the locally installed one.
    pluginVerification {
        // Fail on everything except internal API: the Ctrl+hover underline needs CtrlMouseAction/CtrlMouseData,
        // which have no public equivalent. Anything new (compat problems, deprecated, experimental) still fails.
        failureLevel = org.jetbrains.intellij.platform.gradle.tasks.VerifyPluginTask.FailureLevel.ALL -
            org.jetbrains.intellij.platform.gradle.tasks.VerifyPluginTask.FailureLevel.INTERNAL_API_USAGES
        ides {
            create(org.jetbrains.intellij.platform.gradle.IntelliJPlatformType.CLion, "2026.2")
            providers.gradleProperty("localClionPath").orNull?.takeIf { file(it).isDirectory }?.let { local(file(it)) }
        }
    }
    pluginConfiguration {
        ideaVersion {
            sinceBuild = "262"  // CLion 2026.2: first with the LspIntegrationProvider / LspClient API
            untilBuild = provider { null }  // open-ended: works on 2026.x too
        }
    }
}

// ---- Bundled tools ----------------------------------------------------------------------------
// glsl_analyzer (LSP) and glslang (diagnostics) ship inside the plugin for Linux and Windows x64,
// so installing the zip is all a new machine needs. Pinned versions, verified by SHA-256.
// Layout in the plugin: bin/<os>-x64/<exe>, licenses/<tool>-LICENSE.*

class BundledFile(val name: String, val url: String, val sha256: String, val entry: String?, val into: String)

val bundled_files = listOf(
    // glsl_analyzer 1.7.1, GPL-3.0 (shipped unmodified, runs as a separate process)
    BundledFile("glsl_analyzer-linux", "https://github.com/nolanderc/glsl_analyzer/releases/download/v1.7.1/x86_64-linux-musl.zip",
        "7c929b304e0d34abf4305d07895c409861f054613c3ae4070baa7ca15c916e7a", "bin/glsl_analyzer", "bin/linux-x64"),
    BundledFile("glsl_analyzer-windows", "https://github.com/nolanderc/glsl_analyzer/releases/download/v1.7.1/x86_64-windows.zip",
        "117604ba20860abf0808fc5551a6e06d305066c1f4296d81a51f175a2cf2a38c", "bin/glsl_analyzer.exe", "bin/windows-x64"),
    BundledFile("glsl_analyzer-license", "https://raw.githubusercontent.com/nolanderc/glsl_analyzer/v1.7.1/LICENSE.md",
        "cc5470feed66192387f06ae93aed0bd4fefae5a5fd7c1e54a4acf4ea64f1028b", null, "licenses"),
    // glslang 16.6.0, BSD-3-Clause and others (see its LICENSE.txt)
    BundledFile("glslang-linux", "https://github.com/KhronosGroup/glslang/releases/download/16.6.0/glslang-16.6.0-linux-x86_64-release.zip",
        "a3fc4f083b1793eb53e55fa3577ac9649ffbe0340715e30d116290fb5382393f", "bin/glslang", "bin/linux-x64"),
    BundledFile("glslang-windows", "https://github.com/KhronosGroup/glslang/releases/download/16.6.0/glslang-16.6.0-windows-x86_64-release.zip",
        "82bf434e69b9bb4829de7e2b4bc2c5e7a7861e53d66cf75e5cc70f5f694a8d9b", "bin/glslang.exe", "bin/windows-x64"),
    BundledFile("glslang-license", "https://raw.githubusercontent.com/KhronosGroup/glslang/16.6.0/LICENSE.txt",
        "17e70c676e1521ff3e4686f04a2053d93a7e28a33be8de7ec37ab0ff72feb677", null, "licenses"),
)

/** Downloads a URL once and fails the build if its SHA-256 doesn't match. Cached by Gradle (up-to-date check). */
abstract class DownloadVerified : DefaultTask() {
    @get:Input abstract val url: Property<String>
    @get:Input abstract val sha256: Property<String>
    @get:OutputFile abstract val dest: RegularFileProperty

    @TaskAction
    fun run() {
        val out = dest.get().asFile
        out.parentFile.mkdirs()
        URI(url.get()).toURL().openStream().use { input -> out.outputStream().use { input.copyTo(it) } }
        val actual = MessageDigest.getInstance("SHA-256").digest(out.readBytes()).joinToString("") { "%02x".format(it) }
        if (actual != sha256.get()) {
            out.delete()
            throw GradleException("SHA-256 mismatch for ${url.get()}: expected ${sha256.get()}, got $actual")
        }
    }
}

val bundle_downloads = bundled_files.map { f ->
    f to tasks.register<DownloadVerified>("download-${f.name}") {
        url = f.url
        sha256 = f.sha256
        dest = layout.buildDirectory.file("downloads/${f.name}/${f.url.substringAfterLast('/')}")
    }
}

val bundleTools = tasks.register<Sync>("bundleTools") {
    description = "Downloads, verifies and lays out the bundled glsl_analyzer and glslang binaries."
    into(layout.buildDirectory.dir("bundled"))
    includeEmptyDirs = false
    for ((f, download) in bundle_downloads) {
        // Plain strings only inside the lambdas below: the configuration cache can't serialize script objects.
        val target_dir = f.into
        val entry = f.entry
        val downloaded = download.flatMap { it.dest }
        if (entry == null) {
            // A plain file (license): keep it, prefixed with the tool name.
            val prefix = f.name.removeSuffix("-license")
            from(downloaded) {
                into(target_dir)
                rename { "$prefix-$it" }
            }
        } else {
            from(zipTree(downloaded)) {
                include(entry)
                eachFile { path = "$target_dir/$name" }
            }
        }
    }
}

// Every sandbox (buildPlugin's, runIde's, tests'), not just prepareSandbox: runIde has its own prepareSandbox_runIde.
tasks.withType<org.jetbrains.intellij.platform.gradle.tasks.PrepareSandboxTask>().configureEach {
    // Lands next to lib/ in the plugin directory, and therefore in the buildPlugin zip too.
    from(bundleTools) { into(intellijPlatform.projectName.map { "$it" }) }
}

// The end-to-end tests run the bundled glslang; without this they would silently skip on a fresh clone.
tasks.test {
    dependsOn(bundleTools)
}
