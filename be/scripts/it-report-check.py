#!/usr/bin/env python3
"""Assert that the integration tests really ran, instead of silently skipping.

`ITTest.requireDocker()` guards the whole integration suite with a JUnit *assumption*, so when
Testcontainers cannot reach a Docker daemon every `*ITTest` class is **skipped** rather than
failed -- and `mvn verify` still reports BUILD SUCCESS. A green run can therefore mean "all
integration tests passed" or "no integration test ran at all", and nothing in the Maven output
distinguishes the two.

This script closes that gap by reading the Failsafe XML reports, which do record the difference,
and is the single shared implementation used by both `be/test-it` (locally) and the backend CI
workflow -- so the local loop and CI can never disagree about what counts as a real IT run.

It fails when:

  * no Failsafe report was produced at all              (Failsafe never ran)
  * every reported test was skipped, i.e. executed == 0 (the Docker trap, whole-suite form)
  * a test was skipped for a Docker/Testcontainers      (the Docker trap, partial form: the daemon
    reason                                               went away part-way through the run)
  * fewer than --min tests executed                     (a full-suite floor; a narrowed run such
                                                         as -Dit.test=X passes --min 1 instead)

Skips for any *other* reason are reported but do not fail: a suite may hold intentionally-inactive
tests (an `@EnabledIfSystemProperty` gate that only a dedicated script activates, as the one-time
Liquibase relocation gate was until 2026-10-06). Those are visible in the output so a *new* one
cannot pass unnoticed.

Test *failures* are deliberately not turned into an error here: Failsafe's `verify` goal already
fails the build for those, and double-reporting them would only obscure this script's one job.
"""

import argparse
import glob
import os
import re
import sys
import xml.etree.ElementTree as ET

# ITTest.requireDocker()'s assumption message, and anything else naming the container runtime. A
# skip mentioning either is the trap this script exists to catch, whatever the wording.
DOCKER_SKIP = re.compile(r"docker|testcontainers", re.IGNORECASE)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument(
        "--min",
        type=int,
        default=1,
        metavar="N",
        help="minimum number of integration tests that must have executed (default: 1)",
    )
    parser.add_argument(
        "--root",
        default=".",
        metavar="DIR",
        help="directory to search for */target/failsafe-reports (default: the current directory)",
    )
    parser.add_argument(
        "--stats-file",
        default=None,
        metavar="PATH",
        help="write a one-line machine-readable summary here (files tests executed skipped "
             "failures errors), for a CI job summary to pick up",
    )
    args = parser.parse_args()

    pattern = os.path.join(args.root, "**", "target", "failsafe-reports", "TEST-*.xml")
    files = sorted(glob.glob(pattern, recursive=True))

    tests = skipped = failures = errors = 0
    unreadable = []
    docker_skips = []   # (test, reason)
    other_skips = []    # (test, reason)

    for path in files:
        try:
            suite = ET.parse(path).getroot()
        except ET.ParseError as exc:
            # A half-written report means the JVM died mid-suite: worth reporting, not worth
            # crashing the check that is supposed to explain what happened.
            unreadable.append(f"{path}: {exc}")
            continue
        tests += int(suite.get("tests", 0))
        skipped += int(suite.get("skipped", 0))
        failures += int(suite.get("failures", 0))
        errors += int(suite.get("errors", 0))

        for case in suite.iter("testcase"):
            for skip in case.iter("skipped"):
                name = f"{case.get('classname', '?')}.{case.get('name', '?')}"
                reason = (skip.get("message") or (skip.text or "").strip() or "no reason given")
                bucket = docker_skips if DOCKER_SKIP.search(reason) else other_skips
                bucket.append((name, reason))

    executed = tests - skipped

    print(f"Failsafe report files: {len(files)}")
    print(
        f"IT totals -> tests={tests} executed={executed} skipped={skipped} "
        f"failures={failures} errors={errors}"
    )
    for problem in unreadable:
        print(f"  ! unreadable report {problem}")
    for name, reason in other_skips:
        print(f"  - skipped (not Docker): {name}\n      {reason}")

    if args.stats_file:
        os.makedirs(os.path.dirname(os.path.abspath(args.stats_file)), exist_ok=True)
        with open(args.stats_file, "w") as out:
            out.write(f"{len(files)} {tests} {executed} {skipped} {failures} {errors}\n")

    problems = []
    if not files:
        problems.append(
            f"No Failsafe reports under {pattern} -- the integration tests never ran. "
            "Did the build stop before the integration-test phase, or was -DskipITs set?"
        )
    elif executed == 0:
        # Both shapes the trap produces: Failsafe either records the classes and marks every test
        # skipped, or -- when the assumption aborts before any test is collected -- writes reports
        # with no tests in them at all and logs "No tests to run".
        detail = (
            f"{tests} integration tests were reported and every one was skipped"
            if tests else
            "the Failsafe reports contain no tests at all"
        )
        problems.append(
            f"0 integration tests executed -- {detail}. This is the Docker silent-skip trap: "
            "Testcontainers could not reach a Docker daemon, ITTest.requireDocker() gave up on "
            "the suite, and Maven still said BUILD SUCCESS. Run `./dev doctor` to see which "
            "Docker endpoint is missing."
        )
    else:
        if docker_skips:
            detail = "; ".join(f"{name} ({reason})" for name, reason in docker_skips[:5])
            more = f" (+{len(docker_skips) - 5} more)" if len(docker_skips) > 5 else ""
            problems.append(
                f"{len(docker_skips)} integration tests were skipped for a Docker reason: "
                f"{detail}{more}. Testcontainers lost the daemon part-way through the run. "
                "Run `./dev doctor`."
            )
        if executed < args.min:
            problems.append(
                f"Only {executed} integration tests executed, expected at least {args.min}. "
                "Either the suite was narrowed unintentionally, or it stopped early."
            )

    if problems:
        # stdout is block-buffered when piped (CI tees this); flush so the totals above still read
        # as context for the errors below rather than arriving after them.
        sys.stdout.flush()
        for problem in problems:
            # ::error:: renders as an annotation on GitHub Actions and is harmless locally.
            prefix = "::error::" if os.environ.get("GITHUB_ACTIONS") == "true" else "ERROR: "
            print(f"{prefix}{problem}", file=sys.stderr)
        return 1

    tail = f", {len(other_skips)} intentionally skipped" if other_skips else ""
    print(f"OK: {executed} integration tests executed{tail}; none skipped for a Docker reason.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
