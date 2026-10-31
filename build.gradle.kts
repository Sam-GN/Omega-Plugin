plugins {
    kotlin("jvm") version "2.1.20"
    application
}

group = "org.example"
version = "1.5.8-beta"

repositories {
    mavenCentral()
}

dependencies {
    // JNA is dual-licensed Apache 2.0 OR LGPL 2.1-or-later. This project is GPLv3-or-later, and both
    // options are compatible with it; the LGPL 2.1 option is the one taken here, and that election
    // is recorded in README.md and in the licence notice on the About screen so the notice
    // obligations are definite. Keep the three in agreement.
    //
    // It is the only third-party runtime dependency, and it exists so that ASIO and WASAPI can be
    // reached without a native build step. The Steinberg ASIO SDK is deliberately NOT used: it would
    // add a native build step and vendored code under a licence of its own, for an interface that
    // is hand-declared here from its published, stable-since-2000 vtable layout instead.
    implementation("net.java.dev.jna:jna:5.19.1")
    implementation("net.java.dev.jna:jna-platform:5.19.1")

    testImplementation(kotlin("test"))
}

kotlin {
    // 21, not 11: generational ZGC is the whole reason. Measured at a 64-frame ASIO buffer under
    // allocation pressure, G1 lost 1.15 % of realtime with a worst callback gap of 17.1 ms while
    // generational ZGC lost 0.01 % with a worst gap of 4.1 ms. At a 1.45 ms period that difference
    // is audible clicks versus none.
    jvmToolchain(21)
}

/**
 * The realtime JVM flags.
 *
 * - `IgnoreUnrecognizedVMOptions` first, so this list survives both directions of JDK drift:
 *   `ZGenerational` turns generational mode on for 21-23 and is obsolete-but-harmless from 24,
 *   where generational is the only ZGC there is.
 * - `AlwaysPreTouch` so first-touch page faults happen at startup rather than mid-stream.
 * - `-Xss` is left alone; the audio callback stack is shallow.
 */
val gcArgs = listOf(
    "-XX:+IgnoreUnrecognizedVMOptions",
    // Only needed if someone points an old JRE at the launcher: ZGC was experimental before 15, and
    // without this the JVM dies with "must be enabled via -XX:+UnlockExperimentalVMOptions" instead
    // of the honest "unsupported class file version" that follows a moment later.
    "-XX:+UnlockExperimentalVMOptions",
    "-XX:+UseZGC",
    "-XX:+ZGenerational",
    "-XX:+AlwaysPreTouch",
    "-Xms256m",
    "-Xmx512m",
    // JNA calls System.load; without this JDK 24+ prints a restricted-method warning on every run.
    "--enable-native-access=ALL-UNNAMED",
)

tasks.test {
    useJUnitPlatform()
    // The DSP tests do not care about the collector, but running them on the shipping one means a
    // regression in allocation behaviour has somewhere to show up.
    jvmArgs(gcArgs)

    // The resolved runtime classpath, handed to LicencesTest so it can check the third-party table
    // against what actually ships. The test cannot work this out for itself: its own classpath also
    // carries kotlin-test and the JUnit engine, which are not in the jar, and the entry that went
    // missing (org.jetbrains:annotations) is transitive and named nowhere in this file. Reading the
    // resolved configuration is the only view that includes it.
    val runtimeJars = configurations.runtimeClasspath.map { cp -> cp.joinToString(File.pathSeparator) }
    inputs.property("runtimeClasspath", runtimeJars)
    doFirst {
        systemProperty("omega.runtimeClasspath", runtimeJars.get())
    }
}

application {
    mainClass.set("org.example.MainKt")
    applicationName = "OmegaPlugin"
    // Picked up by `gradlew run` and baked into the start scripts that `installDist` and
    // `distZip` generate. A bare `java -jar` gets none of this, which is why Main checks the
    // collector at startup and says so rather than quietly running badly.
    applicationDefaultJvmArgs = gcArgs
}

