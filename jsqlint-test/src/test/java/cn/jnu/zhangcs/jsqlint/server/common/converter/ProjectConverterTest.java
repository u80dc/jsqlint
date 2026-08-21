package cn.jnu.zhangcs.jsqlint.server.common.converter;

import cn.jnu.zhangcs.jsqlint.server.common.enums.ProjectVersionStatus;
import cn.jnu.zhangcs.jsqlint.server.common.enums.ProjectVersionStepType;
import cn.jnu.zhangcs.jsqlint.server.entity.Credential;
import cn.jnu.zhangcs.jsqlint.server.entity.Project;
import cn.jnu.zhangcs.jsqlint.server.entity.ProjectVersion;
import cn.jnu.zhangcs.jsqlint.server.entity.User;
import cn.jnu.zhangcs.jsqlint.server.mapper.CredentialMapper;
import cn.jnu.zhangcs.jsqlint.server.model.dto.ProjectDTO;
import cn.jnu.zhangcs.jsqlint.server.model.dto.ProjectVersionDTO;
import cn.jnu.zhangcs.jsqlint.server.model.vo.ProjectVO;
import cn.jnu.zhangcs.jsqlint.server.model.vo.ProjectVersionVO;
import cn.jnu.zhangcs.jsqlint.server.service.UserService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * {@link ProjectConverter} 单元测试。
 *
 * @author zhangcs
 */
@ExtendWith(MockitoExtension.class)
class ProjectConverterTest {

    @Mock
    private CredentialMapper credentialMapper;

    @Mock
    private UserService userService;

    @InjectMocks
    private ProjectConverter converter;

    private ProjectDTO buildProjectDTO() {
        ProjectDTO dto = new ProjectDTO();
        dto.setName("jsqlint");
        dto.setDescription("SQL检查平台");
        dto.setGitUrl("https://git.example.com/jsqlint.git");
        dto.setRef("main");
        return dto;
    }

    @Test
    void fromDTO_enabledStatusAndDefaultCredentialId() {
        ProjectDTO dto = buildProjectDTO();

        Project project = converter.fromDTO(dto, 10L);

        assertThat(project.getName()).isEqualTo("jsqlint");
        assertThat(project.getDescription()).isEqualTo("SQL检查平台");
        assertThat(project.getGitUrl()).isEqualTo("https://git.example.com/jsqlint.git");
        assertThat(project.getRef()).isEqualTo("main");
        assertThat(project.getStatus()).isEqualTo(1);
        assertThat(project.getCreateUserId()).isEqualTo(10L);
        assertThat(project.getCredentialId()).isNull();
    }

    @Test
    void fromDTO_enabledZeroMapsToStatusZero() {
        ProjectDTO dto = buildProjectDTO();
        dto.setEnabled(0);

        assertThat(converter.fromDTO(dto, 1L).getStatus()).isZero();
    }

    @Test
    void fromDTO_credentialIdMinusOneConvertedToNull() {
        ProjectDTO dto = buildProjectDTO();
        dto.setCredentialId(-1L);

        assertThat(converter.fromDTO(dto, 1L).getCredentialId()).isNull();
    }

    @Test
    void fromDTO_normalCredentialIdKept() {
        ProjectDTO dto = buildProjectDTO();
        dto.setCredentialId(88L);

        assertThat(converter.fromDTO(dto, 1L).getCredentialId()).isEqualTo(88L);
    }

    @Test
    void updateFromDTO_overwritesOnlyNonNullFields() {
        Project target = new Project();
        target.setDescription("旧描述");
        target.setRef("master");
        target.setStatus(1);
        target.setCredentialId(5L);

        ProjectDTO dto = new ProjectDTO();
        dto.setDescription("新描述");
        converter.updateFromDTO(target, dto);

        assertThat(target.getDescription()).isEqualTo("新描述");
        assertThat(target.getRef()).isEqualTo("master");
        assertThat(target.getStatus()).isEqualTo(1);
        assertThat(target.getCredentialId()).isEqualTo(5L);
    }

    @Test
    void updateFromDTO_credentialIdMinusOneClearsCredential() {
        Project target = new Project();
        target.setCredentialId(5L);

        ProjectDTO dto = new ProjectDTO();
        dto.setCredentialId(-1L);
        converter.updateFromDTO(target, dto);

        assertThat(target.getCredentialId()).isNull();
    }

    @Test
    void updateFromDTO_normalCredentialIdOverwrites() {
        Project target = new Project();
        target.setCredentialId(5L);

        ProjectDTO dto = new ProjectDTO();
        dto.setCredentialId(6L);
        converter.updateFromDTO(target, dto);

        assertThat(target.getCredentialId()).isEqualTo(6L);
    }

    @Test
    void toVO_withoutCredentialBaseFieldsComplete() {
        Project project = new Project();
        project.setId(1L);
        project.setName("jsqlint");
        project.setDescription("desc");
        project.setGitUrl("git-url");
        project.setRef("main");
        project.setStatus(1);
        project.setCreateUserId(10L);

        ProjectVO vo = converter.toVO(project, "张三");

        assertThat(vo.getId()).isEqualTo(1L);
        assertThat(vo.getName()).isEqualTo("jsqlint");
        assertThat(vo.getEnabled()).isEqualTo(1);
        assertThat(vo.getCreateUserName()).isEqualTo("张三");
        assertThat(vo.getCredentialName()).isNull();
        assertThat(vo.getCredentialCreatorId()).isNull();
    }

