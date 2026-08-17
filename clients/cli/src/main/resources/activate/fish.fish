# Directory-aware JAVA_HOME / PATH (hook-env). PATH ensure + completions wrap this in
# `jk activate fish` full output; the rc block is: "$HOME/…/jk" activate fish | source
set -gx __JK_EXE __JK_EXE__
set -gx __JK_SHELL fish

if not set -q __JK_ORIG_PATH
    set -gx __JK_ORIG_PATH $PATH
end

function __jk_env_eval --on-event fish_prompt --description 'jk: refresh env'
    command $__JK_EXE hook-env -s fish | source
end

function __jk_cd_hook --on-variable PWD --description 'jk: refresh env on cd'
    command $__JK_EXE hook-env -s fish | source
end

__jk_env_eval
