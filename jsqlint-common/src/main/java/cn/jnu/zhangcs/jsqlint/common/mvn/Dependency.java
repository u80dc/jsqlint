package cn.jnu.zhangcs.jsqlint.common.mvn;

/**
 * 依赖
 *
 * @param groupId    组织
 * @param artifactId 名称
 * @param version    版本
 */
public record Dependency(String groupId, String artifactId, String version) {
    public String info() {
        return groupId + ":" + artifactId + ":" + version;
    }
}