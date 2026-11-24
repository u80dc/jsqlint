package org.u80dc.jsqlint.commons.compiler;

import org.apache.maven.settings.*;
import org.apache.maven.settings.io.xpp3.SettingsXpp3Writer;
import org.u80dc.jsqlint.core.statistics.StatisticsRecorder;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import static org.u80dc.jsqlint.core.AnalysisStatistics.Phase.CLEANUP;

/**
 * 本次调用专用的 settings 文件，包含仓库与认证信息，构建结束后立即删除。
 *
 * @author u80dc
 */
final class MavenBuildSettings implements AutoCloseable {
    private final Path directory;
    private final Path userSettings;
    private final Path globalSettings;
    private final Path localRepository;
    private final StatisticsRecorder statisticsRecorder;

    private MavenBuildSettings(Path directory, Path localRepository, StatisticsRecorder statisticsRecorder) {
        this.directory = directory;
        this.userSettings = directory.resolve("settings.xml");
        this.globalSettings = directory.resolve("global-settings.xml");
        this.localRepository = localRepository;
        this.statisticsRecorder = statisticsRecorder;
    }

    static MavenBuildSettings create(MavenRepositoryOptions options) throws IOException {
        return create(options, StatisticsRecorder.none());
    }

    static MavenBuildSettings create(MavenRepositoryOptions options, StatisticsRecorder statisticsRecorder) throws IOException {
        Objects.requireNonNull(statisticsRecorder, "statisticsRecorder");
        Path local = options.localRepository() == null
                ? Path.of(System.getProperty("user.home"), ".m2", "repository").toAbsolutePath().normalize()
                : options.localRepository();
        Files.createDirectories(local);
        Path temporary = Path.of(System.getProperty("java.io.tmpdir"));
        Files.createDirectories(temporary);
        // POSIX 系统限制临时目录访问权限；Windows 使用当前用户临时目录的 ACL。
        Path directory = Files.getFileStore(temporary).supportsFileAttributeView("posix")
                ? Files.createTempDirectory(temporary, "jsqlint-maven-", PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")))
                : Files.createTempDirectory(temporary, "jsqlint-maven-");
        var result = new MavenBuildSettings(directory, local, statisticsRecorder);
        try {
            var settings = new Settings();
            settings.setLocalRepository(local.toString());
            var profile = new Profile();
            profile.setId("jsqlint-repositories");
            var repositories = new ArrayList<>(options.remoteRepositories());
            repositories.add(new MavenRemoteRepository("central", URI.create("https://repo.maven.apache.org/maven2")));
            for (var configured : repositories) {
                Repository repository = repository(configured);
                profile.addRepository(repository);
                // 构建插件及其依赖也需要访问私有仓库，不能只配置 repositories。
                profile.addPluginRepository(repository.clone());
                if (configured.username() != null) {
                    var server = new Server();
                    server.setId(configured.id());
                    server.setUsername(configured.username());
                    server.setPassword(configured.password());
                    settings.addServer(server);
                }
                if (configured.mirrorOf() != null) {
                    var mirror = new Mirror();
                    mirror.setId(configured.id());
                    mirror.setUrl(configured.url().toASCIIString());
                    mirror.setMirrorOf(configured.mirrorOf());
                    settings.addMirror(mirror);
                }
            }
            settings.addProfile(profile);
            settings.addActiveProfile(profile.getId());
            write(result.userSettings, settings);
            // 显式覆盖全局和用户 settings，避免宿主机器的镜像悄悄替换默认官方仓库。
            write(result.globalSettings, new Settings());
            return result;
        } catch (IOException | RuntimeException error) {
            try {
                result.close();
            } catch (IOException cleanup) {
                error.addSuppressed(cleanup);
            }
            throw error;
        }
    }

    private static Repository repository(MavenRemoteRepository configured) {
        var repository = new Repository();
        repository.setId(configured.id());
        repository.setUrl(configured.url().toASCIIString());
        var releases = new RepositoryPolicy();
        releases.setEnabled(configured.releasesEnabled());
        repository.setReleases(releases);
        var snapshots = new RepositoryPolicy();
        snapshots.setEnabled(configured.snapshotsEnabled());
        repository.setSnapshots(snapshots);
        return repository;
    }

    private static void write(Path path, Settings settings) throws IOException {
        try (var writer = Files.newBufferedWriter(path)) {
            new SettingsXpp3Writer().write(writer, settings);
        }
    }

    List<String> arguments() {
        return List.of("--settings", userSettings.toString(), "--global-settings", globalSettings.toString(),
                "-Dmaven.repo.local=" + localRepository);
    }

    @Override
    public void close() throws IOException {
        // 路径全部来自本次创建的目录，不递归删除，不触碰用户的仓库缓存。
        try (var cleanup = statisticsRecorder.measure(CLEANUP)) {
            try {
                Files.deleteIfExists(userSettings);
            } finally {
                try {
                    Files.deleteIfExists(globalSettings);
                } finally {
                    Files.deleteIfExists(directory);
                }
            }
        }
    }
}
