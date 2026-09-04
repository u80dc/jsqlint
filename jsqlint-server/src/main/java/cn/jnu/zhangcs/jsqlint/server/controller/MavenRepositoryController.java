package cn.jnu.zhangcs.jsqlint.server.controller;

import cn.jnu.zhangcs.jsqlint.server.entity.MavenRepository;
import cn.jnu.zhangcs.jsqlint.server.service.MavenRepositoryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * Maven 仓库配置控制器
 */
@Tag(name = "Maven仓库管理", description = "Maven 仓库配置的创建、更新、删除和查询")
@RestController
@RequestMapping("/api/maven-repositories")
@RequiredArgsConstructor
public class MavenRepositoryController {

    private final MavenRepositoryService repositoryService;

    @Operation(summary = "创建Maven仓库", description = "创建一个新的 Maven 仓库配置")
    @PostMapping
    public ResponseEntity<MavenRepository> create(@Valid @RequestBody MavenRepository repository) {
        MavenRepository created = repositoryService.create(repository);
        return ResponseEntity.ok(created);
    }

    @Operation(summary = "更新Maven仓库", description = "更新指定ID的 Maven 仓库配置")
    @PutMapping("/{id}")
    public ResponseEntity<MavenRepository> update(
            @Parameter(name = "id", description = "仓库ID", example = "1") @PathVariable(value = "id") Long id,
            @Valid @RequestBody MavenRepository repository) {
        repository.setId(id);
        MavenRepository updated = repositoryService.update(repository);
        return ResponseEntity.ok(updated);
    }

    @Operation(summary = "删除Maven仓库", description = "根据仓库ID删除指定的 Maven 仓库配置")
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(
            @Parameter(name = "id", description = "仓库ID", example = "1") @PathVariable(value = "id")  Long id) {
        repositoryService.delete(id);
        return ResponseEntity.ok().build();
    }

    @Operation(summary = "查询Maven仓库详情", description = "根据仓库ID查询 Maven 仓库的详细信息")
    @GetMapping("/{id}")
    public ResponseEntity<MavenRepository> getById(
            @Parameter(name = "id", description = "仓库ID", example = "1") @PathVariable(value = "id")  Long id) {
        MavenRepository repository = repositoryService.getById(id);
        if (repository == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(repository);
    }

    @Operation(summary = "查询Maven仓库列表", description = "分页查询 Maven 仓库列表，可根据类型、状态筛选")
    @GetMapping
    public ResponseEntity<Map<String, Object>> list(
            @Parameter(name = "type", description = "仓库类型（0:中央仓库, 1:私有仓库）", example = "1") @RequestParam(value = "type", required = false) Integer type,
            @Parameter(name = "status", description = "状态（0：未启用，1：启用，为空时不限制）", example = "1") @RequestParam(value = "status", required = false) Integer status,
            @Parameter(name = "page", description = "页码", example = "1") @RequestParam(value = "page", defaultValue = "1") int page,
            @Parameter(name = "pageSize", description = "每页数量", example = "10") @RequestParam(value = "pageSize", defaultValue = "10") int pageSize) {
        com.baomidou.mybatisplus.extension.plugins.pagination.Page<MavenRepository> result =
                repositoryService.getRepositoryPage(type, status, page, pageSize);
        return ResponseEntity.ok(Map.of(
                "list", result.getRecords(),
                "total", result.getTotal()
        ));
    }
}