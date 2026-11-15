package com.a55.wifiradar.e2e.harness

import java.io.File
import java.util.concurrent.TimeUnit
import java.util.zip.ZipFile

data class ToolOutput(
    val exitCode: Int,
    val stdout: String,
    val stderr: String,
) {
    val combined: String get() = "$stdout\n$stderr"
}

/**
 * Prong 3 compiled APK binary inspector invoking Android SDK 35 CLI tools (`aapt2`, `apksigner`,
 * `apkanalyzer`) and `ZipFile` inspection against compiled debug and signed release APKs.
 */
object ApkBinaryInspector {

    val projectRoot: File
        get() = File(System.getProperty("wifiradar.projectRoot") ?: "/home/antigravity")

    val sdkRoot: File
        get() = File(System.getProperty("wifiradar.androidSdk") ?: "/home/antigravity/.android-sdk")

    val aapt2Bin: File
        get() = File(sdkRoot, "build-tools/35.0.1/aapt2")

    val apksignerBin: File
        get() = File(sdkRoot, "build-tools/35.0.1/apksigner")

    val apkanalyzerBin: File
        get() = File(sdkRoot, "cmdline-tools/latest/bin/apkanalyzer")

    val debugApk: File
        get() = File(
            System.getProperty("wifiradar.debugApk")
                ?: "/home/antigravity/app/build/outputs/apk/debug/app-debug.apk",
        )

    fun resolveSignedReleaseApk(): File {
        val dir = File(
            System.getProperty("wifiradar.releaseApkDir")
                ?: "/home/antigravity/app/build/outputs/apk/release",
        )
        val signedCandidates = dir.listFiles()
            ?.filter { it.isFile && it.extension == "apk" && !it.name.contains("unsigned", ignoreCase = true) }
            ?.sortedByDescending { it.lastModified() }
            .orEmpty()
        return signedCandidates.firstOrNull() ?: File(dir, "app-release.apk")
    }

    fun runTool(executable: File, vararg args: String, timeoutSec: Long = 30L): ToolOutput {
        check(executable.canExecute()) { "Missing SDK executable: ${executable.absolutePath}" }
        val pb = ProcessBuilder(listOf(executable.absolutePath) + args)
        pb.environment()["JAVA_HOME"] = System.getenv("JAVA_HOME") ?: "/usr/lib/jvm/java-17-openjdk-amd64"
        pb.environment()["ANDROID_HOME"] = sdkRoot.absolutePath
        pb.environment()["ANDROID_SDK_ROOT"] = sdkRoot.absolutePath
        val proc = pb.start()
        val stdoutFuture = java.util.concurrent.CompletableFuture.supplyAsync {
            proc.inputStream.bufferedReader().use { it.readText() }
        }
        val stderrFuture = java.util.concurrent.CompletableFuture.supplyAsync {
            proc.errorStream.bufferedReader().use { it.readText() }
        }
        check(proc.waitFor(timeoutSec, TimeUnit.SECONDS)) {
            proc.destroyForcibly()
            "Timed out running ${executable.name} ${args.joinToString(" ")}"
        }
        return ToolOutput(
            exitCode = proc.exitValue(),
            stdout = stdoutFuture.get(5, TimeUnit.SECONDS),
            stderr = stderrFuture.get(5, TimeUnit.SECONDS),
        )
    }

    fun dumpBadging(apk: File): ToolOutput =
        runTool(aapt2Bin, "dump", "badging", apk.absolutePath)

    fun dumpPermissions(apk: File): ToolOutput =
        runTool(aapt2Bin, "dump", "permissions", apk.absolutePath)

    fun dumpManifestXmlTree(apk: File): ToolOutput =
        runTool(aapt2Bin, "dump", "xmltree", apk.absolutePath, "--file", "AndroidManifest.xml")

    fun verifySignature(apk: File): ToolOutput =
        runTool(apksignerBin, "verify", "--verbose", "--print-certs", apk.absolutePath)

    fun analyzeDexPackages(apk: File): ToolOutput =
        runTool(apkanalyzerBin, "dex", "packages", apk.absolutePath)

    fun analyzeManifestSummary(apk: File): ToolOutput =
        runTool(apkanalyzerBin, "manifest", "summary", apk.absolutePath)

    fun listZipEntries(apk: File): List<String> =
        ZipFile(apk).use { zf -> zf.entries().asSequence().map { it.name }.toList() }
}
