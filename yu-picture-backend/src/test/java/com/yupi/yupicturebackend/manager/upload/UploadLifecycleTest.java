package com.yupi.yupicturebackend.manager.upload;

import com.qcloud.cos.COSClient;
import com.qcloud.cos.model.PutObjectRequest;
import com.qcloud.cos.model.PutObjectResult;
import com.yupi.yupicturebackend.config.CosClientConfig;
import com.yupi.yupicturebackend.exception.BusinessException;
import com.yupi.yupicturebackend.manager.CosManager;
import com.yupi.yupicturebackend.model.dto.file.UploadPictureResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.util.ReflectionTestUtils;
import org.mockito.ArgumentCaptor;
import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.Collections;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class UploadLifecycleTest {
    private FilePictureUpload upload;
    private COSClient client;
    private MockMultipartFile file;

    @BeforeEach
    void setup() throws Exception {
        CosClientConfig config = new CosClientConfig();
        config.setHost("https://bucket.example"); config.setBucket("bucket");
        client = mock(COSClient.class);
        CosManager cos = new CosManager();
        ReflectionTestUtils.setField(cos, "cosClientConfig", config);
        ReflectionTestUtils.setField(cos, "cosClient", client);
        upload = new FilePictureUpload();
        ReflectionTestUtils.setField(upload, "cosClientConfig", config);
        ReflectionTestUtils.setField(upload, "cosManager", cos);
        ByteArrayOutputStream image = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(1, 1, BufferedImage.TYPE_INT_RGB), "png", image);
        file = new MockMultipartFile("file", "image.PNG", "image/png", image.toByteArray());
    }

    @Test
    void unprocessedImageUsesNewUrlAsThumbnailAndCanonicalKey() {
        PutObjectResult result = mock(PutObjectResult.class, RETURNS_DEEP_STUBS);
        when(result.getCiUploadResult().getOriginalInfo().getImageInfo().getWidth()).thenReturn(1);
        when(result.getCiUploadResult().getOriginalInfo().getImageInfo().getHeight()).thenReturn(1);
        when(result.getCiUploadResult().getOriginalInfo().getImageInfo().getFormat()).thenReturn("png");
        when(result.getCiUploadResult().getProcessResults().getObjectList()).thenReturn(Collections.emptyList());
        when(client.putObject(any(PutObjectRequest.class))).thenReturn(result);
        UploadPictureResult picture = upload.uploadPicture(file, "space/9");
        assertEquals(picture.getUrl(), picture.getThumbnailUrl());
        assertTrue(picture.getUrl().startsWith("https://bucket.example/space/9/"));
        ArgumentCaptor<PutObjectRequest> request = ArgumentCaptor.forClass(PutObjectRequest.class);
        verify(client).putObject(request.capture());
        assertFalse(request.getValue().getKey().startsWith("/"));
        assertFalse(request.getValue().getFile().exists(), "temporary upload file is removed");
        verify(client, never()).deleteObject(anyString(), anyString());
    }

    @Test
    void failedCloudDecodeCleansOnlyGeneratedObjects() {
        when(client.putObject(any(PutObjectRequest.class))).thenThrow(new IllegalStateException("decode failed"));
        assertThrows(BusinessException.class, () -> upload.uploadPicture(file, "space/9"));
        ArgumentCaptor<PutObjectRequest> request = ArgumentCaptor.forClass(PutObjectRequest.class);
        verify(client).putObject(request.capture());
        String key = request.getValue().getKey();
        String stem = key.substring(0, key.lastIndexOf('.'));
        verify(client).deleteObject("bucket", key);
        verify(client).deleteObject("bucket", stem + ".webp");
        verify(client).deleteObject("bucket", stem + "_thumbnail.png");
        assertFalse(request.getValue().getFile().exists());
    }
}
