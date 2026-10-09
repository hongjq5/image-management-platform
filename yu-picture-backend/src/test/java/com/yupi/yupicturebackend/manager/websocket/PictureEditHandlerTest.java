package com.yupi.yupicturebackend.manager.websocket;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yupi.yupicturebackend.constant.UserConstant;
import com.yupi.yupicturebackend.manager.auth.SpaceUserAuthManager;
import com.yupi.yupicturebackend.manager.auth.model.SpaceUserPermissionConstant;
import com.yupi.yupicturebackend.manager.websocket.disruptor.PictureEditEventProducer;
import com.yupi.yupicturebackend.manager.websocket.model.PictureEditRequestMessage;
import com.yupi.yupicturebackend.model.entity.Picture;
import com.yupi.yupicturebackend.model.entity.Space;
import com.yupi.yupicturebackend.model.entity.User;
import com.yupi.yupicturebackend.model.enums.SpaceTypeEnum;
import com.yupi.yupicturebackend.model.vo.UserVO;
import com.yupi.yupicturebackend.service.PictureService;
import com.yupi.yupicturebackend.service.SpaceService;
import com.yupi.yupicturebackend.service.UserService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.session.MapSession;
import org.springframework.session.SessionRepository;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class PictureEditHandlerTest {
    @Mock private UserService userService;
    @Mock private PictureService pictureService;
    @Mock private SpaceService spaceService;
    @Mock private SpaceUserAuthManager spaceUserAuthManager;
    @Mock private SessionRepository<MapSession> sessionRepository;
    @Mock private PictureEditEventProducer pictureEditEventProducer;
    @InjectMocks private PictureEditHandler handler;
    private AutoCloseable mocks;
    private final ObjectMapper json = new ObjectMapper();
    private final User user = user(7L);
    private final Map<String, List<JsonNode>> messages = new HashMap<>();
    private final MapSession login = new MapSession("login");
    private WebSocketSession owner;
    private WebSocketSession sibling;
    private WebSocketSession observer;

    @BeforeEach
    void setup() throws Exception {
        mocks = MockitoAnnotations.openMocks(this);
        login.setAttribute(UserConstant.USER_LOGIN_STATE, user);
        when(sessionRepository.findById("login")).thenReturn(login);
        when(userService.getById(7L)).thenReturn(user);
        when(userService.getUserVO(any())).thenAnswer(invocation -> {
            User value = invocation.getArgument(0);
            UserVO vo = new UserVO();
            vo.setId(value.getId());
            vo.setUserName(value.getUserName());
            return vo;
        });
        Picture picture = new Picture();
        picture.setId(12L);
        picture.setSpaceId(21L);
        Space space = new Space();
        space.setId(21L);
        space.setSpaceType(SpaceTypeEnum.TEAM.getValue());
        when(pictureService.getById(12L)).thenReturn(picture);
        when(spaceService.getById(21L)).thenReturn(space);
        when(spaceUserAuthManager.getPermissionList(any(), any())).thenReturn(List.of(SpaceUserPermissionConstant.PICTURE_EDIT));
        owner = session("owner");
        sibling = session("sibling");
        observer = session("observer");
        handler.afterConnectionEstablished(owner);
        handler.afterConnectionEstablished(sibling);
        handler.afterConnectionEstablished(observer);
        clearMessages();
    }

    @AfterEach
    void cleanup() throws Exception { mocks.close(); }

    @Test
    void anotherTabForSameUserCannotEditTheOwnersImage() throws Exception {
        enter(owner);
        clearMessages();
        edit(sibling);
        assertEquals(0, count("observer", "EDIT_ACTION"));
        edit(owner);
        assertEquals(1, count("observer", "EDIT_ACTION"));
        assertEquals("7", messages.get("observer").get(0).get("user").get("id").asText());
        assertEquals(0, count("owner", "EDIT_ACTION"));
    }

    @Test
    void enterEditIdentifiesOnlyTheOwningConnectionForTheSameUser() throws Exception {
        enter(owner);
        assertTrue(lastMessage("owner", "ENTER_EDIT").path("isEditor").asBoolean());
        assertEquals(json.readTree("false"), lastMessage("sibling", "ENTER_EDIT").path("isEditor"));
        assertEquals("7", lastMessage("sibling", "ENTER_EDIT").path("user").path("id").asText());
        clearMessages();
        edit(owner);
        assertEquals(1, count("sibling", "EDIT_ACTION"));
        assertEquals(json.readTree("false"), lastMessage("sibling", "EDIT_ACTION").path("isEditor"));
        assertEquals(0, count("owner", "EDIT_ACTION"));
    }

    @Test
    void lateJoiningTabReceivesCurrentOwnerWithoutAcquiringTheLock() throws Exception {
        enter(owner);
        clearMessages();
        WebSocketSession late = session("late");
        handler.afterConnectionEstablished(late);
        assertEquals(1, count("late", "ENTER_EDIT"));
        JsonNode snapshot = lastMessage("late", "ENTER_EDIT");
        assertEquals("7", snapshot.path("user").path("id").asText());
        assertEquals(json.readTree("false"), snapshot.path("isEditor"));
        assertEquals(0, count("owner", "ENTER_EDIT"));
        assertTrue(lastMessage("owner", "INFO").path("isEditor").asBoolean());
        edit(late);
        assertEquals(0, count("observer", "EDIT_ACTION"));
    }

    @Test
    void joiningAnUnlockedRoomExplicitlyReportsNoConnectionOwnership() throws Exception {
        WebSocketSession late = session("late");
        handler.afterConnectionEstablished(late);
        assertEquals(json.readTree("false"), lastMessage("late", "INFO").path("isEditor"));
        assertEquals(0, count("late", "ENTER_EDIT"));
    }

    @Test
    void exitEditClearsOwnershipForEveryConnection() throws Exception {
        enter(owner);
        clearMessages();
        handler.handleExitEditMessage(new PictureEditRequestMessage("EXIT_EDIT", null), owner, user, 12L);
        for (String id : List.of("owner", "sibling", "observer")) {
            assertEquals(json.readTree("false"), lastMessage(id, "EXIT_EDIT").path("isEditor"));
        }
    }

    @Test
    void anotherTabCannotReleaseTheOwnersLock() throws Exception {
        enter(owner);
        clearMessages();
        handler.handleExitEditMessage(new PictureEditRequestMessage("EXIT_EDIT", null), sibling, user, 12L);
        edit(owner);
        assertEquals(0, count("observer", "EXIT_EDIT"));
        assertEquals(1, count("observer", "EDIT_ACTION"));
    }

    @Test
    void closingAnotherTabKeepsOwnersLockButClosingOwnerReleasesIt() throws Exception {
        enter(owner);
        clearMessages();
        handler.afterConnectionClosed(sibling, CloseStatus.NORMAL);
        edit(owner);
        assertEquals(0, count("observer", "EXIT_EDIT"));
        assertEquals(1, count("observer", "EDIT_ACTION"));
        handler.afterConnectionClosed(owner, CloseStatus.NORMAL);
        enter(observer);
        assertEquals(1, count("observer", "ENTER_EDIT"));
    }

    @Test
    void revokedMembershipRejectsQueuedEditsClosesConnectionAndReleasesLock() throws Exception {
        enter(owner);
        clearMessages();
        when(spaceUserAuthManager.getPermissionList(any(), any())).thenReturn(List.of());
        edit(owner);
        assertEquals(0, count("observer", "EDIT_ACTION"));
        verify(owner).close(CloseStatus.POLICY_VIOLATION);
        when(spaceUserAuthManager.getPermissionList(any(), any())).thenReturn(List.of(SpaceUserPermissionConstant.PICTURE_EDIT));
        enter(sibling);
        assertEquals(1, count("observer", "ENTER_EDIT"));
    }

    @Test
    void logoutRejectsNewInboundCommands() throws Exception {
        login.removeAttribute(UserConstant.USER_LOGIN_STATE);
        handler.handleTextMessage(owner, new TextMessage("{\"type\":\"ENTER_EDIT\"}"));
        verifyNoInteractions(pictureEditEventProducer);
        verify(owner).close(CloseStatus.POLICY_VIOLATION);
    }

    @Test
    void expiredLoginRejectsQueuedEnter() throws Exception {
        when(sessionRepository.findById("login")).thenReturn(null);
        enter(owner);
        assertEquals(0, count("observer", "ENTER_EDIT"));
        verify(owner).close(CloseStatus.POLICY_VIOLATION);
    }

    @Test
    void queuedCommandCannotReacquireAfterConnectionClosed() throws Exception {
        handler.afterConnectionClosed(owner, CloseStatus.NORMAL);
        clearMessages();
        enter(owner);
        assertEquals(0, count("observer", "ENTER_EDIT"));
        enter(sibling);
        assertEquals(1, count("observer", "ENTER_EDIT"));
    }

    @Test
    void idleLoggedOutOwnerCannotKeepAnotherAuthenticatedConnectionLockedOut() throws Exception {
        enter(owner);
        clearMessages();
        MapSession otherLogin = new MapSession("other-login");
        otherLogin.setAttribute(UserConstant.USER_LOGIN_STATE, user);
        when(sessionRepository.findById("other-login")).thenReturn(otherLogin);
        sibling.getAttributes().put("httpSessionId", "other-login");
        login.removeAttribute(UserConstant.USER_LOGIN_STATE);
        enter(sibling);
        assertEquals(1, count("sibling", "ENTER_EDIT"));
        verify(owner).close(CloseStatus.POLICY_VIOLATION);
    }

    @Test
    void failedErrorReplyReleasesOwnersLockWithoutWaitingForCloseCallback() throws Exception {
        enter(owner);
        clearMessages();
        doThrow(new IOException("broken transport")).when(owner).sendMessage(any());
        handler.handleTextMessage(owner, new TextMessage("{}"));
        enter(sibling);
        assertEquals(1, count("observer", "ENTER_EDIT"));
    }

    @Test
    void synchronousCloseCallbackFromBrokenRecipientDoesNotAbortBroadcast() throws Exception {
        doThrow(new IOException("broken transport")).when(sibling).sendMessage(any());
        doAnswer(invocation -> {
            handler.afterConnectionClosed(sibling, CloseStatus.SERVER_ERROR);
            return null;
        }).when(sibling).close(CloseStatus.SERVER_ERROR);
        assertDoesNotThrow(() -> enter(owner));
        assertEquals(1, count("observer", "ENTER_EDIT"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"{", "null", "[]", "{}", "{\"type\":\"UNKNOWN\"}", "{\"type\":\"INFO\"}", "{\"type\":\"EDIT_ACTION\",\"editAction\":\"INVALID\"}"})
    void invalidMessagesReturnErrorWithoutReachingDisruptor(String payload) {
        assertDoesNotThrow(() -> handler.handleTextMessage(owner, new TextMessage(payload)));
        verifyNoInteractions(pictureEditEventProducer);
        assertEquals(1, count("owner", "ERROR"));
    }

    @Test
    void failedRecipientDoesNotPreventOtherRecipientsReceivingBroadcast() throws Exception {
        doThrow(new IOException("broken transport")).when(sibling).sendMessage(any());
        assertDoesNotThrow(() -> enter(owner));
        assertEquals(1, count("observer", "ENTER_EDIT"));
        verify(sibling).close(CloseStatus.SERVER_ERROR);
    }

    @Test
    void parallelActionsNeverSendConcurrentlyToOneSocket() throws Exception {
        enter(owner);
        AtomicInteger active = new AtomicInteger();
        AtomicBoolean concurrent = new AtomicBoolean();
        doAnswer(invocation -> {
            if (active.incrementAndGet() > 1) concurrent.set(true);
            try { Thread.sleep(15); } finally { active.decrementAndGet(); }
            return null;
        }).when(observer).sendMessage(any());
        ExecutorService executor = Executors.newFixedThreadPool(8);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<?>> actions = new CopyOnWriteArrayList<>();
            for (int i = 0; i < 8; i++) actions.add(executor.submit(() -> {
                start.await();
                edit(owner);
                return null;
            }));
            start.countDown();
            for (Future<?> action : actions) action.get(5, TimeUnit.SECONDS);
        } finally {
            executor.shutdownNow();
        }
        assertFalse(concurrent.get(), "WebSocket transports reject overlapping sends");
    }

    @Test
    void concurrentClaimsAnnounceExactlyOneConnectionOwner() throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Future<?> first = executor.submit(() -> { start.await(); enter(owner); return null; });
            Future<?> second = executor.submit(() -> { start.await(); enter(sibling); return null; });
            start.countDown();
            first.get(5, TimeUnit.SECONDS);
            second.get(5, TimeUnit.SECONDS);
        } finally {
            executor.shutdownNow();
        }
        assertEquals(1, count("owner", "ENTER_EDIT"));
        assertEquals(1, count("sibling", "ENTER_EDIT"));
        boolean firstOwns = lastMessage("owner", "ENTER_EDIT").path("isEditor").asBoolean();
        boolean secondOwns = lastMessage("sibling", "ENTER_EDIT").path("isEditor").asBoolean();
        assertNotEquals(firstOwns, secondOwns, "Only the winning socket receives isEditor=true");
        assertEquals(json.readTree("false"), lastMessage("observer", "ENTER_EDIT").path("isEditor"));
    }

    private WebSocketSession session(String id) throws IOException {
        WebSocketSession result = mock(WebSocketSession.class);
        Map<String, Object> attributes = new HashMap<>();
        attributes.put("user", user);
        attributes.put("userId", 7L);
        attributes.put("pictureId", 12L);
        attributes.put("httpSessionId", "login");
        when(result.getId()).thenReturn(id);
        when(result.isOpen()).thenReturn(true);
        when(result.getAttributes()).thenReturn(attributes);
        messages.put(id, new CopyOnWriteArrayList<>());
        doAnswer(invocation -> {
            messages.get(id).add(json.readTree(((TextMessage) invocation.getArgument(0)).getPayload()));
            return null;
        }).when(result).sendMessage(any());
        return result;
    }

    private void enter(WebSocketSession session) throws IOException {
        handler.handleEnterEditMessage(new PictureEditRequestMessage("ENTER_EDIT", null), session, user, 12L);
    }

    private void edit(WebSocketSession session) throws IOException {
        handler.handleEditActionMessage(new PictureEditRequestMessage("EDIT_ACTION", "ZOOM_IN"), session, user, 12L);
    }

    private void clearMessages() { messages.values().forEach(List::clear); }

    private long count(String session, String type) {
        return messages.get(session).stream().filter(message -> type.equals(message.path("type").asText())).count();
    }

    private JsonNode lastMessage(String session, String type) {
        return messages.get(session).stream().filter(message -> type.equals(message.path("type").asText()))
                .reduce((first, second) -> second).orElseThrow();
    }

    private static User user(long id) {
        User result = new User();
        result.setId(id);
        result.setUserName("member");
        return result;
    }
}
