@AGENTS.md

## Claude Code only

- **Process starts are slow on the Windows workstations** (endpoint protection: ~150 ms per process from PowerShell,
  ~400 ms from Git Bash). Read, search and list files with the Read, Grep and Glob tools rather than `cat`/`grep`/`awk`
  pipelines (0.3–2 s against 3–5 s a call), put many small queries into one script, and wait for long runs with
  `run_in_background` and its completion notice rather than `sleep` loops.
