# `jk deactivate` for fish: tear down hooks. `hook-env --clear` owns the env restore —
# JAVA_HOME / GRAALVM_HOME / PATH come back from __JK_DIFF; never erase them here.
functions --erase __jk_env_eval 2>/dev/null
functions --erase __jk_cd_hook 2>/dev/null
functions --erase jk 2>/dev/null
if set -q __JK_EXE
    set -l __jk_clear (command $__JK_EXE hook-env -s fish --clear)
    if test $status -eq 0
        printf '%s\n' $__jk_clear | source
        set -e __JK_DIFF
        set -e __JK_EXE
    else
        # Keep __JK_EXE / __JK_DIFF: the only state a repaired jk can still use
        # to strip the toolchain bins and restore the original homes.
        echo "jk: deactivate: '$__JK_EXE hook-env --clear' failed; environment left as-is (repair jk, then rerun)" >&2
    end
else
    set -e __JK_DIFF
end
set -e __JK_SHELL
set -e __JK_ORIG_PATH
