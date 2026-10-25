// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.wire.protocol;

import cc.jumpkick.credential.RepoCredential;
import cc.jumpkick.jsonl.Jsonl;
import cc.jumpkick.model.InstallDestination;
import cc.jumpkick.model.RepositorySpec;
import cc.jumpkick.wire.runtime.TailArgs;
import java.net.URI;
import java.nio.file.Path;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * {@link TailArgs} as the nested {@code tail} object of a build request: {@code {"kind":"native",…}}.
 * A publish credential rides as {@code authType} plus its fields, the shape the client resolved it
 * in; the engine files it as a secret on decode.
 */
public final class TailArgsJson {

    private TailArgsJson() {}

    public static String encode(TailArgs tail) {
        return "{" + fields(tail) + "}";
    }

    private static String fields(TailArgs tail) {
        return switch (tail) {
            case TailArgs.Native n ->
                RequestJson.fields()
                        .string("kind", "native")
                        .optionalNonBlankString("main", n.main())
                        .optionalArray("extraArgs", n.extraArgs())
                        .body();
            case TailArgs.Image i ->
                RequestJson.fields()
                        .string("kind", "image")
                        .optionalString("main", i.main())
                        .optionalString("registry", i.registry())
                        .optionalString("tag", i.tag())
                        .optionalString("tarball", i.tarball())
                        .optionalString("docker", i.docker())
                        .body();
            case TailArgs.Install i ->
                RequestJson.fields()
                        .string("kind", "install")
                        .optionalNonBlankString(
                                "m2Dir", i.m2Dir() == null ? null : i.m2Dir().toString())
                        .optionalNonBlankString(
                                InstallDestination.KEY,
                                i.destination() == null ? null : i.destination().spelling())
                        .optionalTrue("snapshot", i.snapshot())
                        .body();
            case TailArgs.Train t ->
                RequestJson.fields()
                        .string("kind", "train")
                        .optionalNonBlankString("profile", t.profile())
                        .optionalTrue("force", t.force())
                        .body();
            case TailArgs.Publish p -> publish(p);
        };
    }

    private static String publish(TailArgs.Publish p) {
        RequestJson json = RequestJson.fields()
                .string("kind", "publish")
                .optionalNonBlankString(
                        "repoUrl", p.repoUrl() == null ? null : p.repoUrl().toString())
                .optionalNonBlankString("region", p.region())
                .optionalNonBlankString("endpoint", p.endpoint())
                .optionalNonBlankString("jar", p.jar() == null ? null : p.jar().toString())
                .optionalTrue("allowSnapshot", p.allowSnapshot())
                .optionalTrue("dryRun", p.dryRun())
                .optionalNonBlankString(
                        "keyFile", p.keyFile() == null ? null : p.keyFile().toString())
                .optionalNonBlankString("gpgPassphrase", p.gpgPassphrase())
                .optionalTrue("sigstore", p.sigstore())
                .optionalTrue("slsa", p.slsa())
                .optionalTrue("sbom", p.sbom())
                .optionalTrue(RepositorySpec.CENTRAL, p.central())
                .optionalNonBlankString("publishingType", p.publishingType());
        return switch (p.credential()) {
            case RepoCredential.Basic b ->
                json.string("authType", "basic")
                        .string("user", b.username())
                        .string("pass", b.password())
                        .body();
            case RepoCredential.Bearer b ->
                json.string("authType", "bearer").string("token", b.token()).body();
            default -> json.string("authType", "anonymous").body();
        };
    }

    public static @Nullable TailArgs decode(@Nullable String json) {
        if (json == null) return null;
        String kind = Jsonl.str(json, "kind");
        if (kind == null) return null;
        return switch (kind) {
            case "native" -> new TailArgs.Native(Jsonl.str(json, "main"), Jsonl.strArray(json, "extraArgs"));
            case "image" ->
                new TailArgs.Image(
                        Jsonl.str(json, "main"),
                        Jsonl.str(json, "registry"),
                        Jsonl.str(json, "tag"),
                        Jsonl.str(json, "tarball"),
                        Jsonl.str(json, "docker"));
            case "install" ->
                new TailArgs.Install(
                        path(Jsonl.str(json, "m2Dir")),
                        InstallDestination.parse(Jsonl.str(json, InstallDestination.KEY))
                                .orElse(null),
                        Jsonl.bool(json, "snapshot", false));
            case "train" -> new TailArgs.Train(Jsonl.str(json, "profile"), Jsonl.bool(json, "force", false));
            case "publish" ->
                new TailArgs.Publish(
                        uri(Jsonl.str(json, "repoUrl")),
                        Jsonl.str(json, "region"),
                        Jsonl.str(json, "endpoint"),
                        path(Jsonl.str(json, "jar")),
                        Jsonl.bool(json, "allowSnapshot", false),
                        Jsonl.bool(json, "dryRun", false),
                        path(Jsonl.str(json, "keyFile")),
                        Jsonl.str(json, "gpgPassphrase"),
                        Jsonl.bool(json, "sigstore", false),
                        Jsonl.bool(json, "slsa", false),
                        Jsonl.bool(json, "sbom", false),
                        credential(json),
                        Jsonl.bool(json, RepositorySpec.CENTRAL, false),
                        Jsonl.str(json, "publishingType"));
            default -> throw new IllegalArgumentException("unknown tail kind `" + kind + "`");
        };
    }

    private static RepoCredential credential(String json) {
        return switch (String.valueOf(Jsonl.str(json, "authType"))) {
            case "basic" ->
                new RepoCredential.Basic(
                        Objects.requireNonNull(Jsonl.str(json, "user"), "user"),
                        Objects.requireNonNullElse(Jsonl.str(json, "pass"), ""));
            case "bearer" -> new RepoCredential.Bearer(Objects.requireNonNull(Jsonl.str(json, "token"), "token"));
            default -> RepoCredential.ANONYMOUS;
        };
    }

    private static @Nullable Path path(@Nullable String s) {
        return s == null || s.isBlank() ? null : Path.of(s);
    }

    private static @Nullable URI uri(@Nullable String s) {
        return s == null || s.isBlank() ? null : URI.create(s);
    }
}
