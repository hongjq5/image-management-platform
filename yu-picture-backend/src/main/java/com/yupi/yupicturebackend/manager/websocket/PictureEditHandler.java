package com.yupi.yupicturebackend.manager.websocket;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import com.yupi.yupicturebackend.constant.UserConstant;
import com.yupi.yupicturebackend.manager.auth.SpaceUserAuthManager;
import com.yupi.yupicturebackend.manager.auth.model.SpaceUserPermissionConstant;
import com.yupi.yupicturebackend.manager.websocket.disruptor.PictureEditEventProducer;
import com.yupi.yupicturebackend.manager.websocket.model.PictureEditActionEnum;
import com.yupi.yupicturebackend.manager.websocket.model.PictureEditMessageTypeEnum;
import com.yupi.yupicturebackend.manager.websocket.model.PictureEditRequestMessage;
import com.yupi.yupicturebackend.manager.websocket.model.PictureEditResponseMessage;
import com.yupi.yupicturebackend.model.entity.Picture;
import com.yupi.yupicturebackend.model.entity.Space;
import com.yupi.yupicturebackend.model.entity.User;
import com.yupi.yupicturebackend.model.enums.SpaceTypeEnum;
import com.yupi.yupicturebackend.service.PictureService;
import com.yupi.yupicturebackend.service.SpaceService;
import com.yupi.yupicturebackend.service.UserService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Lazy;
import org.springframework.session.Session;
import org.springframework.session.SessionRepository;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import javax.annotation.Resource;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;

/** 图片协同编辑。房间状态按图片串行更新，编辑权属于具体连接。 */
@Component
@Slf4j
public class PictureEditHandler extends TextWebSocketHandler {
    @Resource
    private UserService userService;
    @Resource
    private PictureService pictureService;
    @Resource
    private SpaceService spaceService;
    @Resource
    private SpaceUserAuthManager spaceUserAuthManager;
    @Resource
    private SessionRepository<? extends Session> sessionRepository;
    @Resource
    @Lazy
    private PictureEditEventProducer pictureEditEventProducer;

    private final Map<Long, PictureRoom> rooms = new ConcurrentHashMap<>();
    private final ObjectMapper objectMapper = new ObjectMapper().registerModule(new SimpleModule()
            .addSerializer(Long.class, ToStringSerializer.instance)
            .addSerializer(Long.TYPE, ToStringSerializer.instance));

    private static class PictureRoom {
        private final Set<WebSocketSession> sessions = new LinkedHashSet<>();
        private WebSocketSession editor;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        Long pictureId = pictureId(session);
        User user = authorizedUser(session, pictureId);
        if (user == null) {
            close(session, CloseStatus.POLICY_VIOLATION);
            return;
        }
        rooms.compute(pictureId, (id, existing) -> {
            PictureRoom room = existing == null ? new PictureRoom() : existing;
            room.sessions.add(session);
            broadcast(room, response(PictureEditMessageTypeEnum.INFO,
                    String.format("用户 %s 加入编辑", user.getUserName()), user), null);
            // A newly connected tab did not see the original lock-acquisition event.
            if (room.editor != null && room.sessions.contains(session)) {
                User editor = (User) room.editor.getAttributes().get("user");
                PictureEditResponseMessage state = response(PictureEditMessageTypeEnum.ENTER_EDIT,
                        String.format("用户 %s 正在编辑图片", editor.getUserName()), editor);
                if (!send(session, forRecipient(room, state, session))) {
                    disconnect(room, session);
                    close(session, CloseStatus.SERVER_ERROR);
                }
            }
            return room.sessions.isEmpty() ? null : room;
        });
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
        Long pictureId = pictureId(session);
        User user = authorizedUser(session, pictureId);
        if (user == null) {
            removeSession(session, CloseStatus.POLICY_VIOLATION);
            return;
        }
        PictureEditRequestMessage request;
        try {
            request = objectMapper.readValue(message.getPayload(), PictureEditRequestMessage.class);
        } catch (IOException | IllegalArgumentException exception) {
            sendError(session, "消息格式错误");
            return;
        }
        PictureEditMessageTypeEnum type = request == null ? null : PictureEditMessageTypeEnum.getEnumByValue(request.getType());
        if (type != PictureEditMessageTypeEnum.ENTER_EDIT && type != PictureEditMessageTypeEnum.EXIT_EDIT
                && type != PictureEditMessageTypeEnum.EDIT_ACTION) {
            sendError(session, "消息类型错误");
            return;
        }
        if (type == PictureEditMessageTypeEnum.EDIT_ACTION
                && PictureEditActionEnum.getEnumByValue(request.getEditAction()) == null) {
            sendError(session, "编辑动作错误");
            return;
        }
        pictureEditEventProducer.publishEvent(request, session, user, pictureId);
    }

