package cn.jnu.zhangcs.jsqlint.server.controller;

import cn.jnu.zhangcs.jsqlint.server.common.exception.BizException;
import cn.jnu.zhangcs.jsqlint.server.common.exception.GlobalExceptionHandler;
import cn.jnu.zhangcs.jsqlint.server.entity.MavenRepository;
import cn.jnu.zhangcs.jsqlint.server.service.MavenRepositoryService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.http.converter.StringHttpMessageConverter;
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@link MavenRepositoryController} 单元测试（standalone MockMvc，service 全部 Mock）。
 *
 * @author zhangcs
 */
@ExtendWith(MockitoExtension.class)
class MavenRepositoryControllerTest {

    @Mock
    private MavenRepositoryService repositoryService;

    @InjectMocks
    private MavenRepositoryController mavenRepositoryController;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(mavenRepositoryController)
                .setControllerAdvice(new GlobalExceptionHandler())
                // standalone 默认 StringHttpMessageConverter 为 ISO-8859-1，中文响应会丢字，需显式指定 UTF-8
                .setMessageConverters(
                        new StringHttpMessageConverter(StandardCharsets.UTF_8),
                        new JacksonJsonHttpMessageConverter())
                .build();
    }

    @Test
    void create_validBody_returnsCreatedRepository() throws Exception {
        MavenRepository created = buildRepository(1L, "central", "中央仓库");
        ArgumentCaptor<MavenRepository> captor = ArgumentCaptor.forClass(MavenRepository.class);
        when(repositoryService.create(captor.capture())).thenReturn(created);

        mockMvc.perform(post("/api/maven-repositories")
                        .contentType(MediaType.APPLICATION_JSON)
                        .characterEncoding("UTF-8")
                        .content("{\"repoId\":\"central\",\"name\":\"中央仓库\","
                                + "\"url\":\"https://repo.maven.apache.org/maven2\","
                                + "\"type\":0,\"status\":1,\"releasesEnabled\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(1))
                .andExpect(jsonPath("$.repoId").value("central"))
                .andExpect(jsonPath("$.name").value("中央仓库"))
                .andExpect(jsonPath("$.url").value("https://repo.maven.apache.org/maven2"));

        // 请求体被正确反序列化后交给 service
        assertThat(captor.getValue().getRepoId()).isEqualTo("central");
        assertThat(captor.getValue().getType()).isZero();
    }

    @Test
    void create_duplicateRepoId_bizExceptionMappedTo400() throws Exception {
        when(repositoryService.create(any(MavenRepository.class)))
                .thenThrow(new BizException("仓库ID已存在: central"));

        mockMvc.perform(post("/api/maven-repositories")
                        .contentType(MediaType.APPLICATION_JSON)
                        .characterEncoding("UTF-8")
                        .content("{\"repoId\":\"central\",\"name\":\"中央仓库\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("仓库ID已存在: central"));
    }

    @Test
    void update_pathIdOverridesBodyId_returnsUpdatedRepository() throws Exception {
        MavenRepository updated = buildRepository(1L, "central", "中央仓库");
        ArgumentCaptor<MavenRepository> captor = ArgumentCaptor.forClass(MavenRepository.class);
        when(repositoryService.update(captor.capture())).thenReturn(updated);

        mockMvc.perform(put("/api/maven-repositories/1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .characterEncoding("UTF-8")
                        // 请求体中故意携带与路径不一致的 id，控制器应以路径 id 为准
                        .content("{\"id\":999,\"repoId\":\"central\",\"name\":\"中央仓库\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(1))
                .andExpect(jsonPath("$.name").value("中央仓库"));

        assertThat(captor.getValue().getId()).isEqualTo(1L);
    }

    @Test
    void update_notExists_bizExceptionMappedTo400() throws Exception {
        when(repositoryService.update(any(MavenRepository.class)))
                .thenThrow(new BizException("仓库不存在"));

        mockMvc.perform(put("/api/maven-repositories/99")
                        .contentType(MediaType.APPLICATION_JSON)
                        .characterEncoding("UTF-8")
                        .content("{\"repoId\":\"central\",\"name\":\"中央仓库\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("仓库不存在"));
    }

    @Test
    void delete_existingId_returnsOkWithEmptyBody() throws Exception {
        mockMvc.perform(delete("/api/maven-repositories/1"))
                .andExpect(status().isOk())
                .andExpect(content().string(""));

        verify(repositoryService).delete(1L);
    }

    @Test
    void getById_existing_returnsRepositoryDetail() throws Exception {
        when(repositoryService.getById(1L)).thenReturn(buildRepository(1L, "central", "中央仓库"));

        mockMvc.perform(get("/api/maven-repositories/1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(1))
                .andExpect(jsonPath("$.repoId").value("central"))
                .andExpect(jsonPath("$.name").value("中央仓库"));
    }

    @Test
    void getById_notExists_returns404() throws Exception {
        when(repositoryService.getById(99L)).thenReturn(null);

        mockMvc.perform(get("/api/maven-repositories/99"))
                .andExpect(status().isNotFound());
    }

    @Test
    void list_withType_returnsRepositoriesFilteredByType() throws Exception {
        MavenRepository privateRepo = buildRepository(2L, "private", "私有仓库");
        privateRepo.setType(1);
        when(repositoryService.getRepositoryPage(1, 1, 1, 10)).thenReturn(pageOf(List.of(privateRepo), 1));

        mockMvc.perform(get("/api/maven-repositories")
                        .param("type", "1")
                        .param("status", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.list.length()").value(1))
                .andExpect(jsonPath("$.list[0].id").value(2))
                .andExpect(jsonPath("$.list[0].repoId").value("private"))
                .andExpect(jsonPath("$.list[0].type").value(1));
    }

    @Test
    void list_withoutType_returnsAllRepositories() throws Exception {
        when(repositoryService.getRepositoryPage(null, null, 1, 10))
                .thenReturn(pageOf(List.of(
                        buildRepository(1L, "central", "中央仓库"),
                        buildRepository(2L, "private", "私有仓库")), 2));

        mockMvc.perform(get("/api/maven-repositories"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(2))
                .andExpect(jsonPath("$.list.length()").value(2));
    }

    private <T> com.baomidou.mybatisplus.extension.plugins.pagination.Page<T> pageOf(List<T> records, long total) {
        com.baomidou.mybatisplus.extension.plugins.pagination.Page<T> page =
                new com.baomidou.mybatisplus.extension.plugins.pagination.Page<>(1, 10);
        page.setRecords(records);
        page.setTotal(total);
        return page;
    }

    /**
     * 构造一个 Maven 仓库实体。
     */
    private MavenRepository buildRepository(Long id, String repoId, String name) {
        MavenRepository repository = new MavenRepository();
        repository.setId(id);
        repository.setRepoId(repoId);
        repository.setName(name);
        repository.setUrl("https://repo.maven.apache.org/maven2");
        repository.setType(0);
        repository.setLayout("default");
        repository.setReleasesEnabled(true);
        repository.setStatus(1);
        return repository;
    }
}
