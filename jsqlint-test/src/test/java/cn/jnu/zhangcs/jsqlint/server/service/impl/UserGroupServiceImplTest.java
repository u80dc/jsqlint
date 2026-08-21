package cn.jnu.zhangcs.jsqlint.server.service.impl;

import cn.jnu.zhangcs.jsqlint.server.common.exception.BizException;
import cn.jnu.zhangcs.jsqlint.server.entity.User;
import cn.jnu.zhangcs.jsqlint.server.entity.UserGroup;
import cn.jnu.zhangcs.jsqlint.server.entity.UserGroupMember;
import cn.jnu.zhangcs.jsqlint.server.mapper.UserGroupMapper;
import cn.jnu.zhangcs.jsqlint.server.mapper.UserGroupMemberMapper;
import cn.jnu.zhangcs.jsqlint.server.service.UserService;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link UserGroupServiceImpl} 单元测试。
 *
 * @author zhangcs
 */
@ExtendWith(MockitoExtension.class)
class UserGroupServiceImplTest {

    @Mock
    private UserGroupMapper userGroupMapper;

    @Mock
    private UserGroupMemberMapper userGroupMemberMapper;

    @Mock
    private UserService userService;

    @InjectMocks
    private UserGroupServiceImpl userGroupService;

    @Test
    void create_statusZero_creatorAutoJoins() {
        UserGroup group = new UserGroup();
        group.setCreatorId(1L);

        UserGroup result = userGroupService.create(group);

        assertThat(result).isSameAs(group);
        assertThat(group.getStatus()).isZero();
        verify(userGroupMapper).insert(group);
        verify(userGroupMemberMapper).insert(any(UserGroupMember.class));
    }

    @Test
    void create_noCreator_doesNotInsertMember() {
        UserGroup group = new UserGroup();

        userGroupService.create(group);

        verify(userGroupMemberMapper, never()).insert(any(UserGroupMember.class));
    }

    @Test
    void update_groupNotFound_throwsException() {
        UserGroup group = new UserGroup();
        group.setId(9L);
        when(userGroupMapper.selectById(9L)).thenReturn(null);

        assertThatThrownBy(() -> userGroupService.update(group))
                .isInstanceOf(BizException.class)
                .hasMessage("用户组不存在");
    }

    @Test
    void update_afterUpdate_returnsLatestData() {
        UserGroup group = new UserGroup();
        group.setId(1L);
        UserGroup latest = new UserGroup();
        latest.setId(1L);
        when(userGroupMapper.selectById(1L)).thenReturn(new UserGroup(), latest);

        UserGroup result = userGroupService.update(group);

        verify(userGroupMapper).updateById(group);
        assertThat(result).isSameAs(latest);
    }

    @Test
    void delete_groupNotFound_throwsException() {
        when(userGroupMapper.selectById(9L)).thenReturn(null);

        assertThatThrownBy(() -> userGroupService.delete(9L))
                .isInstanceOf(BizException.class)
                .hasMessage("用户组不存在");
    }

    @Test
    void delete_softDeletesAndClearsMembers() {
        UserGroup group = new UserGroup();
        when(userGroupMapper.selectById(1L)).thenReturn(group);

        userGroupService.delete(1L);

        assertThat(group.getStatus()).isEqualTo(-1);
        verify(userGroupMapper).updateById(group);
        verify(userGroupMemberMapper).delete(any());
    }

    @Test
    void getById_delegatesToMapper() {
        UserGroup group = new UserGroup();
        when(userGroupMapper.selectById(1L)).thenReturn(group);

        assertThat(userGroupService.getById(1L)).isSameAs(group);
    }

    @Test
    void list_delegatesToMapper() {
        List<UserGroup> list = List.of(new UserGroup());
        when(userGroupMapper.selectList(any())).thenReturn(list);

        assertThat(userGroupService.list()).isSameAs(list);
    }

    @Test
    void listNotDeleted_delegatesToMapper() {
        List<UserGroup> list = List.of(new UserGroup());
        when(userGroupMapper.selectList(any())).thenReturn(list);

        assertThat(userGroupService.listNotDeleted()).isSameAs(list);
    }

    @Test
    void getByKeyword_blankKeyword_returnsEmptyList() {
        assertThat(userGroupService.getByKeyword("")).isEmpty();
    }

