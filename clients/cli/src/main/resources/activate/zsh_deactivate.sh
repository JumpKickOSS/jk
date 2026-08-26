# `jk deactivate` for zsh: tear down hooks. `hook-env --clear` owns the env restore —
# JAVA_HOME / GRAALVM_HOME / PATH come back from __JK_DIFF; never unset them here.
autoload -Uz add-zsh-hook
add-zsh-hook -d precmd _jk_hook_precmd 2>/dev/null
add-zsh-hook -d chpwd _jk_hook_chpwd 2>/dev/null
if [ -n "${__JK_EXE:-}" ]; then
    if __jk_clear="$(command "$__JK_EXE" hook-env -s zsh --clear)"; then
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
(( $+functions[_jk_hook_precmd] )) && unset -f _jk_hook_precmd
(( $+functions[_jk_hook_chpwd] )) && unset -f _jk_hook_chpwd
(( $+functions[jk] )) && unset -f jk
