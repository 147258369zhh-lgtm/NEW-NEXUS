# Nexus Repository Instructions

## Git

- Do not run Git operations that change repository state without explicit user authorization. This includes init, remote changes, fetch, pull, branch, merge, rebase, cherry-pick, commit, push, tag, reset, stash and history rewrite.
- Read-only Git inspection is allowed when it is needed to understand the current workspace.
- Never force-push or bypass protected branches.

## Product identity

- The product name is Nexus.
- The Android applicationId is `io.github.zhh_lgtm.nexus` unless the user explicitly changes it.
- Nexus must remain installable beside legacy Eta and must not overwrite Eta.
- Legacy Eta data may only move through an exported, validated, preferably encrypted migration package imported through Android SAF. Never read another application's private data directory as an implicit migration shortcut.

## Architecture gates

- `kernel-api` must remain free of Android, Compose, Room, Miuix and app-module imports.
- UI must not write directly through DAOs, Runtime singletons or host-private databases.
- Third-party plugins must not execute in the UI process.
- Tool exposure and tool execution must use the same permission and effect policy.
- Root and high-risk A2A operations require local user confirmation; an Agent review cannot approve them.

## Builds and releases

- Build or package only when the requested work requires it.
- Do not commit signing secrets or plaintext credentials.
- For ordinary changes, run the smallest relevant compile/test pass once after the change is complete. Add broader device testing only when the acceptance criteria require it.
- Do not claim a feature is complete from compilation alone. State the evidence level reached.

## Protected local references

- Treat the legacy Eta checkout and unrelated local projects as read-only references unless the user explicitly asks to modify them.

