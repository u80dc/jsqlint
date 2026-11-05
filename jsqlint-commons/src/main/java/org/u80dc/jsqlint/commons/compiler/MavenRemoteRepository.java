package org.u80dc.jsqlint.commons.compiler;

import org.jspecify.annotations.Nullable;

import java.net.URI;
import java.util.Locale;
import java.util.Objects;

/**
 * Maven 远程仓库。默认启用 release、禁用 snapshot；认证信息按仓库 ID 关联。
 * mirrorOf 为空时作为附加仓库，设置为 "*" 时可通过私服代理所有远程仓库。
 * 密码或访问令牌通过 password 传入，不能放在 URL 中。
 *
 * @author u80dc
 */
public record MavenRemoteRepository(String id, URI url, boolean releasesEnabled, boolean snapshotsEnabled,
                                    @Nullable String username, @Nullable String password, @Nullable String mirrorOf) {
    public MavenRemoteRepository {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(url, "url");
        if (!id.matches("[A-Za-z0-9_.-]+") || id.equals("local")) {
            throw new IllegalArgumentException("Repository ID must be a nonblank Maven identifier other than local");
        }
        String scheme = url.getScheme() == null ? "" : url.getScheme().toLowerCase(Locale.ROOT);
        if ((!scheme.equals("https") && !scheme.equals("http") && !scheme.equals("file"))
                || url.isOpaque() || url.getRawUserInfo() != null || url.getRawQuery() != null || url.getRawFragment() != null
                || (!scheme.equals("file") && url.getHost() == null)) {
            throw new IllegalArgumentException("Repository URL must be an absolute HTTP(S) or file URL without credentials, query or fragment");
        }
        if (!releasesEnabled && !snapshotsEnabled) {
            throw new IllegalArgumentException("At least one repository policy must be enabled");
        }
        if ((username == null) != (password == null) || (username != null && username.isBlank())) {
            throw new IllegalArgumentException("Repository username and password must be supplied together");
        }
        if (mirrorOf != null && mirrorOf.isBlank()) {
            throw new IllegalArgumentException("mirrorOf must not be blank");
        }
    }

    public MavenRemoteRepository(String id, URI url) {
        this(id, url, true, false, null, null, null);
    }

    public MavenRemoteRepository withCredentials(String username, String password) {
        return new MavenRemoteRepository(id, url, releasesEnabled, snapshotsEnabled, username, password, mirrorOf);
    }

    public MavenRemoteRepository withSnapshotsEnabled(boolean enabled) {
        return new MavenRemoteRepository(id, url, releasesEnabled, enabled, username, password, mirrorOf);
    }

    public MavenRemoteRepository withReleasesEnabled(boolean enabled) {
        return new MavenRemoteRepository(id, url, enabled, snapshotsEnabled, username, password, mirrorOf);
    }

    /** 使用 Maven mirrorOf 语法，例如 "central"、"*" 或 "*,!another-repository"。 */
    public MavenRemoteRepository withMirrorOf(@Nullable String expression) {
        return new MavenRemoteRepository(id, url, releasesEnabled, snapshotsEnabled, username, password, expression);
    }

    /** 防止配置对象被日志打印时泄漏密码或令牌。 */
    @Override
    public String toString() {
        return "MavenRemoteRepository[id=" + id + ", url=" + url + ", releasesEnabled=" + releasesEnabled
                + ", snapshotsEnabled=" + snapshotsEnabled + ", credentials=" + (username == null ? "none" : "***")
                + ", mirrorOf=" + mirrorOf + "]";
    }
}
