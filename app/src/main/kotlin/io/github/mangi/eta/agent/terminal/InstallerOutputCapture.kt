package io.github.mangi.eta.agent.terminal

/** Keep the end of long installs, where package managers report the actual failure. */
internal class InstallerOutputCapture(private val limit: Int = 64 * 1024) {
    private val tail = StringBuilder()
    private val line = StringBuilder()

    @Synchronized fun append(chars: CharArray, count: Int, onLine: (String) -> Unit) {
        tail.append(chars, 0, count)
        if (tail.length > limit) tail.delete(0, tail.length - limit)
        repeat(count) { index ->
            val char = chars[index]
            if (char == '\n' || char == '\r') {
                if (line.isNotEmpty()) onLine(line.toString())
                line.setLength(0)
            } else {
                line.append(char)
                if (line.length > 2048) line.delete(0, line.length - 2048)
            }
        }
    }

    @Synchronized fun finish(onLine: (String) -> Unit) {
        if (line.isNotEmpty()) onLine(line.toString())
        line.setLength(0)
    }

    @Synchronized fun text(): String = tail.toString().trimEnd()
}