    @Test
    void getByKeyword_returnsPagedRecords() {
        Page<UserGroup> page = new Page<>(0, 20);
        UserGroup group = new UserGroup();
        group.setName("研发组");
        page.setRecords(List.of(group));
        when(userGroupMapper.selectPage(any(), any())).thenReturn(page);

        List<UserGroup> result = userGroupService.getByKeyword("研发");

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getName()).isEqualTo("研发组");
    }

    @Test
    void getMembers_nullGroupId_returnsEmptyList() {
        assertThat(userGroupService.getMembers(null)).isEmpty();
    }

    @Test
    void getMembers_noMembers_returnsEmptyList() {
        when(userGroupMemberMapper.selectList(any())).thenReturn(List.of());

        assertThat(userGroupService.getMembers(1L)).isEmpty();
    }

    @Test
    void getMembers_queriesByUserIds() {
        UserGroupMember relation = new UserGroupMember();
        relation.setUserId(5L);
        when(userGroupMemberMapper.selectList(any())).thenReturn(List.of(relation));
        List<User> members = List.of(new User());
        when(userService.getByIds(any())).thenReturn(members);

        assertThat(userGroupService.getMembers(1L)).isSameAs(members);
    }

    @Test
    void getMemberCount_nullGroupId_returnsZero() {
        assertThat(userGroupService.getMemberCount(null)).isZero();
    }

    @Test
    void getMemberCount_nullCount_returnsZero() {
        when(userGroupMemberMapper.selectCount(any())).thenReturn(null);

        assertThat(userGroupService.getMemberCount(1L)).isZero();
    }

    @Test
    void getMemberCount_returnsIntValue() {
        when(userGroupMemberMapper.selectCount(any())).thenReturn(3L);

        assertThat(userGroupService.getMemberCount(1L)).isEqualTo(3);
    }

    @Test
    void addMember_groupNotFound_throwsException() {
        when(userGroupMapper.selectById(9L)).thenReturn(null);

        assertThatThrownBy(() -> userGroupService.addMember(9L, 5L))
                .isInstanceOf(BizException.class)
                .hasMessage("用户组不存在或已删除");
    }

    @Test
    void addMember_groupDeleted_throwsException() {
        UserGroup group = new UserGroup();
        group.setStatus(-1);
        when(userGroupMapper.selectById(1L)).thenReturn(group);

        assertThatThrownBy(() -> userGroupService.addMember(1L, 5L))
                .isInstanceOf(BizException.class)
                .hasMessage("用户组不存在或已删除");
    }

    @Test
    void addMember_userNotFound_throwsException() {
        UserGroup group = new UserGroup();
        group.setStatus(0);
        when(userGroupMapper.selectById(1L)).thenReturn(group);
        when(userService.getById(5L)).thenReturn(null);

        assertThatThrownBy(() -> userGroupService.addMember(1L, 5L))
                .isInstanceOf(BizException.class)
                .hasMessage("用户不存在");
    }

    @Test
    void addMember_alreadyInGroup_throwsException() {
        UserGroup group = new UserGroup();
        group.setStatus(0);
        when(userGroupMapper.selectById(1L)).thenReturn(group);
        when(userService.getById(5L)).thenReturn(new User());
        when(userGroupMemberMapper.selectOne(any())).thenReturn(new UserGroupMember());

        assertThatThrownBy(() -> userGroupService.addMember(1L, 5L))
                .isInstanceOf(BizException.class)
                .hasMessage("用户已在该用户组中");
    }

    @Test
    void addMember_inOtherGroup_removesOldRelationFirst() {
        UserGroup group = new UserGroup();
        group.setStatus(0);
        when(userGroupMapper.selectById(1L)).thenReturn(group);
        when(userService.getById(5L)).thenReturn(new User());
        UserGroupMember currentRelation = new UserGroupMember();
        currentRelation.setId(7L);
        // 第一次 selectOne 查目标组关系返回 null，第二次查用户当前关系返回旧关系
        when(userGroupMemberMapper.selectOne(any())).thenReturn(null, currentRelation);

        userGroupService.addMember(1L, 5L);

        verify(userGroupMemberMapper).deleteById(7L);
        verify(userGroupMemberMapper).insert(any(UserGroupMember.class));
    }

    @Test
    void getByUser_noRelations_returnsEmptyList() {
        when(userGroupMemberMapper.selectList(any())).thenReturn(List.of());

        assertThat(userGroupService.getByUser(5L)).isEmpty();
    }

    @Test
    void getByUser_queriesByGroupIds() {
        UserGroupMember relation = new UserGroupMember();
        relation.setUserGroupId(3L);
        when(userGroupMemberMapper.selectList(any())).thenReturn(List.of(relation));
        List<UserGroup> groups = List.of(new UserGroup());
        when(userGroupMapper.selectByIds(any())).thenReturn(groups);

        assertThat(userGroupService.getByUser(5L)).isSameAs(groups);
    }

    @Test
    void removeMember_relationNotFound_throwsException() {
        when(userGroupMemberMapper.selectOne(any())).thenReturn(null);

        assertThatThrownBy(() -> userGroupService.removeMember(1L, 5L))
                .isInstanceOf(BizException.class)
                .hasMessage("用户不在该用户组中");
    }

    @Test
    void removeMember_deletesByRelationId() {
        UserGroupMember relation = new UserGroupMember();
        relation.setId(7L);
        when(userGroupMemberMapper.selectOne(any())).thenReturn(relation);

        userGroupService.removeMember(1L, 5L);

        verify(userGroupMemberMapper).deleteById(7L);
    }
}
