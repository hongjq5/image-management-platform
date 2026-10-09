package com.yupi.yupicturebackend.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yupi.yupicturebackend.model.entity.User;
import com.yupi.yupicturebackend.service.impl.UserServiceImpl;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class UserViewPrivacyTest {
    @Test
    void publicUserViewExcludesRedemptionCodeAndPassword() {
        User user = new User();
        user.setId(7L);
        user.setUserName("Example member");
        user.setUserPassword("synthetic-stored-password-hash");
        user.setVipCode("synthetic-redemption-code");

        JsonNode json = new ObjectMapper().valueToTree(new UserServiceImpl().getUserVO(user));
        assertAll(
                () -> assertEquals("Example member", json.get("userName").asText()),
                () -> assertFalse(json.has("vipCode"), "Public user profiles must omit redemption codes"),
                () -> assertFalse(json.has("userPassword"), "Public user profiles must omit password hashes"),
                () -> assertEquals("synthetic-redemption-code", user.getVipCode()),
                () -> assertEquals("synthetic-stored-password-hash", user.getUserPassword())
        );
    }
}
