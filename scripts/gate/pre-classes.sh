#!/usr/bin/env bash
# Print the gate's pre-run plan from a class list (default scripts/gate/pre-classes.txt): one line
# per module, `<module> <Class>,<Class>…`, the modules in the order they first appear. Blank lines
# and `#` comments are skipped; a line that is not `<module> <class>` is refused with its number.
set -euo pipefail

LIST="${1:-$(cd "$(dirname "$0")" && pwd)/pre-classes.txt}"

awk '
  { sub(/#.*/, "") }
  NF == 0 { next }
  NF != 2 { printf "pre-classes: %s:%d: want `<module> <class>`, got: %s\n", FILENAME, FNR, $0 > "/dev/stderr"; bad = 1; next }
  !($1 in classes) { order[++n] = $1; classes[$1] = $2; next }
  { classes[$1] = classes[$1] "," $2 }
  END {
    if (bad) exit 1
    for (i = 1; i <= n; i++) print order[i], classes[order[i]]
  }
' "$LIST"
