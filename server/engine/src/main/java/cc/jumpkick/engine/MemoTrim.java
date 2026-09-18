// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine;

import cc.jumpkick.config.JkBuildParser;
import cc.jumpkick.config.TomlScan;
import cc.jumpkick.config.WorkspaceScan;
import cc.jumpkick.host.Interned;
import cc.jumpkick.resolve.ResolveProcessCacheControl;
import cc.jumpkick.task.AbiMemo;
import cc.jumpkick.task.FileHashMemo;
import cc.jumpkick.version.Versions;
import java.nio.file.Path;
import org.jspecify.annotations.Nullable;

/**
 * Empties the process-wide memos whose payoff is the next build of the same workspace, once the
 * engine has sat idle long enough that there may be no such build: parsed manifests, scanned TOML
 * lines, parsed versions, the interned-string table, the file-hash and ABI stores (persisted
 * first; the next build reloads them), and the resolve memos — effective POMs, repository hits,
 * version lists, module metadata. An engine that has touched every workspace on a machine
 * otherwise keeps every one of their manifest trees and POM graphs for its life; the workspace
 * built last keeps its manifests. Returns the log fragment naming what went.
 */
final class MemoTrim {

    private MemoTrim() {}

    /**
     * Drop every idle-evictable memo; {@code memos dropped: manifests 1943, toml files 812, …}. The
     * manifests and TOML files of the workspace that owns {@code lastBuilt} — the directory the
     * last plan job ran in, or null when none has — are kept, so a developer coming back to a large
     * reactor after a pause finds its manifests still parsed; the line names that root.
     */
    static String drop(@Nullable Path lastBuilt) {
        Path keep = lastBuilt == null ? null : WorkspaceScan.findRoot(lastBuilt).orElse(lastBuilt);
        int manifests = keep == null ? JkBuildParser.dropMemos() : JkBuildParser.dropMemosOutside(keep);
        int tomlFiles = keep == null ? TomlScan.dropMemos() : TomlScan.dropMemosOutside(keep);
        int versions = Versions.dropParsed();
        int strings = Interned.dropAll();
        int fileHashes = FileHashMemo.dropAll();
        int abiTokens = AbiMemo.dropAll();
        ResolveProcessCacheControl.Dropped resolve = ResolveProcessCacheControl.dropMemos();
        return "memos dropped: manifests " + manifests
                + ", toml files " + tomlFiles
                + ", versions " + versions
                + ", strings " + strings
                + ", file hashes " + fileHashes
                + ", abi tokens " + abiTokens
                + ", effective poms " + resolve.effectivePoms()
                + ", repository hits " + resolve.repositoryHits()
                + ", version lists " + resolve.versionLists()
                + ", module metadata " + resolve.moduleMetadata()
                + (keep == null ? "" : "; kept for " + keep);
    }
}
