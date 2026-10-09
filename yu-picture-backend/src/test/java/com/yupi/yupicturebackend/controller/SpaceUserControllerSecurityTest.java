package com.yupi.yupicturebackend.controller;

import com.yupi.yupicturebackend.common.DeleteRequest;
import com.yupi.yupicturebackend.exception.BusinessException;
import com.yupi.yupicturebackend.model.dto.spaceuser.*;
import com.yupi.yupicturebackend.model.entity.*;
import com.yupi.yupicturebackend.service.*;
import org.junit.jupiter.api.*;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.util.ReflectionTestUtils;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.any;

class SpaceUserControllerSecurityTest {
    @InjectMocks private SpaceUserController controller;
    @Mock private SpaceUserService members;
    @Mock private SpaceService spaces;
    private AutoCloseable mocks;
    private final SpaceUser owner = new SpaceUser();
    private final Space space = new Space();

    @BeforeEach void setUp() {
        mocks = MockitoAnnotations.openMocks(this);
        owner.setId(55L); owner.setSpaceId(11L); owner.setUserId(7L); owner.setSpaceRole("admin");
        space.setId(11L); space.setUserId(7L); space.setSpaceType(1);
        when(spaces.getById(11L)).thenReturn(space);
        when(spaces.getOne(any())).thenReturn(space);
        when(members.getById(55L)).thenReturn(owner);
        when(members.removeById(55L)).thenReturn(true);
        when(members.updateById(any())).thenReturn(true);
        when(members.addSpaceUser(any())).thenReturn(66L);
    }

    @AfterEach void cleanUp() throws Exception { mocks.close(); }

    @Test void memberListMustNameItsSpaceEvenWhenInterceptorResolvedAnAlias() {
        assertThrows(BusinessException.class, () -> controller.listSpaceUser(new SpaceUserQueryRequest(), new MockHttpServletRequest()));
        verify(members, never()).list(any(com.baomidou.mybatisplus.core.conditions.Wrapper.class));
    }

    @Test void creatorMembershipCannotBeDeleted() {
        DeleteRequest query = new DeleteRequest(); query.setId(55L);
        assertThrows(BusinessException.class, () -> controller.deleteSpaceUser(query, new MockHttpServletRequest()));
        verify(members, never()).removeById(55L);
    }

    @Test void creatorCannotBeDemotedFromAdmin() {
        SpaceUserEditRequest query = new SpaceUserEditRequest(); query.setId(55L); query.setSpaceRole("viewer");
        assertThrows(BusinessException.class, () -> controller.editSpaceUser(query, new MockHttpServletRequest()));
        verify(members, never()).updateById(any());
    }

    @Test void privateSpaceCannotGainTeamMembers() {
        space.setSpaceType(0);
        SpaceUserAddRequest query = new SpaceUserAddRequest(); query.setSpaceId(11L); query.setUserId(8L); query.setSpaceRole("viewer");
        assertThrows(BusinessException.class, () -> controller.addSpaceUser(query, new MockHttpServletRequest()));
        verify(members, never()).addSpaceUser(any());
    }

    @Test void existingTeamAdminCanStillAddViewer() {
        SpaceUserAddRequest query = new SpaceUserAddRequest(); query.setSpaceId(11L); query.setUserId(8L); query.setSpaceRole("viewer");
        assertEquals(66L, controller.addSpaceUser(query, new MockHttpServletRequest()).getData());
    }
}
