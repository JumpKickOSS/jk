// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine;

import cc.jumpkick.config.JkBuildParser;
import cc.jumpkick.config.TomlScan;
import cc.jumpkick.host.Interned;
import cc.jumpkick.task.AbiMemo;
import cc.jumpkick.task.FileHashMemo;
import cc.jumpkick.version.Versions;

/**
 * Empties the process-wide memos whose payoff is the next build of the same workspace, once the
 * engine has sat idle long enough that there may be no such build: parsed manifests, scanned TOML
 * lines, parsed versions, the interned-string table, and the file-hash and ABI stores (persisted
 * first; the next build reloads them). An engine that has touched every workspace on a machine
 * otherwise keeps every one of their manifest trees for its life. Returns the log fragment naming
 * what went.
 */
final class MemoTrim {

    private MemoTrim() {}

    /** Drop every idle-evictable memo; {@code memos dropped: manifests 1943, toml files 812, …}. */
    static String drop() {
        int manifests = JkBuildParser.dropMemos();
        int tomlFiles = TomlScan.dropMemos();
        int versions = Versions.dropParsed();
        int strings = Interned.dropAll();
        int fileHashes = FileHashMemo.dropAll();
        int abiTokens = AbiMemo.dropAll();
        return "memos dropped: manifests " + manifests
                + ", toml files " + tomlFiles
                + ", versions " + versions
                + ", strings " + strings
                + ", file hashes " + fileHashes
                + ", abi tokens " + abiTokens;
    }
}
