package io.github.mangi.eta.agent.terminal

import org.junit.Assume.assumeFalse

/** Shell, PRoot, permissions and process-tree suites require a POSIX host. */
internal fun assumePosixTestHost() {
    assumeFalse(
        "This integration suite requires a POSIX host and is covered by the Linux test lane",
        System.getProperty("os.name").orEmpty().startsWith("Windows"),
    )
}