    @Test
    void toVO_withCredentialFillsCredentialAndCreator() {
        Project project = new Project();
        project.setId(1L);
        project.setCredentialId(7L);

        Credential credential = new Credential();
        credential.setId(7L);
        credential.setName("公司凭证");
        credential.setVisibility(1);
        credential.setCreateUserId(20L);
        when(credentialMapper.selectById(7L)).thenReturn(credential);

        User creator = new User();
        creator.setId(20L);
        creator.setUsername("李四");
        when(userService.getById(20L)).thenReturn(creator);

        ProjectVO vo = converter.toVO(project, "张三");

        assertThat(vo.getCredentialName()).isEqualTo("公司凭证");
        assertThat(vo.getCredentialVisibility()).isEqualTo(1);
        assertThat(vo.getCredentialCreatorId()).isEqualTo(20L);
        assertThat(vo.getCredentialCreatorName()).isEqualTo("李四");
    }

    @Test
    void toVO_credentialNotFoundFieldsEmpty() {
        Project project = new Project();
        project.setCredentialId(99L);
        when(credentialMapper.selectById(99L)).thenReturn(null);

        ProjectVO vo = converter.toVO(project, null);

        assertThat(vo.getCredentialName()).isNull();
        assertThat(vo.getCredentialVisibility()).isNull();
    }

    @Test
    void toVO_credentialWithoutCreatorSkipsUserLookup() {
        Project project = new Project();
        project.setCredentialId(7L);

        Credential credential = new Credential();
        credential.setId(7L);
        credential.setName("无主凭证");
        credential.setCreateUserId(null);
        when(credentialMapper.selectById(7L)).thenReturn(credential);

        ProjectVO vo = converter.toVO(project, null);

        assertThat(vo.getCredentialName()).isEqualTo("无主凭证");
        assertThat(vo.getCredentialCreatorId()).isNull();
        assertThat(vo.getCredentialCreatorName()).isNull();
    }

    @Test
    void toProjectVOList_emptyProjectListReturnsEmptyVoList() {
        assertThat(converter.toProjectVOList(List.of(), List.of())).isEmpty();
    }

    @Test
    void toProjectVOList_matchesUserNamesByCreateUserId() {
        Project p1 = new Project();
        p1.setId(1L);
        p1.setCreateUserId(10L);
        Project p2 = new Project();
        p2.setId(2L);
        p2.setCreateUserId(20L);
        Project p3 = new Project();
        p3.setId(3L);
        p3.setCreateUserId(null);

        User u1 = new User();
        u1.setId(10L);
        u1.setUsername("张三");
        User u2 = new User();
        u2.setId(20L);
        u2.setUsername("李四");

        List<ProjectVO> vos = converter.toProjectVOList(List.of(p1, p2, p3), List.of(u1, u2));

        assertThat(vos).hasSize(3);
        assertThat(vos.get(0).getCreateUserName()).isEqualTo("张三");
        assertThat(vos.get(1).getCreateUserName()).isEqualTo("李四");
        assertThat(vos.get(2).getCreateUserName()).isNull();
    }

    @Test
    void fromDTO_versionDtoToEntity() {
        ProjectVersionDTO dto = new ProjectVersionDTO();
        dto.setRef("feature-x");
        dto.setCommitId("abc123");
        dto.setCommitMessage("fix bug");
        dto.setAuthor("王五");

        ProjectVersion version = converter.fromDTO(dto, 30L);

        assertThat(version.getRef()).isEqualTo("feature-x");
        assertThat(version.getCommitId()).isEqualTo("abc123");
        assertThat(version.getCommitMessage()).isEqualTo("fix bug");
        assertThat(version.getAuthor()).isEqualTo("王五");
        assertThat(version.getOperatorId()).isEqualTo(30L);
    }

    @Test
    void toVO_versionStatusAndStepNames() {
        ProjectVersion version = new ProjectVersion();
        version.setId(1L);
        version.setProjectId(2L);
        version.setRef("main");
        version.setCommitId("abc123");
        version.setCommitMessage("msg");
        version.setAuthor("作者");
        version.setOperatorId(30L);
        version.setOverallStatus(ProjectVersionStatus.SUCCESS);
        version.setCurrentStep(ProjectVersionStepType.RULE_AUDIT);
        version.setMessage(null);

        ProjectVersionVO vo = converter.toVO(version, "jsqlint项目", "操作人");

        assertThat(vo.getId()).isEqualTo(1L);
        assertThat(vo.getProjectId()).isEqualTo(2L);
        assertThat(vo.getProjectName()).isEqualTo("jsqlint项目");
        assertThat(vo.getOperatorName()).isEqualTo("操作人");
        assertThat(vo.getOverallStatus()).isEqualTo(2);
        assertThat(vo.getOverallStatusName()).isEqualTo("执行成功");
        assertThat(vo.getCurrentStep()).isEqualTo(3);
        assertThat(vo.getCurrentStepName()).isEqualTo("规则审核");
    }

    @Test
    void toVO_unSupportStepNameIsNull() {
        ProjectVersion version = new ProjectVersion();
        version.setOverallStatus((Integer) null);
        version.setCurrentStep((Integer) null);

        ProjectVersionVO vo = converter.toVO(version, null, null);

        assertThat(vo.getOverallStatusName()).isEqualTo("未知");
        assertThat(vo.getCurrentStepName()).isNull();
    }
}
