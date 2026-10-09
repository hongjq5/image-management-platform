package com.yupi.yupicturebackend.manager;

import com.qcloud.cos.COSClient;
import com.yupi.yupicturebackend.config.CosClientConfig;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import static org.mockito.Mockito.*;
import static org.junit.jupiter.api.Assertions.*;
import com.yupi.yupicturebackend.exception.BusinessException;
import com.qcloud.cos.model.PutObjectRequest;
import org.mockito.ArgumentCaptor;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.nio.file.Files;

class CosStorageTest {
    @TempDir Path temp;

    @Test
    void refusesForeignOriginsAndPreservesDerivativeDirectory() throws Exception {
        CosManager manager = new CosManager();
        COSClient client = mock(COSClient.class);
        CosClientConfig config = new CosClientConfig();
        config.setHost("https://bucket.example"); config.setBucket("bucket");
        ReflectionTestUtils.setField(manager, "cosClient", client);
        ReflectionTestUtils.setField(manager, "cosClientConfig", config);
        assertThrows(BusinessException.class, () -> manager.deleteObject("https://evil.example/space/9/image.webp"));
        assertThrows(BusinessException.class, () -> manager.deleteObject("https://bucket.example/space/../secret"));
        verifyNoInteractions(client);
        Path file = temp.resolve("source.png"); Files.write(file, new byte[22000]);
        manager.putPictureObject("space/9/image.png", file.toFile());
        ArgumentCaptor<PutObjectRequest> request = ArgumentCaptor.forClass(PutObjectRequest.class);
        verify(client).putObject(request.capture());
        assertEquals("space/9/image.webp", request.getValue().getPicOperations().getRules().get(0).getFileId());
        assertEquals("space/9/image_thumbnail.png", request.getValue().getPicOperations().getRules().get(1).getFileId());
    }
    @Test
    void deletionUsesOwnedObjectKeyInsteadOfWholeUrl() {
        CosManager manager = new CosManager();
        COSClient client = mock(COSClient.class);
        CosClientConfig config = new CosClientConfig();
        config.setHost("https://bucket.example"); config.setBucket("bucket");
        ReflectionTestUtils.setField(manager, "cosClient", client);
        ReflectionTestUtils.setField(manager, "cosClientConfig", config);
        manager.deleteObject("https://bucket.example/space/9/image.webp");
        verify(client).deleteObject("bucket", "space/9/image.webp");
    }
}
