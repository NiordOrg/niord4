# Pack releases

The record of every language pack this repository publishes from `fe/packs/<lang>/` with
`.github/workflows/pack.yml`, as `ghcr.io/<owner>/niord-lang-<lang>:<tag>`, and of every withdrawal.
Each publish and each withdrawal writes its line into the run's summary: add it here in the same
change that follows the run. A consumer compares its first pin of a pack with the digest recorded
here before it commits the pin (`node setup.mjs --update-pins` writes it). Withdrawing a tag is
pack.yml's dispatch with the action `withdraw`, the tag and a one-line reason; the pack's withdrawn
marker (`:withdrawn`) is what consumers read, this file is the human record.

## Published

| Pack | Tag | Digest | Date |
| ---- | --- | ------ | ---- |

## Withdrawn

| Pack | Tag | Digest | Date | Reason |
| ---- | --- | ------ | ---- | ------ |
