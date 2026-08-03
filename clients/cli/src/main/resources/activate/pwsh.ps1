# `jk activate pwsh` — directory-aware JAVA_HOME / PATH (hook-env).
# Wired from the installer profile block via Invoke-Expression.
# Real `jk` / `jkx` live on PATH (platform bin); this file does not wrap them.
$env:__JK_EXE = '__JK_EXE__'
$env:__JK_SHELL = 'pwsh'

if (-not (Test-Path -Path Env:/__JK_ORIG_PATH)) {
    $env:__JK_ORIG_PATH = $env:PATH
}

function global:_jk_hook {
    if ($env:__JK_SHELL -eq 'pwsh') {
        $output = & $env:__JK_EXE hook-env -s pwsh | Out-String
        if ($output -and $output.Trim()) {
            $output | Invoke-Expression
        }
    }
}

# Chpwd: requires PowerShell 7+ (LocationChangedAction).
if ($PSVersionTable.PSVersion.Major -ge 7 -and -not $__jk_pwsh_chpwd_installed) {
    $Global:__jk_pwsh_chpwd_installed = $true
    $__jk_chpwd = [EventHandler[System.Management.Automation.LocationChangedEventArgs]] {
        param($source, $args)
        end { _jk_hook }
    }
    $existing = $ExecutionContext.SessionState.InvokeCommand.LocationChangedAction
    if ($existing) {
        $ExecutionContext.SessionState.InvokeCommand.LocationChangedAction = [Delegate]::Combine($existing, $__jk_chpwd)
    } else {
        $ExecutionContext.SessionState.InvokeCommand.LocationChangedAction = $__jk_chpwd
    }
}

# Prompt hook: runs each prompt so cd from a subshell still updates env.
if (-not $__jk_pwsh_prompt_installed) {
    $Global:__jk_pwsh_prompt_installed = $true
    $Global:__jk_previous_prompt = $function:prompt
    function global:prompt {
        _jk_hook
        & $__jk_previous_prompt
    }
}

_jk_hook
