package com.yupi.yupicturebackend.manager.websocket;

import com.yupi.yupicturebackend.exception.ErrorCode;
import com.yupi.yupicturebackend.exception.BusinessException;
import com.yupi.yupicturebackend.manager.auth.SpaceUserAuthManager;
import com.yupi.yupicturebackend.manager.auth.model.SpaceUserPermissionConstant;
import com.yupi.yupicturebackend.model.entity.Picture;
import com.yupi.yupicturebackend.model.entity.Space;
import com.yupi.yupicturebackend.model.entity.User;
import com.yupi.yupicturebackend.model.enums.SpaceTypeEnum;
import com.yupi.yupicturebackend.service.PictureService;
import com.yupi.yupicturebackend.service.SpaceService;
import com.yupi.yupicturebackend.service.UserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.socket.WebSocketHandler;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class WsHandshakeInterceptorTest {
    private final WsHandshakeInterceptor interceptor = new WsHandshakeInterceptor();
    private final UserService users = mock(UserService.class);
    private final PictureService pictures = mock(PictureService.class);
    private final SpaceService spaces = mock(SpaceService.class);
    private final SpaceUserAuthManager permissions = mock(SpaceUserAuthManager.class);
    private final MockHttpServletRequest request = new MockHttpServletRequest();
    private final Map<String, Object> attributes = new HashMap<>();
    private final Picture picture = new Picture();
    private final Space space = new Space();

    @BeforeEach
    void setup() {
        ReflectionTestUtils.setField(interceptor, "userService", users);
        ReflectionTestUtils.setField(interceptor, "pictureService", pictures);
        ReflectionTestUtils.setField(interceptor, "spaceService", spaces);
        ReflectionTestUtils.setField(interceptor, "spaceUserAuthManager", permissions);
        request.setParameter("pictureId", "12");
        request.getSession();
        User user = new User();
        user.setId(7L);
        when(users.getLoginUser(request)).thenReturn(user);
        picture.setId(12L);
        picture.setSpaceId(21L);
        space.setId(21L);
        space.setSpaceType(SpaceTypeEnum.TEAM.getValue());
        when(pictures.getById(any())).thenReturn(picture);
        when(spaces.getById(21L)).thenReturn(space);
        when(permissions.getPermissionList(any(), any())).thenReturn(List.of(SpaceUserPermissionConstant.PICTURE_EDIT));
    }

    @Test
    void teamEditorHandshakeKeepsSessionIdentityForLaterRevalidation() throws Exception {
        assertTrue(handshake());
        assertEquals(12L, attributes.get("pictureId"));
        assertEquals(7L, attributes.get("userId"));
        assertEquals(request.getSession().getId(), attributes.get("httpSessionId"));
    }

    @Test
    void publicImageIsRejectedEvenIfPermissionManagerGrantsEdit() throws Exception {
        picture.setSpaceId(null);
        assertFalse(handshake());
        assertTrue(attributes.isEmpty());
    }

    @Test
    void privateImageIsRejected() throws Exception {
        space.setSpaceType(SpaceTypeEnum.PRIVATE.getValue());
        assertFalse(handshake());
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "abc", "0", "-1", "9223372036854775808", "12.5"})
    void malformedPictureIdsAreRejectedWithoutThrowing(String id) {
        request.removeParameter("pictureId");
        if (id != null) request.setParameter("pictureId", id);
        assertFalse(assertDoesNotThrow(this::handshake));
        assertTrue(attributes.isEmpty());
    }

    @Test
    void unauthenticatedRequestIsRejectedWithoutThrowing() {
        when(users.getLoginUser(request)).thenThrow(new BusinessException(ErrorCode.NOT_LOGIN_ERROR));
        assertFalse(assertDoesNotThrow(this::handshake));
    }

    @Test
    void unknownSpaceTypeIsRejectedWithoutThrowing() {
        space.setSpaceType(null);
        assertFalse(assertDoesNotThrow(this::handshake));
    }

    @Test
    void missingPictureOrSpaceAndViewerAreRejected() throws Exception {
        when(pictures.getById(any())).thenReturn(null);
        assertFalse(handshake());
        when(pictures.getById(any())).thenReturn(picture);
        when(spaces.getById(21L)).thenReturn(null);
        assertFalse(handshake());
        when(spaces.getById(21L)).thenReturn(space);
        when(permissions.getPermissionList(any(), any())).thenReturn(List.of());
        assertFalse(handshake());
    }

    @Test
    void requestsWithoutServletAuthenticationAreRejected() throws Exception {
        assertFalse(interceptor.beforeHandshake(mock(ServerHttpRequest.class),
                mock(ServerHttpResponse.class), mock(WebSocketHandler.class), attributes));
    }

    private boolean handshake() throws Exception {
        return interceptor.beforeHandshake(new ServletServerHttpRequest(request),
                mock(ServerHttpResponse.class), mock(WebSocketHandler.class), attributes);
    }
}
