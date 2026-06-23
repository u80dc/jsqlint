package cn.jnu.zhangcs.jsqlint.common.mvn;

/**
 * 仓库配置
 *
 * @param id       仓库唯一标识
 * @param url      仓库地址
 * @param release  是否启用Release
 * @param snapshot 是否启用Snapshot
 */
public record MavenRepo(String id, String url, boolean release, boolean snapshot) {
}