    public void handleEnterEditMessage(PictureEditRequestMessage request, WebSocketSession session, User user, Long pictureId) throws IOException {
        inAuthorizedRoom(session, pictureId, (room, currentUser) -> {
            if (room.editor != null && authorizedUser(room.editor, pictureId) == null) {
                WebSocketSession expiredEditor = room.editor;
                disconnect(room, expiredEditor);
                close(expiredEditor, CloseStatus.POLICY_VIOLATION);
            }
            if (room.editor == null && room.sessions.contains(session)) {
                room.editor = session;
                broadcast(room, response(PictureEditMessageTypeEnum.ENTER_EDIT,
                        String.format("用户 %s 开始编辑图片", currentUser.getUserName()), currentUser), null);
            }
        });
    }

    public void handleEditActionMessage(PictureEditRequestMessage request, WebSocketSession session, User user, Long pictureId) throws IOException {
        inAuthorizedRoom(session, pictureId, (room, currentUser) -> {
            PictureEditActionEnum action = request == null ? null : PictureEditActionEnum.getEnumByValue(request.getEditAction());
            if (action == null) {
                sendError(session, "编辑动作错误");
            } else if (session.equals(room.editor)) {
                PictureEditResponseMessage result = response(PictureEditMessageTypeEnum.EDIT_ACTION,
                        String.format("%s 执行 %s", currentUser.getUserName(), action.getText()), currentUser);
                result.setEditAction(action.getValue());
                broadcast(room, result, session);
            }
        });
    }

    public void handleExitEditMessage(PictureEditRequestMessage request, WebSocketSession session, User user, Long pictureId) throws IOException {
        inAuthorizedRoom(session, pictureId, (room, currentUser) -> releaseEditor(room, session));
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) throws Exception {
        removeSession(session, null);
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) throws Exception {
        removeSession(session, CloseStatus.SERVER_ERROR);
    }

    // Atomic room updates serialize connection removal with queued commands and new joins.
    private void inAuthorizedRoom(WebSocketSession session, Long pictureId, BiConsumer<PictureRoom, User> action) {
        if (pictureId == null) return;
        rooms.computeIfPresent(pictureId, (id, room) -> {
            if (!room.sessions.contains(session)) return room;
            User user = authorizedUser(session, pictureId);
            if (user == null) {
                disconnect(room, session);
                close(session, CloseStatus.POLICY_VIOLATION);
            } else {
                action.accept(room, user);
            }
            return room.sessions.isEmpty() ? null : room;
        });
    }

    private User authorizedUser(WebSocketSession socket, Long pictureId) {
        if (!socket.isOpen() || pictureId == null || !Objects.equals(pictureId, pictureId(socket))) return null;
        Object userId = socket.getAttributes().get("userId");
        Object sessionId = socket.getAttributes().get("httpSessionId");
        if (!(userId instanceof Long) || !(sessionId instanceof String)) return null;
        try {
            Session session = sessionRepository.findById((String) sessionId);
            if (session == null || session.isExpired()) return null;
            Object login = session.getAttribute(UserConstant.USER_LOGIN_STATE);
            if (!(login instanceof User) || !Objects.equals(((User) login).getId(), userId)) return null;
            User user = userService.getById((Long) userId);
            Picture picture = pictureService.getById(pictureId);
            if (user == null || picture == null || picture.getSpaceId() == null) return null;
            Space space = spaceService.getById(picture.getSpaceId());
            if (space == null || !Integer.valueOf(SpaceTypeEnum.TEAM.getValue()).equals(space.getSpaceType())) return null;
            List<String> permissions = spaceUserAuthManager.getPermissionList(space, user);
            return permissions != null && permissions.contains(SpaceUserPermissionConstant.PICTURE_EDIT) ? user : null;
        } catch (RuntimeException exception) {
            log.warn("无法验证图片编辑连接权限，拒绝操作", exception);
            return null;
        }
    }

