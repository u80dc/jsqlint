package cn.jnu.zhangcs.jsqlint.server.service.impl;

import cn.jnu.zhangcs.jsqlint.server.common.exception.BizException;
import cn.jnu.zhangcs.jsqlint.server.entity.AuditRule;
import cn.jnu.zhangcs.jsqlint.server.entity.Project;
import cn.jnu.zhangcs.jsqlint.server.entity.SqlWhitelist;
import cn.jnu.zhangcs.jsqlint.server.mapper.AuditRuleMapper;
import cn.jnu.zhangcs.jsqlint.server.mapper.SqlWhitelistMapper;
import cn.jnu.zhangcs.jsqlint.server.model.dto.SqlWhitelistDTO;
import cn.jnu.zhangcs.jsqlint.server.service.ProjectService;
import cn.jnu.zhangcs.jsqlint.common.util.FingerprintUtils;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link SqlWhitelistServiceImpl} 单元测试。
 *
 * @author zhangcs
 */
@ExtendWith(MockitoExtension.class)
class SqlWhitelistServiceImplTest {

    @BeforeAll
    static void initTableInfo() {
        // wrapper.select(字段...) 需要实体列缓存，纯单元测试下需手动初始化
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), SqlWhitelist.class);
    }

    @Mock
    private SqlWhitelistMapper sqlWhitelistMapper;

    @Mock
    private AuditRuleMapper auditRuleMapper;

    @Mock
    private ProjectService projectService;

    @InjectMocks
    private SqlWhitelistServiceImpl whitelistService;

    private void mockProjectExists() {
        when(projectService.getById(1L)).thenReturn(new Project());
    }

    @Test
    void list_projectNotFound_throwsException() {
        when(projectService.getById(9L)).thenReturn(null);

        assertThatThrownBy(() -> whitelistService.list(9L, 1, 10, null))
                .isInstanceOf(BizException.class)
                .hasMessage("项目不存在");
    }

    @Test
    void list_returnsPagedRecords() {
        mockProjectExists();
        Page<SqlWhitelist> page = new Page<>(1, 10);
        SqlWhitelist item = new SqlWhitelist();
        page.setRecords(List.of(item));
        when(sqlWhitelistMapper.selectPage(any(), any())).thenReturn(page);

        List<SqlWhitelist> result = whitelistService.list(1L, 1, 10, 2L);

        assertThat(result).containsExactly(item);
    }

    @Test
    void count_projectNotFound_throwsException() {
        when(projectService.getById(9L)).thenReturn(null);

        assertThatThrownBy(() -> whitelistService.count(9L, null))
                .isInstanceOf(BizException.class)
                .hasMessage("项目不存在");
    }

    @Test
    void count_returnsCount() {
        mockProjectExists();
        when(sqlWhitelistMapper.selectCount(any())).thenReturn(5L);

        assertThat(whitelistService.count(1L, 2L)).isEqualTo(5L);
    }

    @Test
    void create_projectNotFound_throwsException() {
        when(projectService.getById(9L)).thenReturn(null);

        assertThatThrownBy(() -> whitelistService.create(9L, new SqlWhitelistDTO(), 1L))
                .isInstanceOf(BizException.class)
                .hasMessage("项目不存在");
    }

    @Test
    void create_ruleNotFound_throwsException() {
        mockProjectExists();
        when(auditRuleMapper.selectById(any())).thenReturn(null);
        SqlWhitelistDTO dto = new SqlWhitelistDTO();
        dto.setRuleId(3L);

        assertThatThrownBy(() -> whitelistService.create(1L, dto, 1L))
                .isInstanceOf(BizException.class)
                .hasMessage("审核规则不存在");
    }

    @Test
    void create_invalidSql_cannotGenerateFingerprint() {
        mockProjectExists();
        when(auditRuleMapper.selectById(3L)).thenReturn(new AuditRule());
        SqlWhitelistDTO dto = new SqlWhitelistDTO();
        dto.setRuleId(3L);
        dto.setSql("   ");

        assertThatThrownBy(() -> whitelistService.create(1L, dto, 1L))
                .isInstanceOf(BizException.class)
                .hasMessage("SQL 语句无效，无法生成指纹");
    }

    @Test
    void create_duplicateRecord_throwsException() {
        mockProjectExists();
        when(auditRuleMapper.selectById(3L)).thenReturn(new AuditRule());
        when(sqlWhitelistMapper.selectCount(any())).thenReturn(1L);
        SqlWhitelistDTO dto = new SqlWhitelistDTO();
        dto.setRuleId(3L);
        dto.setSql("SELECT * FROM t_user WHERE id = 1");

        assertThatThrownBy(() -> whitelistService.create(1L, dto, 1L))
                .isInstanceOf(BizException.class)
                .hasMessage("该 SQL 和规则的白名单已存在");
    }

    @Test
    void create_fillsFingerprintFieldsAndInserts() {
        mockProjectExists();
        when(auditRuleMapper.selectById(3L)).thenReturn(new AuditRule());
        when(sqlWhitelistMapper.selectCount(any())).thenReturn(0L);
        String sql = "SELECT * FROM t_user WHERE id = 1";
        SqlWhitelistDTO dto = new SqlWhitelistDTO();
        dto.setRuleId(3L);
        dto.setSql(sql);
        dto.setReason("业务需要");

        SqlWhitelist result = whitelistService.create(1L, dto, 10L);

        assertThat(result.getProjectId()).isEqualTo(1L);
        assertThat(result.getRuleId()).isEqualTo(3L);
        assertThat(result.getSqlSample()).isEqualTo(sql);
        assertThat(result.getReason()).isEqualTo("业务需要");
        assertThat(result.getCreateUserId()).isEqualTo(10L);
        assertThat(result.getSqlFingerprint())
                .isEqualTo(FingerprintUtils.fingerprint(sql))
                .hasSize(64);
        verify(sqlWhitelistMapper).insert(result);
    }

    @Test
    void delete_projectNotFound_throwsException() {
        when(projectService.getById(9L)).thenReturn(null);

        assertThatThrownBy(() -> whitelistService.delete(9L, 7L))
                .isInstanceOf(BizException.class)
                .hasMessage("项目不存在");
    }

    @Test
    void delete_recordNotFound_throwsException() {
        mockProjectExists();
        when(sqlWhitelistMapper.selectOne(any())).thenReturn(null);

        assertThatThrownBy(() -> whitelistService.delete(1L, 7L))
                .isInstanceOf(BizException.class)
                .hasMessage("白名单记录不存在");
    }

    @Test
    void delete_deletesRecord() {
        mockProjectExists();
        when(sqlWhitelistMapper.selectOne(any())).thenReturn(new SqlWhitelist());

        whitelistService.delete(1L, 7L);

        verify(sqlWhitelistMapper).deleteById(7L);
    }

    @Test
    void loadWhitelistSet_joinsKeyByFingerprintAndRuleId() {
        SqlWhitelist w1 = new SqlWhitelist();
        w1.setSqlFingerprint("fp1");
        w1.setRuleId(3L);
        SqlWhitelist w2 = new SqlWhitelist();
        w2.setSqlFingerprint("fp2");
        w2.setRuleId(4L);
        when(sqlWhitelistMapper.selectList(any())).thenReturn(List.of(w1, w2));

        Set<String> keys = whitelistService.loadWhitelistSet(1L);

        assertThat(keys).containsExactlyInAnyOrder("fp1:3", "fp2:4");
    }
}
