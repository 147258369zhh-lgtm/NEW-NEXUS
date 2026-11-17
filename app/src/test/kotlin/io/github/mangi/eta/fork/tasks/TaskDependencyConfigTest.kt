package io.github.mangi.eta.fork.tasks

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class TaskDependencyConfigTest {
    @Test fun allAndAny_configsRoundTripWithTaskIds() {
        listOf(TaskDependencyConfig.Mode.ALL, TaskDependencyConfig.Mode.ANY).forEach { mode ->
            val config = TaskDependencyConfig(listOf("task-a", "task-b"), mode)
            assertEquals(config, TaskDependencyConfig.parse(config.encode()))
        }
    }

    @Test fun duplicateEmptyAndOversizedConfigsAreRejected() {
        assertThrows(IllegalArgumentException::class.java) { TaskDependencyConfig(listOf("a", "a"), TaskDependencyConfig.Mode.ALL) }
        assertThrows(IllegalArgumentException::class.java) { TaskDependencyConfig(emptyList(), TaskDependencyConfig.Mode.ANY) }
        assertThrows(IllegalArgumentException::class.java) { TaskDependencyConfig.parse("{" + " ".repeat(4096) + "}") }
        assertThrows(IllegalArgumentException::class.java) { TaskDependencyConfig.parse("{\"tasks\":[\"a\"],\"mode\":\"FIRST\"}") }
    }

    @Test fun dependencyCyclesIncludeEverySource() {
        val config = TaskDependencyConfig(listOf("a", "b"), TaskDependencyConfig.Mode.ALL).encode()
        assertThrows(IllegalArgumentException::class.java) {
            AutomationTriggers.requireAcyclic(listOf(
                AutomationTriggerEntity("a-trigger", "a", AutomationTrigger.TASK_SUCCEEDED.name, "b"),
                AutomationTriggerEntity("b-trigger", "b", AutomationTrigger.TASK_SUCCEEDED.name, config),
            ))
        }
    }

    @Test fun allWaitsForBothAndConsumesOneCompletionFromEach() {
        val a = run("a-run", 10)
        val b = run("b-run", 20)
        assertEquals(null, TaskDependencyConfig.selectCompletions(listOf("a", "b"), mapOf("a" to a), TaskDependencyConfig.Mode.ALL))
        assertEquals(mapOf("a" to a, "b" to b), TaskDependencyConfig.selectCompletions(
            listOf("a", "b"), mapOf("a" to a, "b" to b), TaskDependencyConfig.Mode.ALL))
    }

    @Test fun anyConsumesOnlyTheEarliestNewCompletion() {
        val earlier = run("a-run", 10)
        val later = run("b-run", 20)
        assertEquals(mapOf("a" to earlier), TaskDependencyConfig.selectCompletions(
            listOf("a", "b"), mapOf("a" to earlier, "b" to later), TaskDependencyConfig.Mode.ANY))
    }

    private fun run(id: String, at: Long) = AgentTaskRunEntity(
        id = id, definitionId = "source", scheduleId = null, scheduledFor = at, attempt = 1,
        idempotencyKey = "run:$id", state = AgentTaskState.SUCCEEDED, createdAt = at, finishedAt = at, updatedAt = at,
    )
}
