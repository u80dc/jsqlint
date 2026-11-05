package org.u80dc.jsqlint.commons.compiler;

import org.jspecify.annotations.Nullable;

import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;

/**
 * 单次 Maven 构建的不可变仓库配置。附加远程仓库优先于 Maven Central，项目 POM 的仓库仍参与解析。
 * localRepository 为空时使用 ${user.home}/.m2/repository；相对路径以调用进程工作目录为基准。
 * 不读取宿主 Maven settings.xml，也不修改宿主的配置文件或系统属性。
 *
 * @author u80dc
 */
public record MavenRepositoryOptions(List<MavenRemoteRepository> remoteRepositories, @Nullable Path localRepository) {
    public MavenRepositoryOptions {
        remoteRepositories = List.copyOf(remoteRepositories);
        var ids = new HashSet<String>();
        for (var repository : remoteRepositories) {
            if (repository.id().equals("central") || !ids.add(repository.id())) {
                throw new IllegalArgumentException("Additional repository IDs must be unique and must not be central");
            }
        }
        if (localRepository != null) localRepository = localRepository.toAbsolutePath().normalize();
    }

    public static MavenRepositoryOptions defaults() {
        return new MavenRepositoryOptions(List.of(), null);
    }

    public MavenRepositoryOptions withRemoteRepositories(List<MavenRemoteRepository> repositories) {
        return new MavenRepositoryOptions(repositories, localRepository);
    }

    /** null 恢复默认本地仓库；本地仓库是持久缓存，不随 class 输出目录清理。 */
    public MavenRepositoryOptions withLocalRepository(@Nullable Path directory) {
        return new MavenRepositoryOptions(remoteRepositories, directory);
    }
}
