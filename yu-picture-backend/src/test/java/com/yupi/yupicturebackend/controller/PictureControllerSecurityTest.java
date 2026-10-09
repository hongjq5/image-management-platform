package com.yupi.yupicturebackend.controller;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.yupi.yupicturebackend.exception.BusinessException;
import com.yupi.yupicturebackend.manager.auth.SpaceUserAuthManager;
import com.yupi.yupicturebackend.model.dto.picture.*;
import com.yupi.yupicturebackend.model.entity.*;
import com.yupi.yupicturebackend.model.vo.PictureVO;
import com.yupi.yupicturebackend.service.*;
import org.junit.jupiter.api.*;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.Collections;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class PictureControllerSecurityTest {
    private final PictureController controller = new PictureController();
    private final PictureService pictures = mock(PictureService.class);
    private final UserService users = mock(UserService.class);
    private final SpaceService spaces = mock(SpaceService.class);
    private final SpaceUserAuthManager permissions = mock(SpaceUserAuthManager.class);
    private final MockHttpServletRequest request = new MockHttpServletRequest();
    private final User user = new User();

    @BeforeEach void setUp() {
        user.setId(7L); user.setUserRole("user");
        when(users.getLoginUser(any())).thenReturn(user);
        ReflectionTestUtils.setField(controller, "pictureService", pictures);
        ReflectionTestUtils.setField(controller, "userService", users);
        ReflectionTestUtils.setField(controller, "spaceService", spaces);
        ReflectionTestUtils.setField(controller, "spaceUserAuthManager", permissions);
        when(pictures.getQueryWrapper(any())).thenReturn(new QueryWrapper<>());
        when(pictures.page(any(Page.class), any())).thenReturn(new Page<Picture>());
        when(pictures.getPictureVOPage(any(), any())).thenReturn(new Page<PictureVO>());
        when(pictures.getPictureVO(any(), any())).thenReturn(new PictureVO());
        when(permissions.getPermissionList(any(), any())).thenReturn(Collections.emptyList());
    }

    @Test void cacheListAlwaysRestrictsToApprovedPublicPictures() {
        PictureQueryRequest query = new PictureQueryRequest(); query.setReviewStatus(0);
        controller.listPictureVOByPageWithCache(query, request);
        assertTrue(query.isNullSpaceId());
        assertEquals(1, query.getReviewStatus());
    }

    @Test void cacheListRejectsForeignPrivateSpaceBeforeQuery() {
        Space space = new Space(); space.setId(22L); space.setUserId(99L); space.setSpaceType(0);
        when(spaces.getById(22L)).thenReturn(space);
        PictureQueryRequest query = new PictureQueryRequest(); query.setSpaceId(22L);
        assertThrows(BusinessException.class, () -> controller.listPictureVOByPageWithCache(query, request));
        verify(pictures, never()).page(any(Page.class), any());
    }

    @Test void rejectedPublicPictureCannotBeViewedByStranger() {
        Picture picture = new Picture(); picture.setId(44L); picture.setUserId(99L); picture.setReviewStatus(2);
        when(pictures.getById(44L)).thenReturn(picture);
        assertThrows(BusinessException.class, () -> controller.getPictureVOById(44L, request));
    }

    @Test void invalidPageSizeCannotDisablePagination() {
        PictureQueryRequest query = new PictureQueryRequest(); query.setPageSize(-1);
        assertThrows(BusinessException.class, () -> controller.listPictureVOByPage(query, request));
    }

    @Test void disabledAiRoutesNeverCallExternalProviders() {
        BusinessException create = assertThrows(BusinessException.class,
                () -> controller.createPictureOutPaintingTask(null, request));
        BusinessException get = assertThrows(BusinessException.class,
                () -> controller.getPictureOutPaintingTask("task-id"));
        assertEquals(40300, create.getCode());
        assertEquals(40300, get.getCode());
        verify(pictures, never()).createPictureOutPaintingTask(any(), any());
    }

    @Test void privateReverseSearchDoesNotDiscloseUrlToThirdParty() {
        Picture picture = new Picture(); picture.setId(44L); picture.setSpaceId(22L); picture.setUserId(7L);
        picture.setReviewStatus(1);
        when(pictures.getById(44L)).thenReturn(picture);
        Space space = new Space(); space.setId(22L); space.setUserId(7L); space.setSpaceType(0);
        when(spaces.getById(22L)).thenReturn(space);
        when(permissions.getPermissionList(space, user)).thenReturn(java.util.List.of("picture:view"));
        SearchPictureByPictureRequest query = new SearchPictureByPictureRequest(); query.setPictureId(44L);
        assertThrows(BusinessException.class, () -> controller.searchPictureByPicture(query, request));
    }

    @Test void approvedPublicDetailsRemainAvailableWithoutLogin() {
        Picture picture = new Picture(); picture.setId(44L); picture.setUserId(99L); picture.setReviewStatus(1);
        when(pictures.getById(44L)).thenReturn(picture);
        when(users.getLoginUser(any())).thenThrow(new BusinessException(com.yupi.yupicturebackend.exception.ErrorCode.NOT_LOGIN_ERROR));
        assertEquals(java.util.List.of("picture:view"), controller.getPictureVOById(44L, request).getData().getPermissionList());
    }
}
