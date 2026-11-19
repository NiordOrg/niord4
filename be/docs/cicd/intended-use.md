# Niord CI/CD — Intended Use

**Audience:** developers, stakeholders, and anyone who wants to understand how changes to Niord travel from an idea to production — without reading the technical design.
**Companion document:** `design.md` holds the full technical design (repositories, pipelines, tooling); `implementation-plan.md` sequences the build-out. This document explains what it is *like to use*.

---

## The idea in one paragraph

Every code change, once merged, is built, fully tested and published automatically. Each country takes the new version through an **update request** that is never merged by itself: a person reviews and merges it. From there the country's **staging** environment follows: one button opens the merge request that moves its pins, and a person merges it (since 2026-11-19; until then the pins moved by hand). Whether staging should follow by itself, with no merge, is still open. Production is updated only when a human presses a button, and that button produces a reviewable summary of exactly what will change before anything happens. The human actions are: **merging the change**; per country, **the merge of the update**; and **approving a production release**. Everything in between is automation that leaves a written trail.

A few words used throughout:

| Term | Meaning |
|---|---|
| **Generic code** | The shared Niord codebase (backend + frontend) used by every country |
| **Country code** | The small Denmark- or Greenland-specific layer on top of the generic code |
| **Staging** | A permanent test installation of the full system, running the latest version its country has taken |
| **Promote** | Copying exactly what staging runs into production — "the button" |
| **Environment** | One complete, self-contained installation of the stack (app, database, login server) |

## What this replaces

| | Old way | New way |
|---|---|---|
| Fix in generic code | release the library → update DK → build DK → release DK → deploy — repeated per country | **merge once**; per country a merge of the update; then on DK *and* GL staging (about 40 minutes of machine time, without the waits) |
| Fix in DK code | build → release → deploy | **merge once**; on DK staging ~15 min later |
| Release to production | manual steps per component | **one button per country**, with generated release notes |
| Set up a test system | manual installation | **one small change request**; full system minutes later |

---

## Scenario 1 — the typical day: a small fix in the generic code

A developer fixes a small bug in the shared backend.

**What the developer does (this is the complete list):**

1. Makes the change and opens a pull request. Quick tests run automatically (~5 minutes).
2. Tests green, review done → **merge**.

Done. The developer can move on to other work.

**What each country's maintainer does:**

1. Reviews and merges the green update request.

Updates come minutes after each publish: the base product's publish run opens the update request (the Niord base bump app's) in each country's repository, and moves it to each newer version until it is merged. When the request is green there is nothing else to do: what the update asks of the country is on the request itself.

**What happens in between** (the times are machine time, without the wait for the merge):

| After merge | What happens |
|---|---|
| ~0 min | The full test suite runs — thousands of tests, including integration tests against a real database and login server, split across parallel machines so it stays fast |
| ~15 min | All green → version `4.0.127` is published. **A version only exists if it passed everything.** |
| minutes after the publish | the Niord base bump app opens a small "use 4.0.127" update request in each country's repository, or moves the open one |
| at once | The country's re-pin workflow records the version's pins on the request's branch, and the country's own test suite runs |
| ~13 min later | Both green → a person merges each request (never automerged), and new application images are built |
| ~7 min after the merges | The deployment system notices and updates both staging installations |
| **~40 min in all** | **The fix is live on DK and GL staging** |

**What people see:** the merge in each country; otherwise a notification when staging updates, and a notification if anything failed. Those two signals are the entire monitoring burden of routine work.

**If something goes wrong, it stops where it broke — broken versions never travel:**

- If the full test suite fails, the version is **never published**. Staging keeps running the previous version; nothing downstream is even attempted.
- If the generic change breaks something Denmark-specific, Denmark's tests reject the update: DK staging stays on the old version, Greenland proceeds, and the failed update *is* the bug report — visible, explained, waiting to be fixed with a follow-up change.

## Scenario 2 — a Denmark-only change