// Single self-contained jar that runs on Windows and Linux with `java -jar`.
val fatJar by tasks.registering(Jar::class) {
    group = "distribution"
    description = "Builds a standalone runnable jar (java -jar build/libs/OmegaPlugin-<version>-all.jar)"
    archiveClassifier.set("all")
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    manifest {
        attributes["Main-Class"] = "org.example.MainKt"
        attributes["Implementation-Title"] = "OmegaPlugin"
        attributes["Implementation-Version"] = project.version
        // JNA ships its own native stubs inside its jar and extracts them at first use; this stops
        // the JDK 24+ restricted-method warning when it does.
        attributes["Enable-Native-Access"] = "ALL-UNNAMED"
    }
    from(sourceSets.main.get().output)
    dependsOn(configurations.runtimeClasspath)
    from({
        configurations.runtimeClasspath.get()
            .filter { it.name.endsWith(".jar") }
            .map { zipTree(it) }
    })
    // GPLv3 §4: recipients must be given a copy of the licence *along with the Program*. A fat jar
    // handed over on its own is the whole program, so the licence has to be inside it — a LICENSE
    // file left behind in a repository is not "along with" anything.
    //
    // At the jar root, deliberately not META-INF/LICENSE: that path is already occupied by JNA's own
    // notice, which arrives with its zipTree above and must stay intact (LGPL 2.1 §6, and simple
    // honesty about whose code is in here). With DuplicatesStrategy.EXCLUDE one of the two would
    // silently win, and which one is not something to leave to file ordering.
    from(rootProject.file("LICENSE"))
    // Not required, but it carries the third-party table and says where the source is, which is
    // exactly what someone holding only the jar would want next.
    from(rootProject.file("README.md"))
    exclude("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA", "module-info.class")
}

/**
 * The same two files in `installDist` and `distZip`.
 *
 * The application plugin builds those out of the jars and the start scripts and nothing else, so
 * without this the archive most likely to be attached to a release is the one copy of the program
 * with no licence in it.
 */
distributions {
    main {
        contents {
            from(rootProject.file("LICENSE"))
            from(rootProject.file("README.md"))
        }
    }
}

/**
 * Launchers next to the fat jar, because a manifest cannot carry VM options and the collector
 * choice is part of the realtime story rather than a tuning preference.
 */
val launchers by tasks.registering {
    group = "distribution"
    description = "Writes omega.bat and omega.sh next to the fat jar, with the realtime JVM flags"
    val outputDir = layout.buildDirectory.dir("libs")
    val jarName = "OmegaPlugin-${project.version}-all.jar"
    val flags = gcArgs.joinToString(" ")
    outputs.files(outputDir.map { it.file("omega.bat") }, outputDir.map { it.file("omega.sh") })
    doLast {
        val dir = outputDir.get().asFile
        dir.mkdirs()
        dir.resolve("omega.bat").writeText(
            "@echo off\r\njava $flags -jar \"%~dp0$jarName\" %*\r\n"
        )
        val sh = dir.resolve("omega.sh")
        sh.writeText(
            "#!/bin/sh\nexec java $flags -jar \"$(dirname \"$0\")/$jarName\" \"$@\"\n"
        )
        sh.setExecutable(true)
    }
}

tasks.named("build") {
    dependsOn(fatJar, launchers)
}

/* ------------------------------------------------------------------------------------------------
 * The portable bundle: the program plus the JVM it needs.
 *
 * `distZip` and the fat jar both assume a Java 21 on the target machine's PATH, which is a large
 * assumption to hand to someone who just wants to plug a guitar in — and a wrong one on most
 * machines, since the common install is 8, 11 or 17 and this needs 21 for generational ZGC. What
 * follows produces a folder that runs on a Windows box with no Java at all.
 * ---------------------------------------------------------------------------------------------- */

/**
 * `jdeps --print-module-deps` on the fat jar answers java.base, java.desktop, java.logging and
 * java.management. Two more are added by hand, because jdeps reads bytecode references and cannot
 * see either of these:
 *
 * - `jdk.unsupported` — JNA reaches `sun.misc.Unsafe` reflectively. Leaving it out yields a runtime
 *   that starts, opens the window, and then fails to load *any* native backend, which presents as
 *   "only Java Sound on this PC" rather than as a missing module.
 * - `jdk.management` — `com.sun.management.ThreadMXBean.getCurrentThreadAllocatedBytes`, which is
 *   how the allocation assertion in `AudioEngineTest` is measured and how a diagnostic build would
 *   check the same thing in the field.
 *
 * Not included, deliberately: `jdk.localedata` (the UI is English and CLDR's base data covers it)
 * and the crypto/TLS modules (nothing here opens a socket).
 */
val portableModules = listOf(
    "java.base",
    "java.desktop",
    "java.logging",
    "java.management",
    "jdk.management",
    "jdk.unsupported",
).joinToString(",")

val portableRuntimeDir = layout.buildDirectory.dir("portable/runtime")

