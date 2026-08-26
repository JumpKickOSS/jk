# Directory-aware JAVA_HOME / PATH (hook-env). PATH ensure + completions wrap this in
# `jk activate zsh` full output; the rc block is: eval "$("$HOME/…/jk" activate zsh)"
export __JK_EXE=__JK_EXE__
export __JK_SHELL=zsh

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
