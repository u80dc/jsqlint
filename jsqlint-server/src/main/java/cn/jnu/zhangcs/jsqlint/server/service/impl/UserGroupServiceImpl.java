package cn.jnu.zhangcs.jsqlint.server.service.impl;

import cn.jnu.zhangcs.jsqlint.server.mapper.UserGroupMapper;
import cn.jnu.zhangcs.jsqlint.server.mapper.UserGroupMemberMapper;
import cn.jnu.zhangcs.jsqlint.server.service.UserGroupService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Slf4j
@RequiredArgsConstructor
@Service
public class UserGroupServiceImpl implements UserGroupService {

    private final UserGroupMapper groupMapper;

    private final UserGroupMemberMapper memberMapper;


}