/**
 * The trimmed runtime. ~45 MB against ~180 MB for the full JDK, which is the difference between a
 * bundle that fits on any USB stick and one that people zip a second time before sending.
 *
 * A jlink image is **per-platform**: this is built from the Windows x64 toolchain and runs on
 * Windows x64 only. Producing a Linux bundle from here would need the Linux JDK's `jmods` passed
 * via `--module-path`; that is a real option and not done yet, hence the archive's name says so.
 */
val portableRuntime by tasks.registering(Exec::class) {
    group = "distribution"
    description = "jlinks a trimmed Java 21 runtime for the portable bundle"
    // No declared inputs beyond the toolchain, so let the up-to-date check rest on the output tree.
    outputs.dir(portableRuntimeDir)
    doFirst {
        val jdkHome = javaToolchains.launcherFor {
            languageVersion.set(JavaLanguageVersion.of(21))
        }.get().metadata.installationPath.asFile
        val jlink = listOf("bin/jlink.exe", "bin/jlink")
            .map { jdkHome.resolve(it) }
            .firstOrNull { it.isFile }
            ?: error("No jlink in the Java 21 toolchain at $jdkHome")
        val out = portableRuntimeDir.get().asFile
        // jlink refuses to write into a directory that already exists, and says so unhelpfully.
        out.deleteRecursively()
        out.parentFile.mkdirs()
        commandLine(
            jlink.absolutePath,
            "--add-modules", portableModules,
            "--strip-debug",
            "--no-header-files",
            "--no-man-pages",
            "--compress", "zip-6",
            "--output", out.absolutePath,
        )
    }
}

/**
 * Two launchers, because the program has two faces and one `.bat` cannot serve both.
 *
 * `OmegaPlugin.bat` is the one to double-click: `javaw.exe` behind `start`, so no console window is
 * left sitting behind the GUI. `omega.bat` is `java.exe` and forwards its arguments, which is what
 * `--list-backends`, `--selftest` and `--measure` need — they print, and printing into a window that
 * closes on exit is the same as not printing.
 *
 * Both hardcode the bundled runtime rather than resolving `java` from PATH. That is the whole point:
 * a machine with a stale Java 8 on PATH must not be able to pick it up and fail confusingly.
 */
/**
 * The bundle read-me, shared by both Windows bundles.
 *
 * There are two of them — the `.bat`-and-jlink `portableZip` and the jpackage `exeZip` — and every
 * word of the troubleshooting advice in here applies to both. Parameterised on the launcher names
 * and written once, because the copy that used to live inside `portableScripts` would have gone
 * stale the first time the exe bundle changed a line of it, and a read-me that names a launcher the
 * folder does not contain is worse than no read-me.
 *
 * `redirect` is why the diagnostics survive the exe bundle having only one launcher. A GUI-subsystem
 * binary attaches to no console, so its output goes nowhere on screen — but redirected it inherits a
 * perfectly good handle, so `OmegaPlugin.exe --list-backends > report.txt` produces the same bytes
 * `omega.bat` would print. `portableZip` passes an empty string and keeps the on-screen version.
 *
 * Every interpolated value must be a single line, or one with its continuations indented to match
 * the literal: `trimIndent` runs after interpolation and computes the common prefix across the
 * result, so one injected line at column zero would strip the indentation off the whole file.
 */
fun readMeText(
    version: String,
    gui: String,
    console: String,
    appDir: String,
    redirect: String,
    howTo: String,
    runtime: RuntimeProvenance,
): String =
    """
    Omega-Plugin $version - portable bundle for Windows x64
    ===========================================================

    Nothing to install. Unzip anywhere you can write to and double-click $gui.
    A Java runtime is inside this folder (runtime\); the launchers use it and ignore any
    Java already on the machine, so an older Java installed here cannot interfere.

    Keep the folder together. $gui, $appDir\ and runtime\ are one unit - moving
    $gui out on its own will not work.

    If the audio is bad, do this before anything else
    -------------------------------------------------
    Open a Command Prompt in this folder and run:

        $console --list-backends$redirect

    $howTo

      ASIO             lowest latency, ~6-10 ms round trip. Needs the audio interface's own
                       ASIO driver installed. Install it from the manufacturer if missing.
      WASAPI exclusive ~18 ms. No third-party driver needed.
      Java Sound       ~160-200 ms. The fallback. If this is all you have, the two above
                       could not start, and --list-backends says what stopped them.

    Then check the machine actually keeps up, with the effects on and under memory pressure:

        $console --selftest --chain --stress <input-device> <output-device> 60$redirect

    Watch realtimeRatio, not deadlineMisses. A collection stalls the audio thread between
    callbacks rather than making one callback slow, so realtimeRatio below 100% means audio
    was lost even while deadlineMisses reads zero. If it drops, raise the buffer size.

    Licences
    --------
    Omega-Plugin is GPLv3-or-later; see LICENSE, and Menu -> Licences in the app for the
    third-party components and why each is permitted.

    The runtime\ folder is ${runtime.build} - OpenJDK, built by ${runtime.vendor} - used under
    GPLv2 with the Classpath Exception. Its own notices are preserved in runtime\legal\.
    Its source is published at
        ${runtime.source}
    and the build named here corresponds to a tag in that repository.
    """.trimIndent().replace("\n", "\r\n") + "\r\n"

