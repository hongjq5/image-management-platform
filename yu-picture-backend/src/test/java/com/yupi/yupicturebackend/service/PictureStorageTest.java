package com.yupi.yupicturebackend.service;

import com.yupi.yupicturebackend.manager.upload.FilePictureUpload;
import com.yupi.yupicturebackend.mapper.PictureMapper;
import com.yupi.yupicturebackend.mapper.SpaceMapper;
import com.yupi.yupicturebackend.manager.auth.SpaceUserAuthManager;
import com.yupi.yupicturebackend.exception.BusinessException;
import com.yupi.yupicturebackend.model.entity.Space;
import com.yupi.yupicturebackend.model.dto.picture.PictureQueryRequest;
import com.yupi.yupicturebackend.model.dto.picture.PictureEditByBatchRequest;
import com.yupi.yupicturebackend.manager.CosManager;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import java.util.Collections;
import com.yupi.yupicturebackend.model.dto.file.UploadPictureResult;
import com.yupi.yupicturebackend.model.dto.picture.PictureUploadRequest;
import com.yupi.yupicturebackend.model.entity.Picture;
import com.yupi.yupicturebackend.model.entity.User;
import com.yupi.yupicturebackend.service.impl.PictureServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class PictureStorageTest {
    private PictureServiceImpl service;
    private SpaceService spaces;
    private User user;
    private Picture old;
    private SpaceMapper quota;
    private TestTransactionManager manager;
    private PictureMapper pictures;

    @BeforeEach
    void setUp() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), "test"), Picture.class);
        service = spy(new PictureServiceImpl());
        doReturn(Picture.class).when(service).getEntityClass();
        spaces = mock(SpaceService.class);
        FilePictureUpload upload = mock(FilePictureUpload.class);
        UserService users = mock(UserService.class);
        when(users.isAdmin(any(User.class))).thenReturn(true);
        ReflectionTestUtils.setField(service, "spaceService", spaces);
        ReflectionTestUtils.setField(service, "userService", users);
        ReflectionTestUtils.setField(service, "filePictureUpload", upload);
        manager = new TestTransactionManager();
        TransactionTemplate transaction = new TransactionTemplate(manager);
        ReflectionTestUtils.setField(service, "transactionTemplate", transaction);
        quota = mock(SpaceMapper.class);
        ReflectionTestUtils.setField(service, "spaceMapper", quota);
        SpaceUserAuthManager permissions = mock(SpaceUserAuthManager.class);
        when(permissions.getPermissionList(any(), any())).thenReturn(Arrays.asList("picture:edit", "picture:upload", "picture:delete", "picture:view"));
        ReflectionTestUtils.setField(service, "spaceUserAuthManager", permissions);
        Space space = new Space(); space.setId(9L); space.setUserId(1L);
        space.setTotalCount(10L); space.setMaxCount(10L); space.setTotalSize(1000L); space.setMaxSize(1000L);
        when(spaces.getById(9L)).thenReturn(space);
        user = new User(); user.setId(2L);
        old = new Picture(); old.setId(10L); old.setUserId(1L);
        old.setPicSize(100L); old.setUrl("https://bucket.example/public/1/old.webp");
        doReturn(old).when(service).getById(10L);
        pictures = mock(PictureMapper.class);
        when(pictures.selectForUpdate(10L)).thenReturn(old);
        ReflectionTestUtils.setField(service, "baseMapper", pictures);
        doReturn(true).when(service).saveOrUpdate(any(Picture.class));
        doReturn(true).when(service).removeById(10L);
        doNothing().when(service).clearPictureFile(any());
        UploadPictureResult result = new UploadPictureResult();
        result.setUrl("https://bucket.example/public/2/new.webp");
        result.setPicSize(160L); result.setPicColor("0xFFFFFF");
        when(upload.uploadPicture(any(), anyString())).thenReturn(result);
    }

    @Test
    void replacementKeepsOriginalOwner() {
        PictureUploadRequest request = new PictureUploadRequest(); request.setId(10L);
        assertEquals(1L, service.uploadPicture(new Object(), request, user).getUserId());
    }

    @Test
    void deletingPublicPictureDoesNotUpdateSpaceQuota() {
        assertDoesNotThrow(() -> service.deletePicture(10L, user));
        verifyNoInteractions(spaces);
        verify(service).clearPictureFile(old);
    }

    @Test
    void replacementAtCountLimitChargesOnlySizeDifference() {
        old.setSpaceId(9L);
        when(quota.adjustUsage(9L, 60L, 0L)).thenReturn(1);
        PictureUploadRequest request = new PictureUploadRequest(); request.setId(10L);
        assertEquals(1L, service.uploadPicture(new Object(), request, user).getUserId());
        verify(quota).adjustUsage(9L, 60L, 0L);
        verify(service).clearPictureFile(old);
        assertTrue(manager.committed);
    }

    @Test
    void quotaRejectionRollsBackAndCleansNewObjectWithoutSaving() {
        PictureUploadRequest request = new PictureUploadRequest(); request.setSpaceId(9L);
        assertThrows(BusinessException.class, () -> service.uploadPicture(new Object(), request, user));
        verify(service, never()).saveOrUpdate(any(Picture.class));
        verify(quota).adjustUsage(9L, 160L, 1L);
        verify(service).clearPictureFile(argThat(p -> p.getUrl().endsWith("new.webp")));
        verify(service, never()).clearPictureFile(old);
        assertTrue(manager.rolledBack);
    }

    @Test
    void databaseFailurePreservesOldObjectAndCleansNewAfterRollback() {
        doReturn(false).when(service).saveOrUpdate(any(Picture.class));
        doAnswer(invocation -> {
            assertTrue(manager.rolledBack);
            return null;
        }).when(service).clearPictureFile(any(Picture.class));
        PictureUploadRequest request = new PictureUploadRequest(); request.setId(10L);
        assertThrows(BusinessException.class, () -> service.uploadPicture(new Object(), request, user));
        verify(service, never()).clearPictureFile(old);
        verify(service).clearPictureFile(argThat(p -> p.getUrl().endsWith("new.webp")));
    }

    @Test
    void deletesOldObjectOnlyAfterSuccessfulCommit() {
        doAnswer(invocation -> { assertTrue(manager.committed); return null; })
                .when(service).clearPictureFile(any(Picture.class));
        PictureUploadRequest request = new PictureUploadRequest(); request.setId(10L);
        service.uploadPicture(new Object(), request, user);
        verify(service).clearPictureFile(old);
    }

    @Test
    void rejectsSqlSortExpressionsAndUnboundedPages() {
        PictureQueryRequest request = new PictureQueryRequest();
        request.setSortField("id desc; drop table picture");
        assertThrows(BusinessException.class, () -> service.getQueryWrapper(request));
        request.setSortField("id"); request.setPageSize(101);
        assertThrows(BusinessException.class, () -> service.getQueryWrapper(request));
    }

    @Test
    void teamEditorCanSearchWithoutBeingTheSpaceCreator() {
        when(pictures.selectList(any(Wrapper.class))).thenReturn(Collections.emptyList());
        assertEquals(Collections.emptyList(), service.searchPictureByColor(9L, "#ffffff", user));
    }

    @Test
    void batchRejectsIdsOutsideTargetSpaceBeforeUpdatingAnything() {
        PictureEditByBatchRequest request = new PictureEditByBatchRequest();
        request.setSpaceId(9L); request.setPictureIdList(Arrays.asList(10L, 11L));
        old.setSpaceId(9L);
        when(pictures.selectList(any(Wrapper.class))).thenReturn(Collections.singletonList(old));
        assertThrows(BusinessException.class, () -> service.editPictureByBatch(request, user));
        verify(pictures, never()).update(any(Picture.class), any(Wrapper.class));
    }

    @Test
    void survivingThumbnailReferencePreventsObjectDeletion() {
        CosManager cos = mock(CosManager.class);
        ReflectionTestUtils.setField(service, "cosManager", cos);
        doCallRealMethod().when(service).clearPictureFile(old);
        doReturn(1L).when(service).count(any(Wrapper.class));
        service.clearPictureFile(old);
        verifyNoInteractions(cos);
    }

    private static class TestTransactionManager extends AbstractPlatformTransactionManager {
        boolean committed, rolledBack;
        protected Object doGetTransaction() { return new Object(); }
        protected void doBegin(Object transaction, TransactionDefinition definition) { }
        protected void doCommit(DefaultTransactionStatus status) { committed = true; }
        protected void doRollback(DefaultTransactionStatus status) { rolledBack = true; }
    }
}
