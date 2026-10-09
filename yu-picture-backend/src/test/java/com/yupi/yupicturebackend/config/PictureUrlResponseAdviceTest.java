package com.yupi.yupicturebackend.config;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.qcloud.cos.COSClient;
import com.qcloud.cos.ClientConfig;
import com.qcloud.cos.auth.BasicCOSCredentials;
import com.qcloud.cos.http.HttpProtocol;
import com.qcloud.cos.endpoint.UserSpecifiedEndpointBuilder;
import com.qcloud.cos.region.Region;
import com.yupi.yupicturebackend.common.BaseResponse;
import com.yupi.yupicturebackend.manager.PictureUrlSigner;
import com.yupi.yupicturebackend.model.entity.Picture;
import com.yupi.yupicturebackend.model.vo.PictureVO;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class PictureUrlResponseAdviceTest {
    private static final String HOST = "https://cos-fixture.example.invalid";
    private COSClient client;
    private PictureUrlResponseAdvice advice;

    @BeforeEach
    void setUp() {
        ClientConfig sdkConfig = new ClientConfig(new Region("ap-shanghai"));
        sdkConfig.setHttpProtocol(HttpProtocol.https);
        // Reserved domain: the SDK signs locally without using a real bucket or account.
        sdkConfig.setEndpointBuilder(new UserSpecifiedEndpointBuilder(
                "cos-fixture.example.invalid", "cos-fixture.example.invalid"));
        client = new COSClient(new BasicCOSCredentials("test-id", "test-key"), sdkConfig);
        CosClientConfig config = new CosClientConfig();
        config.setHost(HOST);
        config.setBucket("fixture-bucket");
        advice = new PictureUrlResponseAdvice(new PictureUrlSigner(client, config));
    }

    @AfterEach
    void tearDown() {
        client.shutdown();
    }

    @Test
    void signsBothUrlsInAPageWithoutMutatingCachedResponses() {
        PictureVO picture = pictureVO();
        Page<PictureVO> page = new Page<>(3, 12, 41);
        page.setRecords(List.of(picture));
        BaseResponse<Page<PictureVO>> original = new BaseResponse<>(0, page, "ok");

        BaseResponse<?> response = (BaseResponse<?>) write(original);
        Page<?> result = (Page<?>) response.getData();
        PictureVO signed = (PictureVO) result.getRecords().get(0);

        assertNotSame(original, response);
        assertNotSame(page, result);
        assertNotSame(picture, signed);
        assertEquals(3, result.getCurrent());
        assertEquals(12, result.getSize());
        assertEquals(41, result.getTotal());
        assertEquals("ok", response.getMessage());
        assertEquals("sample", signed.getName());
        assertTrue(signed.getUrl().contains("q-signature="));
        assertTrue(signed.getThumbnailUrl().contains("q-signature="));
        assertEquals(HOST + "/public/photo.webp", picture.getUrl());
        assertEquals(HOST + "/public/photo_thumbnail.webp", picture.getThumbnailUrl());
    }

    @Test
    void signsListResultsAndLeavesUnrelatedValuesUnchanged() {
        PictureVO picture = pictureVO();
        List<?> original = List.of(picture, "plain", 4);
        BaseResponse<?> response = (BaseResponse<?>) write(new BaseResponse<>(0, original));
        List<?> result = (List<?>) response.getData();

        assertTrue(((PictureVO) result.get(0)).getUrl().contains("q-signature="));
        assertEquals("plain", result.get(1));
        assertEquals(4, result.get(2));
        assertEquals(HOST + "/public/photo.webp", picture.getUrl());
    }

    @Test
    void signsAdminPictureCopiesWithoutChangingThePersistenceEntity() {
        Picture picture = new Picture();
        picture.setId(7L);
        picture.setUrl(HOST + "/public/photo.webp");
        picture.setThumbnailUrl(HOST + "/public/photo_thumbnail.webp");
        picture.setReviewMessage("approved");
        BaseResponse<?> response = (BaseResponse<?>) write(new BaseResponse<>(0, picture));
        Picture signed = (Picture) response.getData();

        assertNotSame(picture, signed);
        assertEquals(7L, signed.getId());
        assertEquals("approved", signed.getReviewMessage());
        assertTrue(signed.getUrl().contains("q-signature="));
        assertTrue(signed.getThumbnailUrl().contains("q-signature="));
        assertEquals(HOST + "/public/photo.webp", picture.getUrl());
        assertEquals(HOST + "/public/photo_thumbnail.webp", picture.getThumbnailUrl());
    }

    @Test
    void handlesNullAndDoesNotTransformUnrelatedData() {
        assertNull(write(null));
        BaseResponse<?> response = (BaseResponse<?>) write(new BaseResponse<>(0, null));
        assertNull(response.getData());
        assertEquals("unchanged", write("unchanged"));
    }

    private Object write(Object value) {
        return advice.beforeBodyWrite(value, null, MediaType.APPLICATION_JSON,
                MappingJackson2HttpMessageConverter.class, null, null);
    }

    private PictureVO pictureVO() {
        PictureVO picture = new PictureVO();
        picture.setName("sample");
        picture.setUrl(HOST + "/public/photo.webp");
        picture.setThumbnailUrl(HOST + "/public/photo_thumbnail.webp");
        return picture;
    }
}