/** The bundled runtime, named exactly. See [runtimeProvenance]. */
data class RuntimeProvenance(val build: String, val vendor: String, val source: String)

/**
 * What the bundle's read-me says about the runtime it ships, read from the toolchain itself.
 *
 * Redistributing an OpenJDK binary carries that binary's own licence obligation - GPLv2 §3, since
 * that is the JDK's licence - and the read-me discharges it by naming the exact build and where its
 * source is published. Both come from the toolchain's `release` file rather than being typed in,
 * because foojay provisions the toolchain and a number copied by hand would be wrong the first
 * time it moved; a build name that was not the one shipped discharges nothing. A toolchain whose
 * `release` file lacks any of the three fields fails the bundle by name, rather than writing a
 * read-me with a hole in it.
 */
fun runtimeProvenance(): RuntimeProvenance {
    val jdkHome = javaToolchains.launcherFor {
        languageVersion.set(JavaLanguageVersion.of(21))
    }.get().metadata.installationPath.asFile
    val release = jdkHome.resolve("release")
    if (!release.isFile) error("No release file in the Java 21 toolchain at $jdkHome; cannot name the bundled runtime")
    val fields = release.readLines().mapNotNull { line ->
        val eq = line.indexOf('=')
        if (eq < 0) null else line.substring(0, eq) to line.substring(eq + 1).trim().trim('"')
    }.toMap()
    fun field(name: String) = fields[name]?.takeIf { it.isNotBlank() }
        ?: error("$release has no $name; the read-me cannot say where the bundled runtime's source is")
    return RuntimeProvenance(
        build = field("IMPLEMENTOR_VERSION"),
        vendor = field("IMPLEMENTOR"),
        source = field("SOURCE_REPO"),
    )
}

val portableScriptsDir = layout.buildDirectory.dir("portable/scripts")

val portableScripts by tasks.registering {
    group = "distribution"
    description = "Writes the portable bundle's launchers and its read-me"
    val outputDir = portableScriptsDir
    val jarName = "OmegaPlugin-${project.version}-all.jar"
    val flags = gcArgs.joinToString(" ")
    val versionText = project.version.toString()
    outputs.dir(outputDir)
    doLast {
        val dir = outputDir.get().asFile
        dir.deleteRecursively()
        dir.mkdirs()
        dir.resolve("OmegaPlugin.bat").writeText(
            """
            @echo off
            rem Double-click this one. No console window; the GUI is the interface.
            start "" "%~dp0runtime\bin\javaw.exe" $flags -jar "%~dp0lib\$jarName" %*
            """.trimIndent().replace("\n", "\r\n") + "\r\n"
        )
        // The two encoding lines are a pair and only make sense together. The diagnostics output
        // contains en-dashes; JDK 19+ picks `stdout.encoding` from the console when attached to one
        // and from the native encoding (cp1252 here) when redirected, so a lone 0x97 reaches a
        // UTF-8 console and comes out as a replacement character. Fixing only the codepage or only
        // the property swaps one mismatch for another - set both, so redirecting to a file and
        // reading on screen agree.
        dir.resolve("omega.bat").writeText(
            """
            @echo off
            rem The console face: --list-backends, --list-devices, --selftest, --measure.
            chcp 65001 >nul
            "%~dp0runtime\bin\java.exe" -Dstdout.encoding=UTF-8 -Dstderr.encoding=UTF-8 $flags -jar "%~dp0lib\$jarName" %*
            """.trimIndent().replace("\n", "\r\n") + "\r\n"
        )
        dir.resolve("READ-ME-FIRST.txt").writeText(
            readMeText(
                versionText, "OmegaPlugin.bat", "omega.bat", "lib",
                redirect = "",
                runtime = runtimeProvenance(),
                howTo = "It prints which audio backends loaded and, for each one that did not, why. That is\n    almost always the answer to \"why is there so much delay on this PC\":",
            )
        )
    }
}

