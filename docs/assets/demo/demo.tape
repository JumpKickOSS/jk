# The homepage demo: change the code, rebuild, re-test, reach green, on spring-petclinic.
# Run docs/assets/demo/setup.sh first, then from the repository root:
#   vhs docs/assets/demo/demo.tape
# Writes hosting/public/demo.webm and demo.mp4; every timing in the video is jk's own.

Output hosting/public/demo.webm
Output hosting/public/demo.mp4

Set Shell zsh
Set FontFamily "JetBrains Mono"
Set FontSize 26
Set LineHeight 1.25
Set Width 1920
Set Height 1080
Set Padding 56
Set Framerate 30
Set TypingSpeed 38ms
Set CursorBlink false
Set WindowBar ""
Set Theme { "name": "JkDark", "background": "#090c11", "foreground": "#cfd8dc", "cursor": "#00f0ff", "selection": "#2a3742", "black": "#161d25", "red": "#ff3366", "green": "#00ff87", "yellow": "#ffb800", "blue": "#3d9bff", "magenta": "#c04dff", "cyan": "#00f0ff", "white": "#cfd8dc", "brightBlack": "#5c6d78", "brightRed": "#ff6688", "brightGreen": "#5dffb0", "brightYellow": "#ffd060", "brightBlue": "#5ab0ff", "brightMagenta": "#d2a8ff", "brightCyan": "#7ff9ff", "brightWhite": "#eceff1" }

Hide
Type "source docs/assets/demo/demo.zshrc && clear"
Enter
Sleep 1s
Show

Sleep 600ms
Type "# Make pet types case-insensitive: a one-line change"
Enter
Sleep 400ms
Type "git diff --color | tail -n +5"
Enter
Sleep 3.5s

Type "jk test"
Enter
Wait@40s /❯\s*$/
Sleep 3.5s

Type "# Red at file:line. Fix it."
Enter
Sleep 300ms
Type "vim **/PetTypeFormatter.java"
Enter
Sleep 1s
Type "/toLowerCase"
Enter
Sleep 900ms
Type ":s/text.equals(type.getName().toLowerCase())/type.getName().equalsIgnoreCase(text)/"
Sleep 400ms
Enter
Sleep 1.6s
Type ":wq"
Enter
Sleep 500ms

Type "jk test -S PetTypeFormatterTests"
Enter
Wait@30s /❯\s*$/
Sleep 2s

Type "jk test"
Enter
Wait@40s /❯\s*$/
Sleep 2.5s

Type "# Green. Ship it, and switch back to main."
Enter
Sleep 300ms
Type "git commit -qam 'Case-insensitive pet types' && git switch -q main"
Enter
Sleep 800ms
Type "jk test"
Enter
Wait@30s /❯\s*$/
Sleep 4s
