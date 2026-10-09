package com.yupi.yupicturebackend.controller;

import com.yupi.yupicturebackend.exception.BusinessException;
import com.yupi.yupicturebackend.model.dto.user.UserAddRequest;
import com.yupi.yupicturebackend.model.entity.User;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yupi.yupicturebackend.service.UserService;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.any;

class UserControllerSecurityTest {
    @Test void adminUserDetailDoesNotReturnPasswordOrMutateStoredUser() throws Exception {
        User stored = new User();
        stored.setId(7L);
        stored.setUserName("Example member");
        stored.setUserPassword("synthetic-stored-password-hash");
        stored.setVipCode("synthetic-redemption-code");
        UserService users = mock(UserService.class);
        when(users.getById(7L)).thenReturn(stored);
        UserController controller = new UserController();
        ReflectionTestUtils.setField(controller, "userService", users);

        User response = controller.getUserById(7L).getData();
        String json = new ObjectMapper().writeValueAsString(response);
        assertAll(
                () -> assertNull(response.getUserPassword()),
                () -> assertNull(response.getVipCode()),
                () -> assertFalse(json.contains("synthetic-stored-password-hash")),
                () -> assertFalse(json.contains("synthetic-redemption-code")),
                () -> assertEquals("Example member", response.getUserName()),
                () -> assertNotSame(stored, response),
                () -> assertEquals("synthetic-stored-password-hash", stored.getUserPassword()),
                () -> assertEquals("synthetic-redemption-code", stored.getVipCode())
        );
    }

    @Test void defaultPasswordProvisioningIsDisabledWithoutCreatingAnAccount() {
        UserService users = mock(UserService.class);
        when(users.save(any())).thenReturn(true);
        UserController controller = new UserController();
        ReflectionTestUtils.setField(controller, "userService", users);
        UserAddRequest input = new UserAddRequest(); input.setUserAccount("new-user");
        BusinessException error = assertThrows(BusinessException.class, () -> controller.addUser(input));
        assertEquals(40300, error.getCode());
        verify(users, never()).save(any());
    }
}
