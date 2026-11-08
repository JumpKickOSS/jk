# The shell the demo tape types into: the demo checkout, a short prompt with the git branch, color
# diffs without a pager, and vim with a plain dark profile instead of whatever the recorder uses.
_demo_rc="${0:A:h}"
export JK_STATE_DIR="${JK_DEMO_STATE:-$HOME/src/scratch/demo-state}"
export JK_HTTP_PORT="${JK_DEMO_PORT:-8921}"
cd "${JK_DEMO_DIR:-$HOME/src/scratch/demo-petclinic}" || return
autoload -Uz vcs_info
precmd() { vcs_info; }
zstyle ':vcs_info:git:*' formats '%b'
setopt PROMPT_SUBST
PROMPT='%F{#00f0ff}petclinic%f %F{#c04dff}${vcs_info_msg_0_}%f %F{#5c6d78}❯%f '
RPROMPT=''
export GIT_PAGER=cat
alias vim="vim -Nu $_demo_rc/demo.vimrc"
setopt INTERACTIVE_COMMENTS
# A recording made from inside another Claude Code session inherits its CLAUDE* variables; the
# nested session then runs as a child (no transcript) and its stdio MCP servers fail to connect.
unset $(env | grep -oE '^CLAUDE[A-Z_]*')
# The agent demo's Claude Code: edits accepted without a prompt, git allowed beside the jk tools
# `jk agents` already permits, so no permission dialog stalls the take.
alias claude='claude --permission-mode acceptEdits --allowedTools "Bash(git:*)"'
