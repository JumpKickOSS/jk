# `jk deactivate` for pwsh: tear down hooks. `hook-env --clear` owns the env restore —
# JAVA_HOME / GRAALVM_HOME / PATH come back from __JK_DIFF; never remove them here.
if ($env:__JK_EXE) {
    $output = $null
    try {
        $output = & $env:__JK_EXE hook-env -s __JK_SHELL__ --clear | Out-String
    } catch {
        # missing/broken binary — handled below
    }
    if ($null -ne $output -and $LASTEXITCODE -eq 0) {
        if ($output -and $output.Trim()) {
            $output | Invoke-Expression
        }
        Remove-Item -ErrorAction SilentlyContinue -Path Env:/__JK_DIFF
        Remove-Item -ErrorAction SilentlyContinue -Path Env:/__JK_EXE
    } else {
        # Keep __JK_EXE / __JK_DIFF: the only state a repaired jk can still use
        # to strip the toolchain bins and restore the original homes.
        Write-Warning "jk: deactivate: '$env:__JK_EXE hook-env --clear' failed; environment left as-is (repair jk, then rerun)"
    }
} else {
    Remove-Item -ErrorAction SilentlyContinue -Path Env:/__JK_DIFF
}
Remove-Item -ErrorAction SilentlyContinue -Path Env:/__JK_SHELL
Remove-Item -ErrorAction SilentlyContinue -Path Env:/__JK_ORIG_PATH
Remove-Item -ErrorAction SilentlyContinue function:_jk_hook
Remove-Item -ErrorAction SilentlyContinue function:jk
