# shellcheck shell=bash
# `jk activate bash` — directory-aware JAVA_HOME / PATH (hook-env).
# Wired from the installer rc block: eval "$(command jk activate bash)"
# Real `jk` / `jkx` live on PATH (platform bin); this file does not wrap them.
export __JK_EXE=__JK_EXE__
export __JK_SHELL=bash

if [ -z "${__JK_ORIG_PATH:-}" ]; then
    export __JK_ORIG_PATH="$PATH"
fi

_jk_hook() {
    local prev_status=$?
    eval "$(command "$__JK_EXE" hook-env -s bash)"
    return $prev_status
}

# Bash has no chpwd; PROMPT_COMMAND is close enough for cd tracking.
if [[ ";${PROMPT_COMMAND:-};" != *";_jk_hook;"* ]]; then
    if [[ "$(declare -p PROMPT_COMMAND 2>/dev/null)" == "declare -a"* ]]; then
        PROMPT_COMMAND=("_jk_hook" "${PROMPT_COMMAND[@]}")
    else
        PROMPT_COMMAND="_jk_hook${PROMPT_COMMAND:+;$PROMPT_COMMAND}"
    fi
fi

_jk_hook
