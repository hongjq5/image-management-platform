package com.yupi.yupicturebackend.manager.auth;

import com.yupi.yupicturebackend.constant.UserConstant;
import com.yupi.yupicturebackend.exception.BusinessException;
import com.yupi.yupicturebackend.model.entity.*;
import com.yupi.yupicturebackend.service.*;
import org.junit.jupiter.api.*;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import java.nio.charset.StandardCharsets;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class StpInterfaceSecurityTest {
    private final UserService users = mock(UserService.class);
    private final SpaceService spaces = mock(SpaceService.class);
    private final PictureService pictures = mock(PictureService.class);
    private final SpaceUserService members = mock(SpaceUserService.class);
    private final StpInterfaceImpl permissions = new StpInterfaceImpl();
    private final User user = new User();

    @BeforeEach void setUp() {
        user.setId(7L); user.setUserRole("user");
        when(users.getLoginUser(any())).thenReturn(user);
        StpKit.SPACE.getSessionByLoginId(7L).set(UserConstant.USER_LOGIN_STATE, user);
        ReflectionTestUtils.setField(permissions, "userService", users);
        ReflectionTestUtils.setField(permissions, "spaceService", spaces);
        ReflectionTestUtils.setField(permissions, "pictureService", pictures);
        ReflectionTestUtils.setField(permissions, "spaceUserService", members);
        SpaceUserAuthManager manager = new SpaceUserAuthManager();
        ReflectionTestUtils.setField(manager, "userService", users);
        ReflectionTestUtils.setField(manager, "spaceUserService", members);
        ReflectionTestUtils.setField(permissions, "spaceUserAuthManager", manager);
        Space foreign = new Space();
        foreign.setId(22L); foreign.setUserId(99L); foreign.setSpaceType(0);
        when(spaces.getById(22L)).thenReturn(foreign);
        Space own = new Space();
        own.setId(11L); own.setUserId(7L); own.setSpaceType(0);
        when(spaces.getById(11L)).thenReturn(own);
    }

    @AfterEach void cleanUp() { RequestContextHolder.resetRequestAttributes(); }

    private List<String> request(String path, String contentType, String json) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api" + path);
        request.setContextPath("/api"); request.setContentType(contentType);
        request.setContent(json.getBytes(StandardCharsets.UTF_8));
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
        return permissions.getPermissionList(7L, "space");
    }

    @Test void forgedNestedAdminDoesNotGrantForeignSpaceAccess() {
        assertTrue(request("/picture/edit", "application/json",
                "{\"spaceId\":22,\"spaceUser\":{\"spaceRole\":\"admin\"}}").isEmpty());
    }

    @Test void charsetJsonCannotSkipForeignSpaceAuthorization() {
        assertTrue(request("/picture/list/page/vo", "application/json;charset=UTF-8", "{\"spaceId\":22}").isEmpty());
    }

    @Test void emptyMemberListRequestIsDenied() {
        assertTrue(request("/spaceUser/list", "application/json", "{}").isEmpty());
    }

    @Test void publicNewUploadGrantsOnlyUpload() {
        assertEquals(List.of("picture:upload"), request("/picture/upload/url", "application/json", "{}"));
    }

    @Test void publicUploadAcceptsExplicitNullIds() {
        assertEquals(List.of("picture:upload"), request("/picture/upload/url", "application/json;charset=UTF-8",
                "{\"id\":null,\"spaceId\":null}"));
    }

    @Test void mismatchedLoginSystemsDoNotGrantSpaceAccess() {
        when(users.getLoginUser(any())).thenAnswer(invocation -> { User other = new User(); other.setId(8L); return other; });
        assertThrows(BusinessException.class, () -> request("/picture/upload/url", "application/json", "{}"));
    }

    @Test void legitimateOwnerRetainsPrivateSpacePermissions() {
        assertTrue(request("/picture/upload/url", "application/json;charset=UTF-8", "{\"spaceId\":11}")
                .containsAll(List.of("picture:upload", "picture:edit", "picture:view")));
    }

    @Test void requestedSpaceMustMatchStoredPicture() {
        Picture picture = new Picture(); picture.setId(44L); picture.setSpaceId(22L); picture.setUserId(99L);
        when(pictures.getById(44L)).thenReturn(picture);
        assertThrows(BusinessException.class, () -> request("/picture/edit", "application/json", "{\"id\":44,\"spaceId\":11}"));
    }

    @Test void conflictingPictureAliasesAreRejected() {
        assertThrows(BusinessException.class, () -> request("/picture/edit", "application/json", "{\"id\":44,\"pictureId\":45,\"spaceId\":11}"));
    }

    @Test void storedMemberSpaceCannotBeOverridden() {
        SpaceUser member = new SpaceUser(); member.setId(55L); member.setSpaceId(22L); member.setUserId(99L);
        when(members.getById(55L)).thenReturn(member);
        assertThrows(BusinessException.class, () -> request("/spaceUser/edit", "application/json",
                "{\"id\":55,\"spaceId\":11,\"spaceUser\":{\"spaceRole\":\"admin\"}}"));
    }
}
