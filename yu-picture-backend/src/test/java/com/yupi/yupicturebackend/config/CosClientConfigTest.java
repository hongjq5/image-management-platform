package com.yupi.yupicturebackend.config;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CosClientConfigTest {
    @Test
    void missingCredentialsGiveActionableErrorWithoutLeakingSecrets() {
        CosClientConfig config = new CosClientConfig();
        config.setSecretKey("never-print-this-value");
        IllegalStateException error = assertThrows(IllegalStateException.class, config::cosClient);
        assertTrue(error.getMessage().contains("COS_SECRET_ID"));
        assertFalse(error.getMessage().contains("never-print-this-value"));
    }
}
