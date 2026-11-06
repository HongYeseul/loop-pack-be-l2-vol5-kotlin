package com.loopers.utils

import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

/** 테스트가 띄운 앱 jar 프로세스, 로그는 logDirectory 에 이름별로 남김 */
class AppProcesses(private val logDirectory: Path, private val commonOptions: List<String>) : AutoCloseable {
    private val processes = linkedMapOf<String, Process>()

    fun start(name: String, jar: String, vararg options: String) {
        Files.createDirectories(logDirectory)
        val command = listOf(Path.of(System.getProperty("java.home"), "bin", "java").toString(), "-Xmx384m", "-jar", jar) +
            commonOptions + options
        processes[name] = ProcessBuilder(command).redirectErrorStream(true).redirectOutput(log(name).toFile()).start()
    }

    /** 실행하고 끝나는 jar (batch 등), 종료 코드를 돌려줌 */
    fun run(name: String, jar: String, vararg options: String): Int {
        start(name, jar, *options)
        val process = processes.getValue(name)
        check(process.waitFor(120, TimeUnit.SECONDS)) { "$name 이 끝나지 않음: ${Files.readString(log(name)).takeLast(8_000)}" }
        processes.remove(name)
        return process.exitValue()
    }

    fun assertAlive() {
        processes.forEach { (name, process) ->
            check(process.isAlive) { "$name 프로세스 종료: ${Files.readString(log(name)).takeLast(8_000)}" }
        }
    }

    private fun log(name: String): Path = logDirectory.resolve("$name.log")

    override fun close() {
        processes.values.reversed().forEach { process ->
            process.destroy()
            if (!process.waitFor(10, TimeUnit.SECONDS)) process.destroyForcibly()
        }
    }
}
