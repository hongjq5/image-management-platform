package com.yupi.yupicturebackend.config;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import java.nio.charset.StandardCharsets;
import static org.junit.jupiter.api.Assertions.*;

class HttpRequestWrapperFilterTest {
    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void rejectsOversizedJsonBeforeAuthorizationBuffersIt(boolean knownLength) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest() {
            @Override public long getContentLengthLong() {
                return knownLength ? super.getContentLengthLong() : -1;
            }
            @Override public int getContentLength() {
                return knownLength ? super.getContentLength() : -1;
            }
        };
        request.setContentType("application/json");
        request.setContent(new byte[1024 * 1024 + 1]);
        MockHttpServletResponse response = new MockHttpServletResponse();
        new HttpRequestWrapperFilter().doFilter(request, response,
                (req, res) -> fail("Oversized JSON reached application handlers"));
        assertEquals(413, response.getStatus());
    }

    @ParameterizedTest
    @ValueSource(strings = {"application/json", "application/json; charset=UTF-8", "application/problem+json"})
    void authorizationAndControllerCanReadSameUtf8Json(String contentType) throws Exception {
        String json = "{\"spaceId\":123,\"spaceName\":\"测试空间\"}";
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setContentType(contentType);
        request.setContent(json.getBytes(StandardCharsets.UTF_8));
        new HttpRequestWrapperFilter().doFilter(request, new MockHttpServletResponse(), (wrapped, response) -> {
            assertEquals(json, new String(wrapped.getInputStream().readAllBytes(), StandardCharsets.UTF_8));
            assertEquals(json, new String(wrapped.getInputStream().readAllBytes(), StandardCharsets.UTF_8));
        });
    }
}
