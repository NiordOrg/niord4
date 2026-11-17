# Session prompt: working in a parallel worktree

> **This applies only when the user explicitly tells the session to follow this file.** Nothing loads it
> automatically and no other document makes it a rule. Without that instruction, ignore it — including when
> the session happens to run in a worktree.

You are one of several Claude sessions working on this repository at the same time. Each session has its own
git worktree (one branch, one folder) and its own slot. The other sessions, the main checkout and the shared
backend belong to someone else.

## Find out where you are

```bash
git rev-parse --show-toplevel      # your worktree — work only inside it
git branch --show-current          # your branch — commit only to it
cat .local-run/slot                # your slot N
```

- Your frontend dev server is `http://localhost:410N` (4100 + slot). Use that URL for every browser check,
  Playwright MCP navigation and `E2E_BASE_URL=http://localhost:410N npx playwright test`.
- If `.local-run/slot` is missing, stop and ask; do not pick a port yourself.

## Running the application

- Frontend-only work: `./start-local.sh --fe-only` from the worktree root. It serves your branch on your
  port and proxies `/rest` to the shared backend on 8888, which runs from the main checkout. If nothing
  listens on 8888, say so — do not start a backend in the main checkout.
- Work that changes `be/`: `./start-local.sh` (no `--fe-only`) starts a backend of your own on
  8888 + slot, with its own Maven install target and message index, on the shared database.
- A branch that adds a Liquibase changeset: add `--own-db`, so the migration runs on a private copy of the
  database and not under the other sessions.
- Start long-running servers in the background with a log file, as usual.

## Leave the others alone

- Stop only processes you started, by their PID. Never kill `java.exe` or `node.exe` by name, never free a
  port that is not yours, never run `docker compose down` or `stop`.
- Ports 4100 and 8888 and every other slot's ports are not yours, even when something on them looks stuck.
- Do not edit, check out, stash or commit in any other worktree or in the main checkout. Do not switch your
  worktree to another branch.
- The database and the files under `~/.niord-local/niord` are shared: do not reseed, wipe or bulk-rewrite data
  without asking. Data you create for a test should be recognisable as yours.
- Never link or copy `fe/node_modules` from another checkout; run `npm ci` in your own `fe/`.

## Finishing

Commit on your branch only, push it and open the pull request when asked to. Leave removing the worktree
(`git worktree remove`) to the user.
