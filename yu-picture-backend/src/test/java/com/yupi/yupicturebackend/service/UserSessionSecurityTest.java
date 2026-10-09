package com.yupi.yupicturebackend.service;

import cn.dev33.satoken.SaManager;
import cn.dev33.satoken.context.SaTokenContext;
import cn.dev33.satoken.spring.SaTokenContextForSpring;
import com.yupi.yupicturebackend.constant.UserConstant;
import com.yupi.yupicturebackend.exception.BusinessException;
import com.yupi.yupicturebackend.manager.auth.StpKit;
import com.yupi.yupicturebackend.model.dto.user.UserQueryRequest;
import com.yupi.yupicturebackend.model.entity.User;
import com.yupi.yupicturebackend.service.impl.UserServiceImpl;
import com.yupi.yupicturebackend.mapper.UserMapper;
import com.yupi.yupicturebackend.utils.PasswordUtils;
import com.yupi.yupicturebackend.exception.ErrorCode;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.util.DigestUtils;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.any;
import org.junit.jupiter.api.*;
import org.springframework.mock.web.*;
import org.springframework.web.context.request.*;
import static org.junit.jupiter.api.Assertions.*;

class UserSessionSecurityTest {
    private final UserServiceImpl service = new UserServiceImpl();
    private SaTokenContext previousContext;
    private MockHttpServletRequest request;

    @BeforeEach void setUp() {
        previousContext = SaManager.getSaTokenContext();
        SaManager.setSaTokenContext(new SaTokenContextForSpring());
        request = new MockHttpServletRequest();
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request, new MockHttpServletResponse()));
    }

    @AfterEach void cleanUp() {
        StpKit.SPACE.logout(7L);
        RequestContextHolder.resetRequestAttributes();
        SaManager.setSaTokenContext(previousContext);
    }

    @Test void logoutRevokesSpaceTokenAndSpringSession() {
        User user = new User(); user.setId(7L);
        request.getSession().setAttribute(UserConstant.USER_LOGIN_STATE, user);
        StpKit.SPACE.login(7L);
        String token = StpKit.SPACE.getTokenValue();
        assertEquals("7", String.valueOf(StpKit.SPACE.getLoginIdByToken(token)));
        assertTrue(service.userLogout(request));
        assertNull(StpKit.SPACE.getLoginIdByToken(token));
        assertTrue(request.getSession(false) == null || request.getSession(false).getAttribute(UserConstant.USER_LOGIN_STATE) == null);
    }

    @Test void sortingRejectsSqlExpressions() {
        UserQueryRequest query = new UserQueryRequest(); query.setSortField("id, (select sleep(5))");
        assertThrows(BusinessException.class, () -> service.getQueryWrapper(query));
    }

    @Test void logoutStillRevokesTokenWhenSpringSessionHasExpired() {
        StpKit.SPACE.login(7L);
        String token = StpKit.SPACE.getTokenValue();
        assertTrue(service.userLogout(request));
        assertNull(StpKit.SPACE.getLoginIdByToken(token));
    }

    @Test void storedLegacyPasswordIsUpgradedAfterSuccessfulLogin() {
        UserMapper mapper = mock(UserMapper.class);
        ReflectionTestUtils.setField(service, "baseMapper", mapper);
        User user = new User(); user.setId(7L); user.setUserAccount("legacy");
        user.setUserPassword(DigestUtils.md5DigestAsHex("yupicorrect-password".getBytes()));
        when(mapper.selectOne(any())).thenReturn(user);
        when(mapper.update(any(), any())).thenReturn(1);
        service.userLogin("legacy", "correct-password", request);
        ArgumentCaptor<User> update = ArgumentCaptor.forClass(User.class);
        verify(mapper).update(update.capture(), any());
        assertTrue(PasswordUtils.matches("correct-password", update.getValue().getUserPassword()));
        assertFalse(PasswordUtils.needsRehash(update.getValue().getUserPassword()));
    }

    @Test void wrongPasswordNeverCreatesSessionOrRehashesStoredPassword() {
        UserMapper mapper = mock(UserMapper.class);
        ReflectionTestUtils.setField(service, "baseMapper", mapper);
        User user = new User(); user.setId(7L); user.setUserAccount("legacy");
        user.setUserPassword(DigestUtils.md5DigestAsHex("yupicorrect-password".getBytes()));
        when(mapper.selectOne(any())).thenReturn(user);
        assertThrows(BusinessException.class, () -> service.userLogin("legacy", "incorrect-password", request));
        assertNull(request.getSession(false));
        verify(mapper, never()).update(any(), any());
    }

    @Test void vipDemoIsDisabledBeforeAnyFileOrDatabaseAccess() {
        User user = new User(); user.setId(7L);
        BusinessException failure = assertThrows(BusinessException.class, () -> service.exchangeVip(user, "demo-code"));
        assertEquals(ErrorCode.FORBIDDEN_ERROR.getCode(), failure.getCode());
    }
}