/**
 * Runs nothing on its own; `build` deliberately does not depend on it. jlink costs tens of seconds
 * and the bundle is a release artifact, not something wanted on every compile.
 *
 *     gradlew.bat portableZip
 */
val portableZip by tasks.registering(Zip::class) {
    group = "distribution"
    description = "Self-contained Windows x64 zip: program, launchers and a bundled Java 21 runtime"
    archiveFileName.set("OmegaPlugin-${project.version}-windows-x64-portable.zip")
    destinationDirectory.set(layout.buildDirectory.dir("distributions"))
    val root = "OmegaPlugin-${project.version}"
    into("$root/lib") { from(fatJar) }
    into("$root/runtime") { from(portableRuntime) }
    into(root) {
        from(portableScripts)
        // GPLv3 section 4 again: the licence travels with the program, not with the repository.
        from(rootProject.file("LICENSE"))
        from(rootProject.file("README.md"))
    }
}

/* ------------------------------------------------------------------------------------------------
 * The exe bundle: the same program behind a real Windows launcher.
 *
 * `portableZip` above solves the runtime problem and leaves the launcher problem: what a user
 * double-clicks is a `.bat`, which shows up in Task Manager as `java`, cannot carry an icon, and
 * reads to most people as a script rather than as an application. jpackage generates a native
 * launcher instead, and — the part that matters here rather than cosmetically — it writes the VM
 * options into a `.cfg` beside the exe, which is the same hole `omega.bat` exists to plug. The
 * realtime flags stop depending on the user having started the program the documented way.
 *
 * It reuses `portableRuntime` through `--runtime-image` rather than letting jpackage run its own
 * jlink. That is deliberate: `portableModules` includes two modules jdeps cannot infer, and
 * jpackage's automatic mode would infer exactly the set that leaves the app running with no native
 * backend at all.
 *
 * Still Windows x64 only, and for the same reason `portableZip` is — the runtime inside it is.
 * ---------------------------------------------------------------------------------------------- */

/**
 * `--app-version` lands in the exe's VERSIONINFO resource, where Windows accepts only
 * `major[.minor[.patch]]` digits: `1.0.61-alpha` is refused outright. The suffix is dropped for
 * that one field. Everything a person sees keeps the full version — the jar, the zip, the folder
 * inside it — and the About screen reads the manifest, not this.
 */
val exeVersion = project.version.toString().substringBefore('-')

/**
 * A Windows `.ico`, packed from the PNGs that are already in `src/main/resources/icons` and already
 * the window's icon via `ui/AppIcon`. Built rather than committed so that the icon has one source:
 * a checked-in `.ico` would be a second copy that nothing ever re-derives, which makes it the copy
 * that goes stale.
 *
 * ICO is a directory of independent images, and since Vista each entry may be a PNG byte-for-byte —
 * so this concatenates the files and writes the 16-byte directory entries in front of them. No
 * decoding, so no `ImageIO` and no chance of a re-encode losing the alpha. Note the width and
 * height fields: one byte each, so 256 is encoded as 0.
 */
val iconSizes = listOf(16, 24, 32, 48, 64, 128, 256)

val windowsIconFile = layout.buildDirectory.file("exe/OmegaPlugin.ico")

val windowsIcon by tasks.registering {
    group = "distribution"
    description = "Packs the PNG icon set into a Windows .ico for the jpackage launchers"
    val pngs = iconSizes.map { rootProject.file("src/main/resources/icons/omega-$it.png") }
    val target = windowsIconFile
    inputs.files(pngs)
    outputs.file(target)
    doLast {
        val payloads = pngs.map { it.readBytes() }
        val out = target.get().asFile
        out.parentFile.mkdirs()
        // Fixed size: a 6-byte ICONDIR then one 16-byte ICONDIRENTRY per image. Written by hand
        // rather than through a stream because `java.io` does not resolve in a Kotlin build script
        // — Gradle's own `java` extension shadows the package name.
        val header = ByteArray(6 + 16 * iconSizes.size)
        var p = 0
        fun put8(v: Int) {
            header[p++] = (v and 0xff).toByte()
        }
        fun le16(v: Int) {
            put8(v)
            put8(v ushr 8)
        }
        fun le32(v: Int) {
            le16(v and 0xffff)
            le16(v ushr 16)
        }
        le16(0)                                  // reserved
        le16(1)                                  // type: icon
        le16(iconSizes.size)
        var offset = header.size
        iconSizes.forEachIndexed { i, size ->
            val dim = if (size >= 256) 0 else size
            put8(dim)                            // width
            put8(dim)                            // height
            put8(0)                              // palette size: none
            put8(0)                              // reserved
            le16(1)                              // colour planes
            le16(32)                             // bits per pixel
            le32(payloads[i].size)
            le32(offset)
            offset += payloads[i].size
        }
        out.outputStream().buffered().use { s ->
            s.write(header)
            payloads.forEach { s.write(it) }
        }
    }
}

