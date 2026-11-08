# The left pane of the agent demo: Claude Code fixing the bug through jk's MCP tools.
# Not run by hand: docs/assets/demo/record-agent.sh records it beside the jk web dashboard.

Output target/demo/agent-terminal.mp4

Set Shell zsh
Set FontFamily "JetBrains Mono"
Set FontSize 20
Set LineHeight 1.2
Set Width 1040
Set Height 1080
Set Padding 32
Set Framerate 30
Set TypingSpeed 30ms
Set CursorBlink false
Set WindowBar ""
Set Theme { "name": "JkDark", "background": "#090c11", "foreground": "#cfd8dc", "cursor": "#00f0ff", "selection": "#2a3742", "black": "#161d25", "red": "#ff3366", "green": "#00ff87", "yellow": "#ffb800", "blue": "#3d9bff", "magenta": "#c04dff", "cyan": "#00f0ff", "white": "#cfd8dc", "brightBlack": "#5c6d78", "brightRed": "#ff6688", "brightGreen": "#5dffb0", "brightYellow": "#ffd060", "brightBlue": "#5ab0ff", "brightMagenta": "#d2a8ff", "brightCyan": "#7ff9ff", "brightWhite": "#eceff1" }

# Off camera: the demo shell, and one throwaway launch so a first-run trust dialog is answered.
Hide
Type "source docs/assets/demo/demo.zshrc && clear"
Enter
Sleep 1s
Type "claude"
Enter
Sleep 6s
# The dialog defaults to "No, exit": move to "Yes, I trust this folder". Once trusted, these two
# keys land in an empty prompt and do nothing.
Down
Sleep 300ms
Enter
Sleep 3s
Type "/exit"
Enter
Sleep 3s
Type "clear && date +%s.%N > $DEMO_OUT/terminal-start"
Enter
Sleep 200ms
Show

Sleep 500ms
Type "claude"
Enter
Sleep 4s
Type "jk test is red on this branch. Find the bug, fix it so pet types match case-insensitively, and get the tests green. Use jk's MCP tools."
Sleep 600ms
Enter
Sleep 170s
