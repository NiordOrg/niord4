#!/usr/bin/env bash
#
# Make a second checkout of this repository for one branch — a git worktree — so several branches can be
# worked on and run at the same time, each with its own dev server:
#
#   ./new-worktree.sh fix/actions-menu        the branch (created from main when it does not exist yet),
#                                             in ../niord-wt/fix-actions-menu, on the first free slot
#   ./new-worktree.sh fix/actions-menu 3      ... on slot 3
#   ./new-worktree.sh --list                  the worktrees and their slots
#
# The slot (1-9) is written to the worktree's .local-run/slot, where ./start-local.sh picks it up: the
# administration app on 4100 + slot, the public site on 4400 + slot, and — for a whole stack — backend on
# 8888 + slot. Slot 0 is this main checkout. In the new worktree:
#
#   ./start-local.sh --fe-only          frontend only, against the backend running from the main checkout
#   ./start-local.sh                    a backend of its own as well, for a branch that changes be/
#   ./start-local.sh --info             the public site (fe/ui-apps/info) instead of the administration app
#   ./start-local.sh --admin --info     both frontends, on 4100 + slot and 4400 + slot
#
# The worktree gets its own fe/node_modules (npm ci; skip with --no-install). Never link node_modules
# between worktrees: removing the worktree would delete the install it points at.
#
# Done with the branch:   git worktree remove ../niord-wt/<folder>   (the branch itself stays)
#
# Environment overrides:
#   NIORD_WORKTREE_ROOT   where the worktrees go (default: niord-wt next to this checkout — outside it, so
#                         the IDE and the repository-wide tools do not index a second copy of everything)
#   NIORD_WORKTREE_BASE   what a new branch starts from (default main)
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
WT_ROOT="${NIORD_WORKTREE_ROOT:-$(dirname "$ROOT")/niord-wt}"
BASE="${NIORD_WORKTREE_BASE:-main}"

die() { echo "!! $*" >&2; exit 1; }

# "<slot> <path>" for every worktree of this repository that has a slot.
slots() {
    local path
    git -C "$ROOT" worktree list --porcelain | sed -n 's/^worktree //p' | while IFS= read -r path; do
        if [[ -f "$path/.local-run/slot" ]]; then
            echo "$(tr -d '[:space:]' <"$path/.local-run/slot") $path"
        fi
    done
}

INSTALL=true
ARGS=()
for arg in "$@"; do
    case "$arg" in
        --no-install) INSTALL=false ;;
        --list)
            git -C "$ROOT" worktree list
            echo
            slots | sort -n | while read -r slot path; do
                echo "slot $slot  admin http://localhost:$((4100 + slot))  info http://localhost:$((4400 + slot))  $path"
            done
            exit 0 ;;
        -h|--help) sed -n '2,/^set -euo/{/^set -euo/d;s/^# \{0,1\}//;p}' "${BASH_SOURCE[0]}"; exit 0 ;;
        -*) die "Unknown argument: $arg (see --help)" ;;
        *) ARGS+=("$arg") ;;
    esac
done
(( ${#ARGS[@]} >= 1 && ${#ARGS[@]} <= 2 )) || die "Usage: $0 <branch> [slot]   (see --help)"

BRANCH="${ARGS[0]}"
SLOT="${ARGS[1]:-}"
git -C "$ROOT" check-ref-format --branch "$BRANCH" >/dev/null 2>&1 || die "'$BRANCH' is not a valid branch name."

TAKEN="$(slots | cut -d' ' -f1)"
if [[ -z "$SLOT" ]]; then
    for candidate in 1 2 3 4 5 6 7 8 9; do
        if ! grep -qx "$candidate" <<<"$TAKEN"; then SLOT="$candidate"; break; fi
    done
    [[ -n "$SLOT" ]] || die "Slots 1-9 are all taken — see $0 --list"
else
    [[ "$SLOT" =~ ^[1-9]$ ]] || die "The slot must be 1-9, got '$SLOT' (0 is the main checkout)."
    if grep -qx "$SLOT" <<<"$TAKEN"; then die "Slot $SLOT is taken — see $0 --list"; fi
fi

DIR="$WT_ROOT/${BRANCH//\//-}"
[[ -e "$DIR" ]] && die "$DIR already exists."
mkdir -p "$WT_ROOT"

if git -C "$ROOT" show-ref --verify --quiet "refs/heads/$BRANCH"; then
    echo ">> Checking out the existing branch $BRANCH in $DIR"
    git -C "$ROOT" worktree add "$DIR" "$BRANCH"
else
    echo ">> Creating branch $BRANCH from $BASE in $DIR"
    git -C "$ROOT" worktree add -b "$BRANCH" "$DIR" "$BASE"
fi

mkdir -p "$DIR/.local-run"
echo "$SLOT" >"$DIR/.local-run/slot"

if $INSTALL; then
    echo ">> Installing frontend dependencies (npm ci) ..."
    (cd "$DIR/fe" && npm ci)
fi

cat <<EOF

  Worktree ready
    Branch      $BRANCH
    Folder      $DIR
    Slot        $SLOT    administration http://localhost:$((4100 + SLOT)), public site http://localhost:$((4400 + SLOT)), own backend (if started) http://localhost:$((8888 + SLOT))/rest

  Next, in a new terminal:
    cd "$DIR"
    ./start-local.sh --fe-only          # or without --fe-only for a backend of its own
    ./start-local.sh --fe-only --info   # the public site instead of the administration app
    claude                              # and point the session at docs/parallel-session-prompt.md

EOF
