package com.yupi.yupicturebackend.controller;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.yupi.yupicturebackend.common.DeleteRequest;
import com.yupi.yupicturebackend.exception.BusinessException;
import com.yupi.yupicturebackend.manager.auth.SpaceUserAuthManager;
import com.yupi.yupicturebackend.model.dto.space.SpaceQueryRequest;
import com.yupi.yupicturebackend.model.entity.*;
import com.yupi.yupicturebackend.model.vo.SpaceVO;
import com.yupi.yupicturebackend.service.*;
import org.junit.jupiter.api.*;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.Collections;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.any;

class SpaceControllerSecurityTest {
    private final SpaceController controller = new SpaceController();
    private final SpaceService spaces = mock(SpaceService.class);
    private final PictureService pictures = mock(PictureService.class);
    private final SpaceUserService members = mock(SpaceUserService.class);
    private final UserService users = mock(UserService.class);
    private final SpaceUserAuthManager permissions = mock(SpaceUserAuthManager.class);
    private final MockHttpServletRequest request = new MockHttpServletRequest();
    private final Space space = new Space();

    @BeforeEach void setUp() {
        User user = new User(); user.setId(7L); user.setUserRole("user");
        space.setId(11L); space.setUserId(99L); space.setSpaceType(0);
        when(users.getLoginUser(any())).thenReturn(user);
        when(spaces.getById(11L)).thenReturn(space);
        when(spaces.getOne(any())).thenReturn(space);
        when(spaces.getSpaceVO(any(), any())).thenReturn(new SpaceVO());
        when(spaces.removeById(11L)).thenReturn(true);
        when(spaces.getQueryWrapper(any())).thenReturn(new QueryWrapper<>());
        when(spaces.page(any(Page.class), any())).thenReturn(new Page<Space>());
        when(spaces.getSpaceVOPage(any(), any())).thenReturn(new Page<SpaceVO>());
        when(permissions.getPermissionList(any(), any())).thenReturn(Collections.emptyList());
        ReflectionTestUtils.setField(controller, "spaceService", spaces);
        ReflectionTestUtils.setField(controller, "userService", users);
        ReflectionTestUtils.setField(controller, "spaceUserAuthManager", permissions);
        ReflectionTestUtils.setField(controller, "pictureService", pictures);
        ReflectionTestUtils.setField(controller, "spaceUserService", members);
    }

    @Test void strangersCannotReadPrivateSpaceMetadata() {
        assertThrows(BusinessException.class, () -> controller.getSpaceVOById(11L, request));
    }

    @Test void ordinaryListIsAlwaysScopedToLoggedInOwner() {
        SpaceQueryRequest query = new SpaceQueryRequest(); query.setUserId(99L);
        controller.listSpaceVOByPage(query, request);
        assertEquals(7L, query.getUserId());
    }

    @Test void unsafeSpaceSortExpressionIsRejected() {
        SpaceQueryRequest query = new SpaceQueryRequest(); query.setSortField("id, sleep(3)");
        assertThrows(BusinessException.class, () -> controller.listSpaceVOByPage(query, request));
    }

    @Test void nonEmptySpaceCannotBeDeletedLeavingOrphanPictures() {
        space.setTotalCount(1L);
        DeleteRequest delete = new DeleteRequest(); delete.setId(11L);
        assertThrows(BusinessException.class, () -> controller.deleteSpace(delete, request));
        verify(spaces, never()).removeById(11L);
    }
}
