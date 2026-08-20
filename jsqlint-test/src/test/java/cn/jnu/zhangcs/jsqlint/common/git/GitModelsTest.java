package cn.jnu.zhangcs.jsqlint.common.git;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link CloneResult}、{@link FetchResult}、{@link PullResult} 数据模型测试。
 */
class GitModelsTest {

    @Test
    void cloneResultAllArgsConstructor() {
        CloneResult result = new CloneResult(true, "Clone成功", "/tmp/repo");

        assertTrue(result.isSuccess());
        assertEquals("Clone成功", result.getMessage());
        assertEquals("/tmp/repo", result.getLocalPath());
    }

    @Test
    void cloneResultDefaultsAndSetters() {
        CloneResult result = new CloneResult();

        assertFalse(result.isSuccess());
        assertNull(result.getMessage());
        assertNull(result.getLocalPath());

        result.setSuccess(true);
        result.setMessage("m");
        result.setLocalPath("p");

        assertTrue(result.isSuccess());
        assertEquals("m", result.getMessage());
        assertEquals("p", result.getLocalPath());
    }

    @Test
    void fetchResultDefaultsInitializeCollections() {
        FetchResult result = new FetchResult();

        assertTrue(result.getBranches().isEmpty());
        assertTrue(result.getTags().isEmpty());
        assertFalse(result.isSuccess());

        result.setSuccess(true);
        result.setMessage("Fetch成功");
        result.setBranches(List.of("main", "dev"));
        result.setTags(List.of("v1"));

        assertTrue(result.isSuccess());
        assertEquals(List.of("main", "dev"), result.getBranches());
        assertEquals(List.of("v1"), result.getTags());
    }

    @Test
    void fetchResultAllArgsConstructor() {
        FetchResult result = new FetchResult(true, "ok", List.of("main"), List.of("v1", "v2"));

        assertTrue(result.isSuccess());
        assertEquals("ok", result.getMessage());
        assertEquals(2, result.getTags().size());
    }

    @Test
    void pullResultDefaultsAndSetters() {
        PullResult result = new PullResult();

        assertFalse(result.isSuccess());

        result.setSuccess(true);
        result.setMessage("Pull成功");
        result.setCommitId("0123456789abcdef0123456789abcdef01234567");
        result.setShortCommitId("0123456");
        result.setAuthorName("zhangcs");
        result.setCommitTime("2026-08-20T10:00");
        result.setCommitMessage("msg");

        assertTrue(result.isSuccess());
        assertEquals("0123456", result.getShortCommitId());
        assertEquals("zhangcs", result.getAuthorName());
        assertEquals("2026-08-20T10:00", result.getCommitTime());
        assertEquals("msg", result.getCommitMessage());
    }
}
