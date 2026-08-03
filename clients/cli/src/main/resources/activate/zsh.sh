# `jk activate zsh` — directory-aware JAVA_HOME / PATH (hook-env).
# Wired from the installer rc block: eval "$(command jk activate zsh)"
# Real `jk` / `jkx` live on PATH (platform bin); this file does not wrap them.
export __JK_EXE=__JK_EXE__
export __JK_SHELL=zsh

if [ -z "${__JK_ORIG_PATH:-}" ]; then
    export __JK_ORIG_PATH="$PATH"
fi

autoload -Uz add-zsh-hook

_jk_hook_precmd() {
    eval "$(command "$__JK_EXE" hook-env -s zsh)"
}

_jk_hook_chpwd() {
    eval "$(command "$__JK_EXE" hook-env -s zsh)"
}

add-zsh-hook precmd _jk_hook_precmd
add-zsh-hook chpwd _jk_hook_chpwd

_jk_hook_precmd