Merge in the Denmark repository → Denmark's tests → new image → DK staging updates. **~15–20 minutes, one manual step, Greenland completely untouched.** (Same for Greenland, mirrored.)

## Scenario 3 — a change that spans backend and frontend

Because the generic backend and frontend live in the same repository with one shared version number, a change like "new field in the API *and* the screen that shows it" is **one pull request, one merge**, and flows exactly like Scenario 1. It is structurally impossible for staging or production to run a frontend/backend combination that was never tested together.

## Scenario 4 — releasing to production

Whenever it suits — after one fix or a week's worth:

1. Press **Promote** and pick the country.
2. Thirty seconds later a change request appears: *"Promote DK to production: 4.0.121 → 4.0.127"*, listing **every change that will go out**, with links to each. The release notes write themselves.
3. An authorized person reads it and approves. **Production is updated in under a minute.**

Greenland is promoted the same way, independently — the two countries never block each other.

Notes for stakeholders:

- **What gets promoted is exactly what staging has been running** — the same tested, soaked combination. Nothing is rebuilt on the way to production.
- **Who may approve is a configured rule** (a protected approval step), not a convention.
- **Rolling back is one action:** undo the promotion request, and production returns to the previous version within a minute. Database changes are designed to tolerate this (they are always backward-compatible by one version — a standing team discipline).

## Scenario 5 — trying something risky in isolation

Need to test a big migration or an experiment without touching staging? Request a **temporary environment**: one small change ("create `test-lights` based on the template") produces a complete private installation — application, its own empty database and login server — in a few minutes, at its own web address; its creator fills the database with the base product's data in one command (`bootstrap.sh`). Optionally loaded with an anonymized copy of production data. Delete the request when done and the environment vanishes.

## Scenario 6 — emergency fix

Same flow as Scenario 1, with an optional **break-glass** shortcut that publishes after the quick tests only (the full suite still runs and reports); the countries' maintainers review and merge the update as soon as it arrives, minutes after the publish. Fix on staging in ~15 minutes, then the normal promote button. Even in an emergency, production is only ever changed through the reviewed button — there is no side door.

---

## What this gives stakeholders

- **Traceability.** "What is running in production?" has an exact answer at all times: it is written in one file, changed only by approved promotion requests. Every production change is a reviewed, linked, revertible record — an audit trail by construction, not by policy.
- **Safety in layers.** A change reaches production only after: quick tests → the full integration suite → the country's own suite, on an update its maintainers reviewed and merged → running on staging → a human reading the release summary. The test gates are automatic; the country's review and merge and the last gate are deliberately human.
- **No release overhead.** "Making a release" stopped being work. Versions publish themselves; release notes generate themselves; the ceremony left is the one that matters — a country's maintainers taking a version, and a person deciding production should change.
- **Country independence with shared quality.** DK and GL build on the same tested generic versions, each taking one when its maintainers review and merge it, yet deploy and release entirely independently; the promote checks that both run the same one (`design.md` §4, §7).
- **Openness.** The code — generic and country layers — is public open source; even the deployment *recipe* (the chart) is public. Only environment specifics (addresses, sizing, credentials) are private. Anyone can see how Niord is run.

## Common questions

**How do I know my change made it to staging?** A notification, or glance at the deployment dashboard — it shows each environment and the exact version it runs.

**Can a broken change reach production?** Not through the machinery: unpublished if tests fail, rejected by country tests if incompatible, visible on staging before any button exists to press. What remains is the promoted-but-unforeseen bug — which the one-action rollback exists for.

**Do we have to promote every change?** No. Staging takes each version its country takes; production moves only when someone decides. Batching a quiet week into one promotion is normal and fine.

**What does this cost to run?** The public repositories build for free. The rest is the Kubernetes cluster the system needs anyway, plus a small always-on helper (the deployment controller); the update requests are opened by the publish run itself.

**What's left to decide?** A short list — mostly facts to confirm with the hosting provider and the security review before the country repositories go public. See §11 of `design.md` and the phase gates in `implementation-plan.md`.
