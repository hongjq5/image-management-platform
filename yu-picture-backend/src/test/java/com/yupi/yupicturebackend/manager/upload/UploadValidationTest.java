package com.yupi.yupicturebackend.manager.upload;

import com.yupi.yupicturebackend.exception.BusinessException;
import com.yupi.yupicturebackend.exception.ErrorCode;
import com.yupi.yupicturebackend.manager.CosManager;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.util.ReflectionTestUtils;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.nio.file.Files;
import java.util.Base64;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.net.InetAddress;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class UploadValidationTest {
    @TempDir Path temp;

    @Test
    void acceptsBoundedWebpForCloudDecoderValidation() throws Exception {
        Path file = temp.resolve("pixel.webp");
        Files.write(file, Base64.getDecoder().decode("UklGRiIAAABXRUJQVlA4IBYAAAAwAQCdASoBAAEADsD+JaQAA3AAAAAA"));
        assertEquals("webp", PictureUploadTemplate.validateImage(file.toFile()));
    }

    @Test
    void rejectsTruncatedAnimatedOversizedAndZeroDimensionWebp() throws Exception {
        byte[] valid = Base64.getDecoder().decode("UklGRiIAAABXRUJQVlA4IBYAAAAwAQCdASoBAAEADsD+JaQAA3AAAAAA");
        byte[] truncated = Arrays.copyOf(valid, valid.length - 1);
        byte[] zero = valid.clone(); Arrays.fill(zero, 26, 30, (byte) 0);
        byte[] extended = new byte[valid.length + 18];
        System.arraycopy(valid, 0, extended, 0, 12);
        extended[4] = (byte) (extended.length - 8);
        System.arraycopy("VP8X".getBytes(), 0, extended, 12, 4); extended[16] = 10;
        Arrays.fill(extended, 24, 30, (byte) 255);
        System.arraycopy(valid, 12, extended, 30, valid.length - 12);
        byte[] animated = extended.clone(); animated[20] = 2;
        for (byte[] invalid : new byte[][]{truncated, zero, extended, animated}) {
            Path file = temp.resolve("invalid.webp"); Files.write(file, invalid);
            assertThrows(BusinessException.class, () -> PictureUploadTemplate.validateImage(file.toFile()));
        }
    }

    @Test
    void capsStreamingEvenWithoutContentLength() {
        byte[] oversized = new byte[2 * 1024 * 1024 + 1];
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        assertThrows(BusinessException.class,
                () -> PictureUploadTemplate.copyBounded(new ByteArrayInputStream(oversized), output));
        assertTrue(output.size() <= 2 * 1024 * 1024);
    }

    @Test
    void rejectsLocalAndTransitionAddresses() throws Exception {
        for (String address : new String[]{"10.1.1.1", "172.16.1.1", "192.168.1.1", "169.254.169.254", "100.64.1.1", "0.0.0.0", "::1", "fc00::1", "2002:7f00:1::"}) {
            assertFalse(UrlPictureUpload.isPublicAddress(InetAddress.getByName(address)), address);
        }
        assertTrue(UrlPictureUpload.isPublicAddress(InetAddress.getByName("8.8.8.8")));
    }
    @Test
    void rejectsLoopbackBeforeAnyNetworkRequest() {
        BusinessException error = assertThrows(BusinessException.class,
                () -> new UrlPictureUpload().validPicture("http://127.0.0.1:1/fake.png"));
        assertEquals(ErrorCode.PARAMS_ERROR.getCode(), error.getCode());
    }

    @Test
    void rejectsTextDisguisedAsPngBeforeCloudUpload() {
        FilePictureUpload upload = new FilePictureUpload();
        CosManager cos = mock(CosManager.class);
        ReflectionTestUtils.setField(upload, "cosManager", cos);
        MockMultipartFile file = new MockMultipartFile("file", "fake.png", "image/png", "not a picture".getBytes());
        BusinessException error = assertThrows(BusinessException.class,
                () -> upload.uploadPicture(file, "public/1"));
        assertEquals(ErrorCode.PARAMS_ERROR.getCode(), error.getCode());
        verifyNoInteractions(cos);
    }
}
