#!/usr/bin/env bash
# Fails unless the country build group ran in the last ./mvnw verify.
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")/.."
summary=target/failsafe-reports/failsafe-summary-niord-country-build.xml
[[ -f "$summary" ]] || { echo "the country build group's integration tests did not run"; exit 1; }
completed=$(sed -n 's|.*<completed>\([0-9]*\)</completed>.*|\1|p' "$summary" | head -1)
skipped=$(sed -n 's|.*<skipped>\([0-9]*\)</skipped>.*|\1|p' "$summary" | head -1)
[[ "${completed:-0}" -gt 0 ]] || { echo "the country build group ran no integration test"; exit 1; }
[[ "${skipped:-0}" -lt "$completed" ]] ||
  { echo "every test of the group skipped: is Docker running?"; exit 1; }
compgen -G 'target/surefire-reports/TEST-*CountryContractGuardTest.xml' >/dev/null ||
  { echo "CountryContractGuardTest did not run"; exit 1; }
echo "the country build group ran: $completed integration tests, $skipped skipped"