    private Long pictureId(WebSocketSession session) {
        Object value = session.getAttributes().get("pictureId");
        return value instanceof Long ? (Long) value : null;
    }

    private void removeSession(WebSocketSession session, CloseStatus closeStatus) {
        Long pictureId = pictureId(session);
        if (pictureId != null) {
            rooms.computeIfPresent(pictureId, (id, room) -> {
                disconnect(room, session);
                return room.sessions.isEmpty() ? null : room;
            });
        }
        if (closeStatus != null) close(session, closeStatus);
    }

    private void disconnect(PictureRoom room, WebSocketSession session) {
        if (!room.sessions.remove(session)) return;
        releaseEditor(room, session);
        User user = (User) session.getAttributes().get("user");
        if (user != null) broadcast(room, response(PictureEditMessageTypeEnum.INFO,
                String.format("用户 %s 离开编辑", user.getUserName()), user), null);
    }

    private void releaseEditor(PictureRoom room, WebSocketSession session) {
        if (!session.equals(room.editor)) return;
        room.editor = null;
        User user = (User) session.getAttributes().get("user");
        broadcast(room, response(PictureEditMessageTypeEnum.EXIT_EDIT, "用户退出编辑图片", user), null);
    }

    private PictureEditResponseMessage response(PictureEditMessageTypeEnum type, String message, User user) {
        PictureEditResponseMessage result = new PictureEditResponseMessage();
        result.setType(type.getValue());
        result.setMessage(message);
        result.setUser(userService.getUserVO(user));
        return result;
    }

    private void sendError(WebSocketSession session, String message) {
        if (!send(session, response(PictureEditMessageTypeEnum.ERROR, message,
                (User) session.getAttributes().get("user")))) {
            removeSession(session, CloseStatus.SERVER_ERROR);
        }
    }

    private void broadcast(PictureRoom room, PictureEditResponseMessage response, WebSocketSession excluded) {
        // Keep one broken transport from interrupting delivery to the other members.
        List<WebSocketSession> failed = new ArrayList<>();
        for (WebSocketSession session : new ArrayList<>(room.sessions)) {
            if (!session.equals(excluded) && !send(session, forRecipient(room, response, session))) failed.add(session);
        }
        // Remove failed recipients before closing transports: containers can call back
        // synchronously from close(), and that callback must see an already-clean room.
        room.sessions.removeAll(failed);
        for (WebSocketSession session : failed) {
            releaseEditor(room, session);
            close(session, CloseStatus.SERVER_ERROR);
        }
    }

    private PictureEditResponseMessage forRecipient(PictureRoom room, PictureEditResponseMessage response,
                                                    WebSocketSession recipient) {
        // Do not mutate a shared response: ownership differs even for two tabs of one user.
        PictureEditResponseMessage result = new PictureEditResponseMessage();
        result.setType(response.getType());
        result.setMessage(response.getMessage());
        result.setEditAction(response.getEditAction());
        result.setUser(response.getUser());
        result.setIsEditor(recipient.equals(room.editor));
        return result;
    }

    private boolean send(WebSocketSession session, PictureEditResponseMessage response) {
        // Parser errors use the IO thread while room events use Disruptor workers.
        synchronized (session) {
            if (!session.isOpen()) return false;
            try {
                session.sendMessage(new TextMessage(objectMapper.writeValueAsString(response)));
                return true;
            } catch (IOException | RuntimeException exception) {
                log.warn("图片编辑消息发送失败，关闭连接 {}", session.getId());
                return false;
            }
        }
    }

    private void close(WebSocketSession session, CloseStatus status) {
        try {
            if (session.isOpen()) session.close(status);
        } catch (IOException | RuntimeException exception) {
            log.warn("图片编辑连接关闭失败 {}", session.getId());
        }
    }
}
