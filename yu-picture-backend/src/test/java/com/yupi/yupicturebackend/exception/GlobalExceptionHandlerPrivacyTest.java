package com.yupi.yupicturebackend.exception;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import cn.dev33.satoken.exception.NotLoginException;
import com.yupi.yupicturebackend.common.BaseResponse;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import static org.junit.jupiter.api.Assertions.*;

class GlobalExceptionHandlerPrivacyTest {
    @Test
    void rejectedTokenDoesNotReachResponseOrLogs() {
        String submittedToken = "synthetic-rejected-session-token";
        NotLoginException exception = NotLoginException.newInstance("space",
                NotLoginException.INVALID_TOKEN, "Invalid token", submittedToken);
        assertTrue(exception.getMessage().contains(submittedToken),
                "The upstream exception must contain the token to exercise the leak");

        Logger logger = (Logger) LoggerFactory.getLogger(GlobalExceptionHandler.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            BaseResponse<?> response = new GlobalExceptionHandler().notLoginException(exception);
            assertAll(
                    () -> assertEquals(ErrorCode.NOT_LOGIN_ERROR.getCode(), response.getCode()),
                    () -> assertEquals(ErrorCode.NOT_LOGIN_ERROR.getMessage(), response.getMessage()),
                    () -> assertTrue(appender.list.stream().noneMatch(event ->
                            event.getFormattedMessage().contains(submittedToken)
                                    || event.getThrowableProxy() != null),
                            "Authentication logs must not retain token-bearing exceptions")
            );
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }
    }
}
