# shellcheck shell=bash
# Directory-aware JAVA_HOME / PATH (hook-env). PATH ensure + completions wrap this in
# `jk activate bash` full output; the rc block is: eval "$("$HOME/…/jk" activate bash)"
export __JK_EXE=__JK_EXE__
export __JK_SHELL=bash

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
