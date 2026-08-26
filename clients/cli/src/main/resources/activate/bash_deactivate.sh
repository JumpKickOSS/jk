# `jk deactivate` for bash: tear down hooks. `hook-env --clear` owns the env restore —
# JAVA_HOME / GRAALVM_HOME / PATH come back from __JK_DIFF; never unset them here.
if [ -n "${__JK_EXE:-}" ]; then
    if __jk_clear="$(command "$__JK_EXE" hook-env -s bash --clear)"; then
        eval "$__jk_clear"
        unset __JK_DIFF
        unset __JK_EXE
    else
        # Keep __JK_EXE / __JK_DIFF: the only state a repaired jk can still use
        # to strip the toolchain bins and restore the original homes.
        echo "jk: deactivate: '$__JK_EXE hook-env --clear' failed; environment left as-is (repair jk, then rerun)" >&2
    fi
    unset __jk_clear
else
    unset __JK_DIFF
fi
unset __JK_SHELL
unset __JK_ORIG_PATH
# Drop the prompt hook entry if present.
if [[ "$(declare -p PROMPT_COMMAND 2>/dev/null)" == "declare -a"* ]]; then
    PROMPT_COMMAND=("${PROMPT_COMMAND[@]/_jk_hook/}")
else
    PROMPT_COMMAND="${PROMPT_COMMAND//_jk_hook;/}"
    PROMPT_COMMAND="${PROMPT_COMMAND//;_jk_hook/}"
    PROMPT_COMMAND="${PROMPT_COMMAND//_jk_hook/}"
fi
unset -f _jk_hook 2>/dev/null
unset -f jk 2>/dev/null