/**
 * jpackage copies everything in `--input` into the image's `app/`, and `build/libs` also holds the
 * thin jar, the launchers and whatever else has accumulated there. Stage the one jar it should see.
 */
val exeInputDir = layout.buildDirectory.dir("exe/input")

val exeInput by tasks.registering(Sync::class) {
    group = "distribution"
    description = "Stages the fat jar on its own for jpackage --input"
    from(fatJar)
    into(exeInputDir)
}

val exeImageDir = layout.buildDirectory.dir("exe/image")

val exeImage by tasks.registering(Exec::class) {
    group = "distribution"
    description = "Runs jpackage to produce the app-image: one launcher, OmegaPlugin.exe"
    dependsOn(exeInput, portableRuntime, windowsIcon)
    // Declared, not just depended on. `dependsOn` alone orders the tasks and tells Gradle nothing
    // about staleness, so with only an output directory to look at this reported UP-TO-DATE after a
    // code change and shipped the previous exe. The version goes in as a property because it
    // reaches the launcher's VERSIONINFO and appears nowhere in these files.
    inputs.dir(exeInputDir)
    inputs.dir(portableRuntimeDir)
    inputs.file(windowsIconFile)
    inputs.property("appVersion", exeVersion)
    outputs.dir(exeImageDir)
    doFirst {
        val jdkHome = javaToolchains.launcherFor {
            languageVersion.set(JavaLanguageVersion.of(21))
        }.get().metadata.installationPath.asFile
        val jpackage = listOf("bin/jpackage.exe", "bin/jpackage")
            .map { jdkHome.resolve(it) }
            .firstOrNull { it.isFile }
            ?: error("No jpackage in the Java 21 toolchain at $jdkHome")
        val dest = exeImageDir.get().asFile
        // jpackage refuses to write over an existing image, as jlink does over an existing runtime.
        dest.deleteRecursively()
        dest.parentFile.mkdirs()
        val argv = mutableListOf(
            jpackage.absolutePath,
            "--type", "app-image",
            "--name", "OmegaPlugin",
            "--app-version", exeVersion,
            "--input", exeInputDir.get().asFile.absolutePath,
            "--main-jar", "OmegaPlugin-${project.version}-all.jar",
            "--main-class", "org.example.MainKt",
            "--runtime-image", portableRuntimeDir.get().asFile.absolutePath,
            "--icon", windowsIconFile.get().asFile.absolutePath,
            "--dest", dest.absolutePath,
            // The product name, not a sentence describing it. jpackage puts `--description` into
            // the exe's VERSIONINFO `FileDescription`, and Windows treats that field as the
            // application's *name*: it is what the taskbar tooltip, the taskbar's grouped-window
            // header and Task Manager's Name column all show. A prose description there means the
            // app is called "Realtime guitar effects processor" everywhere the user meets it.
            // Chrome ships `FileDescription=Google Chrome` for exactly this reason.
            "--description", "Omega-Plugin",
            "--vendor", "Sam-GN",
            "--copyright", "GPLv3-or-later; see LICENSE",
        )
        // One --java-options per flag. Space-joining them into a single argument works right up
        // until a flag contains a space, which is not a failure worth waiting for.
        //
        // The encoding pair is this bundle's only addition to gcArgs, and it is safe here in a way
        // it is not in `omega.bat`. This is the sole launcher and it is GUI-subsystem, so it never
        // attaches to a console: the diagnostics output only ever reaches a redirect, which is what
        // READ-ME-FIRST.txt now tells the user to do. Left alone, the JDK falls back to the native
        // encoding and writes cp1252 — a lone 0x97 for an em-dash, which is not valid UTF-8 and
        // comes out as a replacement character anywhere the file is later pasted. There is no
        // on-screen case to keep in step with, so pinning UTF-8 has no downside.
        val exeJavaOptions = gcArgs + listOf("-Dstdout.encoding=UTF-8", "-Dstderr.encoding=UTF-8")
        exeJavaOptions.forEach { argv += listOf("--java-options", it) }
        commandLine(argv)
    }
}

val exeExtrasDir = layout.buildDirectory.dir("exe/extras")

