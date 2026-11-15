package com.a55.wifiradar.e2e.harness

import java.io.File
import java.nio.charset.StandardCharsets
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue

data class CliExecResult(
    val exitCode: Int,
    val stdout: String,
    val stderr: String,
    val durationMs: Long,
) {
    fun assertSucceeded(context: String = "cli-runner"): CliExecResult {
        assertEquals(
            "$context failed with exitCode=$exitCode.\nSTDERR:\n$stderr\nSTDOUT:\n${stdout.take(1500)}",
            0,
            exitCode,
        )
        assertTrue("$context produced blank stdout", stdout.isNotBlank())
        return this
    }

    fun assertFailedGracefully(context: String = "cli-runner"): CliExecResult {
        assertTrue(
            "$context expected non-zero exit code on invalid input, got 0. STDOUT:\n${stdout.take(500)}",
            exitCode != 0,
        )
        assertTrue(
            "$context expected descriptive error message on stderr, got blank stderr",
            stderr.isNotBlank(),
        )
        return this
    }

    fun parseJsonRoot(): JsonObject =
        CliRunnerHarness.lenientJson.parseToJsonElement(stdout.trim()).jsonObject
}

/**
 * Pure black-box subprocess harness invoking `:cli-runner`'s installed distribution binary
 * (`/home/antigravity/cli-runner/build/install/cli-runner/bin/cli-runner`) via OS `ProcessBuilder`.
 */
object CliRunnerHarness {

    val lenientJson = Json { ignoreUnknownKeys = true }

    private val repoRoot: File =
        File(System.getProperty("wifiradar.projectRoot") ?: "/home/antigravity")

    private val cliBinary: File
        get() = File(
            System.getProperty("wifiradar.cliBinary")
                ?: File(repoRoot, "cli-runner/build/install/cli-runner/bin/cli-runner").absolutePath,
        )

    private val tmpDir: File = File("/home/antigravity/.tmp").apply { mkdirs() }

    fun executeRaw(
        args: List<String>,
        stdinPayload: String? = null,
        timeoutSeconds: Long = 20L,
    ): CliExecResult {
        check(cliBinary.exists() && cliBinary.canExecute()) {
            "Missing :cli-runner binary at ${cliBinary.absolutePath}. Run `./gradlew :cli-runner:installDist` first."
        }
        val command = mutableListOf(cliBinary.absolutePath).apply { addAll(args) }
        val pb = ProcessBuilder(command).directory(repoRoot)
        val env = pb.environment()
        env["JAVA_HOME"] = System.getenv("JAVA_HOME") ?: "/usr/lib/jvm/java-17-openjdk-amd64"
        env["TMPDIR"] = tmpDir.absolutePath
        env["CLI_RUNNER_OPTS"] =
            "-Xms32m -Xmx256m -XX:TieredStopAtLevel=1 -XX:+UseSerialGC " +
                "-Dfile.encoding=UTF-8 -Duser.language=en -Duser.country=US -Djava.io.tmpdir=${tmpDir.absolutePath}"
        env["JAVA_TOOL_OPTIONS"] =
            "-Djavax.net.ssl.trustStore=/home/antigravity/.android-sdk/cacerts.p12 " +
                "-Djavax.net.ssl.trustStorePassword=changeit"

        val t0 = System.nanoTime()
        val proc = pb.start()

        val stdinFuture = CompletableFuture.runAsync {
            proc.outputStream.use { out ->
                if (stdinPayload != null) {
                    out.write(stdinPayload.toByteArray(StandardCharsets.UTF_8))
                    out.flush()
                }
            }
        }
        val stdoutFuture = CompletableFuture.supplyAsync {
            proc.inputStream.bufferedReader(StandardCharsets.UTF_8).use { it.readText() }
        }
        val stderrFuture = CompletableFuture.supplyAsync {
            proc.errorStream.bufferedReader(StandardCharsets.UTF_8).use { it.readText() }
        }

        val finished = proc.waitFor(timeoutSeconds, TimeUnit.SECONDS)
        if (!finished) {
            proc.destroyForcibly()
            throw AssertionError("cli-runner timed out after ${timeoutSeconds}s for args=$args")
        }
        stdinFuture.get(2, TimeUnit.SECONDS)
        val stdout = stdoutFuture.get(5, TimeUnit.SECONDS)
        val stderr = stderrFuture.get(5, TimeUnit.SECONDS)
        val elapsedMs = (System.nanoTime() - t0) / 1_000_000L

        return CliExecResult(
            exitCode = proc.exitValue(),
            stdout = stdout,
            stderr = stderr,
            durationMs = elapsedMs,
        )
    }

    fun analyzeStdin(
        scenarioJson: String,
        format: String = "json",
        targetBssid: String? = null,
        env: String? = null,
        redactMacs: Boolean = false,
        includeDfs: Boolean = false,
        excludeOwnSsid: Boolean = false,
    ): CliExecResult {
        val args = buildAnalyzeArgs(
            inputArg = "-",
            format = format,
            targetBssid = targetBssid,
            env = env,
            redactMacs = redactMacs,
            includeDfs = includeDfs,
            excludeOwnSsid = excludeOwnSsid,
        )
        return executeRaw(args = args, stdinPayload = scenarioJson)
    }

    fun analyzeFile(
        inputFile: File,
        format: String = "json",
        targetBssid: String? = null,
        env: String? = null,
        redactMacs: Boolean = false,
        includeDfs: Boolean = false,
        excludeOwnSsid: Boolean = false,
    ): CliExecResult {
        val args = buildAnalyzeArgs(
            inputArg = inputFile.absolutePath,
            format = format,
            targetBssid = targetBssid,
            env = env,
            redactMacs = redactMacs,
            includeDfs = includeDfs,
            excludeOwnSsid = excludeOwnSsid,
        )
        return executeRaw(args = args, stdinPayload = null)
    }

    fun createTempScenarioFile(prefix: String, content: String): File {
        val file = File.createTempFile(prefix, ".json", tmpDir)
        file.writeText(content, StandardCharsets.UTF_8)
        file.deleteOnExit()
        return file
    }

    private fun buildAnalyzeArgs(
        inputArg: String,
        format: String,
        targetBssid: String?,
        env: String?,
        redactMacs: Boolean,
        includeDfs: Boolean,
        excludeOwnSsid: Boolean,
    ): List<String> = buildList {
        add("analyze")
        add("--input")
        add(inputArg)
        add("--format")
        add(format)
        if (targetBssid != null) {
            add("--target-bssid")
            add(targetBssid)
        }
        if (env != null) {
            add("--env")
            add(env)
        }
        if (redactMacs) add("--redact-macs")
        if (includeDfs) add("--include-dfs")
        if (excludeOwnSsid) add("--exclude-own-ssid")
    }
}
