---
name: self-improving-agent
description: Built-in self-improvement loop. Use to record non-trivial failures, user corrections, and reusable best practices into structured learnings.
---

# Self Improving Agent

This built-in skill is fixed-injected for agent runs.

Use it to maintain a lightweight learning loop without interrupting the user's main task.

The runtime may auto-read this skill after a tool failure and auto-record the failure into `data/ERRORS.md`.

## When To Record

Record after the immediate task is safe or complete when any of these happens:

1. a non-trivial command, tool, or device action fails
2. the user corrects your understanding, path, rule, or project assumption
3. you discover an outdated runtime/project convention
4. you find a reusable workaround or best practice that will likely save future retries
5. the same mistake repeats in the same task or across tasks

Do not record ordinary chat, tiny one-off slips, or anything the user asked not to save.

## Default Storage

- durable, auditable candidates: Eta learning database, accessed through `learning_propose`
- confirmed, reusable rules: Eta learning database, accessed through `learning_search`
- the runtime failure hook records redacted failure evidence as a pending candidate; it never promotes rules automatically

## Logging Workflow

1. Finish or stabilize the current user-facing step first.
2. Before starting a task that resembles an earlier correction or failure, call `learning_search`.
3. After the immediate task is safe, call `learning_propose` for a corrected rule or reusable procedure. Use `failure_fix` for a verified recovery, `preference` for an explicit preference, `procedure` for a reusable workflow, and `fact` for stable project context.
4. Include a short, redacted evidence excerpt and a stable source reference. The runtime offers the tool only when learning is enabled.
5. A proposal remains pending until the user reviews and confirms it. You cannot approve, promote, roll back or alter permissions through the learning tool.

## Memory Promotion

Promote a lesson into a stable rule only when it is short, reusable, and broadly applicable.

Good candidates:

- a rule like "遇到 X 先检查 Y"
- a stable workspace convention
- a long-term user preference the user explicitly wants remembered

Prefer this order:

1. redacted failures are captured as pending evidence by the runtime hook
2. repeated evidence is deduplicated by content hash and source
3. stable rules are versioned only after user confirmation
4. users can review evidence and roll back a version from the learning history section

## Output Discipline

- keep summaries short and specific
- include the concrete command/tool/context that failed
- include the corrected rule, not only the symptom
- avoid logging secrets, tokens, and personal data