val exeExtras by tasks.registering {
    group = "distribution"
    description = "Writes the exe bundle's read-me"
    val outputDir = exeExtrasDir
    val versionText = project.version.toString()
    outputs.dir(outputDir)
    doLast {
        val dir = outputDir.get().asFile
        dir.deleteRecursively()
        dir.mkdirs()
        // `app`, not `lib`: that is where jpackage puts the jar, and the read-me tells the user to
        // keep the folder's parts together by name.
        //
        // One launcher in this bundle, so the diagnostics run through the GUI exe with their output
        // redirected — see the note on `readMeText`.
        dir.resolve("READ-ME-FIRST.txt").writeText(
            readMeText(
                versionText, "OmegaPlugin.exe", "OmegaPlugin.exe", "app",
                redirect = " > report.txt",
                runtime = runtimeProvenance(),
                howTo = "That writes report.txt beside this file - open it in Notepad. It names every audio\n    backend that loaded and, for each one that did not, why. That is almost always the\n    answer to \"why is there so much delay on this PC\":",
            )
        )
    }
}

/**
 * Like `portableZip`, `build` deliberately does not depend on this: jpackage costs tens of seconds
 * on top of jlink's, and the bundle is a release artefact.
 *
 *     gradlew.bat exeZip
 */
val exeZip by tasks.registering(Zip::class) {
    group = "distribution"
    description = "Self-contained Windows x64 zip built around OmegaPlugin.exe"
    // The name a guitarist reads on the release page, not the one a build engineer would write.
    // `-windows-x64-exe` names the launcher format and the architecture, neither of which means
    // anything to the audience; "Windows" is the only part that helps them choose. Still x64-only
    // — that is a fact about the bundled runtime, and it belongs in the release notes rather than
    // in a filename nobody can act on.
    archiveFileName.set("Omega-Plugin-${project.version}-Windows.zip")
    destinationDirectory.set(layout.buildDirectory.dir("distributions"))
    dependsOn(exeImage)
    into("OmegaPlugin-${project.version}") {
        from(exeImageDir.map { it.dir("OmegaPlugin") })
        from(exeExtras)
        // GPLv3 section 4, the same as everywhere else: the licence travels with the program.
        from(rootProject.file("LICENSE"))
        from(rootProject.file("README.md"))
    }
}

/* ------------------------------------------------------------------------------------------------
 * The Linux AppImage: the same program and the same kind of bundled runtime, in one file.
 *
 * Structurally `exeZip` with a different launcher. An AppDir is a folder — `AppRun`, a `.desktop`
 * entry, an icon, the jar and a jlink runtime — and `appimagetool` squashes it into one executable
 * that runs on any distribution without installing anything, including the immutable ones (SteamOS,
 * Bazzite) where a `.deb` or `.rpm` cannot go. See docs/linux-compat.md for why AppImage and not
 * Flatpak.
 *
 * `portableRuntime` is reused unchanged: jlink builds for the platform it runs on, so on a Linux
 * host it already produces a Linux runtime. That is also why this can only be built on Linux, the
 * same way the exe can only be built on Windows.
 *
 * **The glibc floor is the build machine's.** Nothing here is compiled natively — the runtime is
 * Temurin's and JNA ships its own stub — but the AppImage is only promised to run on systems at
 * least as new as the one that built it, so the release builds on the oldest Ubuntu runner.
 * ---------------------------------------------------------------------------------------------- */

val appDir = layout.buildDirectory.dir("appimage/OmegaPlugin.AppDir")

/**
 * What the AppImage says about the runtime it carries — the GPLv2 §3 obligation the Windows read-me
 * discharges, in the place a Linux user would look: inside the image, beside `runtime/legal/`.
 * Read off the toolchain for the same reason, so it names the build actually shipped.
 */
fun linuxRuntimeNotice(version: String, runtime: RuntimeProvenance): String =
    """
    Omega-Plugin $version - AppImage for Linux x86_64
    =================================================

    The runtime/ folder inside this AppImage is ${runtime.build} - OpenJDK, built by
    ${runtime.vendor} - used under GPLv2 with the Classpath Exception. Its own notices are in
    runtime/legal/. Its source is published at
        ${runtime.source}
    and the build named here corresponds to a tag in that repository.

    Omega-Plugin itself is GPLv3-or-later; see LICENSE, and Menu -> Licences in the app.
    """.trimIndent() + "\n"

val appDirGenerated = layout.buildDirectory.dir("appimage/generated")

/** The three files the AppDir needs that are written rather than copied. */
val appDirFiles by tasks.registering {
    group = "distribution"
    description = "Writes AppRun, the .desktop entry and the runtime notice for the AppDir"
    val jarName = "OmegaPlugin-${project.version}-all.jar"
    val flags = gcArgs.joinToString(" ")
    val versionText = project.version.toString()
    val outputDir = appDirGenerated
    outputs.dir(outputDir)
    doLast {
        val dir = outputDir.get().asFile
        dir.deleteRecursively()
        dir.mkdirs()
        // The bundled runtime and nothing else, the F5 rule: a `java` from PATH is whatever the
        // distribution ships, routinely 17, and generational ZGC needs 21. Resolved through
        // readlink so it works from the mounted image and from an extracted one alike.
        val appRun = dir.resolve("AppRun")
        appRun.writeText(
            """
            #!/bin/sh
            # Omega-Plugin AppImage launcher. Runs the Java inside this image, never one from PATH.
            # Diagnostics work from a terminal:  ./Omega-Plugin-*.AppImage --list-backends
            HERE="${'$'}(dirname "${'$'}(readlink -f "${'$'}0")")"
            exec "${'$'}HERE/runtime/bin/java" -Dstdout.encoding=UTF-8 -Dstderr.encoding=UTF-8 $flags -jar "${'$'}HERE/lib/$jarName" "${'$'}@"
            """.trimIndent() + "\n"
        )
        appRun.setExecutable(true, false)
        // StartupWMClass is the JDK's own WM_CLASS for this main class: dots become dashes. Without
        // it a dock shows the running window as a second, iconless "java" next to the launcher.
        dir.resolve("omega-plugin.desktop").writeText(
            """
            [Desktop Entry]
            Type=Application
            Name=Omega-Plugin
            GenericName=Guitar effects
            Comment=Realtime guitar effects processor
            Exec=OmegaPlugin
            Icon=omega-plugin
            Terminal=false
            Categories=AudioVideo;Audio;
            StartupWMClass=org-example-MainKt
            """.trimIndent() + "\n"
        )
        dir.resolve("RUNTIME-NOTICE.txt").writeText(linuxRuntimeNotice(versionText, runtimeProvenance()))
    }
}

val appDirStage by tasks.registering(Sync::class) {
    group = "distribution"
    description = "Assembles the AppDir: AppRun, .desktop, icon, jar and the bundled runtime"
    into(appDir)
    into("lib") { from(fatJar) }
    into("runtime") { from(portableRuntime) }
    from(appDirFiles)
    from(rootProject.file("LICENSE"))
    from(rootProject.file("README.md"))
    // The icon twice, for two readers: the .desktop entry names it and appimagetool wants it at the
    // root under that name; file managers look for .DirIcon.
    from(rootProject.file("src/main/resources/icons/omega-256.png")) { rename { "omega-plugin.png" } }
    from(rootProject.file("src/main/resources/icons/omega-256.png")) { rename { ".DirIcon" } }
}

/**
 * `appimagetool` is found, in order, from `-Pappimagetool=<path>`, the `APPIMAGETOOL` environment
 * variable, or `appimagetool` on PATH. It is not downloaded here: a build that fetches and runs an
 * executable from the network should say so where a person can see it, and CI does, with a pinned
 * version and checksum (.github/workflows/release.yml).
 *
 *     ./gradlew appImage -Pappimagetool=/path/to/appimagetool-x86_64.AppImage
 *
 * Like `exeZip`, `build` does not depend on this; it is a release artefact.
 */
val appImage by tasks.registering(Exec::class) {
    group = "distribution"
    description = "Packs the AppDir into Omega-Plugin-<version>-Linux.AppImage (Linux only)"
    dependsOn(appDirStage)
    // The name a guitarist reads on the release page, matching the Windows zip's "-Windows".
    val output = layout.buildDirectory.file("distributions/Omega-Plugin-${project.version}-Linux.AppImage")
    val tool = (findProperty("appimagetool") as String?) ?: System.getenv("APPIMAGETOOL") ?: "appimagetool"
    inputs.dir(appDir)
    outputs.file(output)
    doFirst {
        if (!System.getProperty("os.name").startsWith("Linux")) {
            error("The AppImage bundles a Linux runtime made by jlink on this machine, so it can only be built on Linux")
        }
        output.get().asFile.parentFile.mkdirs()
        output.get().asFile.delete()
        // ARCH because appimagetool will not guess it; extract-and-run because a CI container has
        // no FUSE and the tool is itself an AppImage.
        environment("ARCH", "x86_64")
        environment("APPIMAGE_EXTRACT_AND_RUN", "1")
        commandLine(tool, "--no-appstream", appDir.get().asFile.absolutePath, output.get().asFile.absolutePath)
    }
}
