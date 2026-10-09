package com.yupi.yupicturebackend.manager.auth;

import cn.dev33.satoken.stp.StpInterface;
import cn.hutool.extra.servlet.ServletUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONNull;
import cn.hutool.json.JSONUtil;
import com.yupi.yupicturebackend.exception.BusinessException;
import com.yupi.yupicturebackend.exception.ErrorCode;
import com.yupi.yupicturebackend.manager.auth.model.SpaceUserPermissionConstant;
import com.yupi.yupicturebackend.model.entity.Picture;
import com.yupi.yupicturebackend.model.entity.Space;
import com.yupi.yupicturebackend.model.entity.SpaceUser;
import com.yupi.yupicturebackend.model.entity.User;
import com.yupi.yupicturebackend.model.enums.PictureReviewStatusEnum;
import com.yupi.yupicturebackend.model.enums.SpaceRoleEnum;
import com.yupi.yupicturebackend.service.*;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import javax.annotation.Resource;
import javax.servlet.http.HttpServletRequest;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/** Permissions are derived from the current user and stored resource relationships. */
@Component
public class StpInterfaceImpl implements StpInterface {
    @Resource private UserService userService;
    @Resource private SpaceService spaceService;
    @Resource private SpaceUserService spaceUserService;
    @Resource private PictureService pictureService;
    @Resource private SpaceUserAuthManager spaceUserAuthManager;

    @Override
    public List<String> getPermissionList(Object loginId, String loginType) {
        if (!StpKit.SPACE_TYPE.equals(loginType)) return Collections.emptyList();
        HttpServletRequest request = ((ServletRequestAttributes) RequestContextHolder.currentRequestAttributes()).getRequest();
        User user = userService.getLoginUser(request);
        // Both sessions must refer to the same still-existing account.
        if (user == null || user.getId() == null || !String.valueOf(user.getId()).equals(String.valueOf(loginId))) {
            throw new BusinessException(ErrorCode.NOT_LOGIN_ERROR);
        }
        String path = request.getRequestURI().substring(request.getContextPath().length());
        SpaceUserAuthContext context = getAuthContextByRequest(request, path);
        Long spaceId = context.getSpaceId();
        if (context.getSpaceUserId() != null) {
            SpaceUser member = spaceUserService.getById(context.getSpaceUserId());
            if (member == null) throw new BusinessException(ErrorCode.NOT_FOUND_ERROR, "空间成员不存在");
            requireSameSpace(spaceId, member.getSpaceId());
            spaceId = member.getSpaceId();
        }
        if (context.getPictureId() != null) {
            Picture picture = pictureService.getById(context.getPictureId());
            if (picture == null) throw new BusinessException(ErrorCode.NOT_FOUND_ERROR, "图片不存在");
            requireSameSpace(spaceId, picture.getSpaceId());
            spaceId = picture.getSpaceId();
            if (spaceId == null) {
                if (Objects.equals(picture.getUserId(), user.getId()) || userService.isAdmin(user)) {
                    return spaceUserAuthManager.getPermissionsByRole(SpaceRoleEnum.ADMIN.getValue());
                }
                return Objects.equals(picture.getReviewStatus(), PictureReviewStatusEnum.PASS.getValue())
                        ? Collections.singletonList(SpaceUserPermissionConstant.PICTURE_VIEW)
                        : Collections.emptyList();
            }
        }
        if (spaceId != null) {
            Space space = spaceService.getById(spaceId);
            if (space == null) throw new BusinessException(ErrorCode.NOT_FOUND_ERROR, "空间不存在");
            return spaceUserAuthManager.getPermissionList(space, user);
        }
        // New public uploads have no resource yet; other empty contexts grant nothing.
        if ("POST".equals(request.getMethod())
                && ("/picture/upload".equals(path) || "/picture/upload/url".equals(path))) {
            return Collections.singletonList(SpaceUserPermissionConstant.PICTURE_UPLOAD);
        }
        return Collections.emptyList();
    }

    private void requireSameSpace(Long requested, Long stored) {
        if (requested != null && !Objects.equals(requested, stored)) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "资源与空间不匹配");
        }
    }

    private SpaceUserAuthContext getAuthContextByRequest(HttpServletRequest request, String path) {
        try {
            String contentType = request.getContentType();
            MediaType mediaType = contentType == null ? null : MediaType.parseMediaType(contentType);
            boolean json = mediaType != null && (MediaType.APPLICATION_JSON.isCompatibleWith(mediaType)
                    || mediaType.getSubtype().endsWith("+json"));
            JSONObject params = json && !"GET".equals(request.getMethod())
                    ? JSONUtil.parseObj(ServletUtil.getBody(request))
                    : new JSONObject(ServletUtil.getParamMap(request));
            // Read identifiers only; nested entities and submitted roles are untrusted.
            SpaceUserAuthContext context = new SpaceUserAuthContext();
            context.setSpaceId(readId(params, "spaceId"));
            context.setPictureId(readId(params, "pictureId"));
            context.setSpaceUserId(readId(params, "spaceUserId"));
            Long id = readId(params, "id");
            if (path.startsWith("/picture/")) {
                if (context.getSpaceUserId() != null) throw new BusinessException(ErrorCode.PARAMS_ERROR);
                context.setPictureId(mergeId(id, context.getPictureId()));
            } else if (path.startsWith("/spaceUser/")) {
                if (context.getPictureId() != null) throw new BusinessException(ErrorCode.PARAMS_ERROR);
                context.setSpaceUserId(mergeId(id, context.getSpaceUserId()));
            } else if (path.startsWith("/space/")) {
                if (context.getPictureId() != null || context.getSpaceUserId() != null) throw new BusinessException(ErrorCode.PARAMS_ERROR);
                context.setSpaceId(mergeId(id, context.getSpaceId()));
            } else {
                throw new BusinessException(ErrorCode.NO_AUTH_ERROR);
            }
            return context;
        } catch (BusinessException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "无效的资源参数");
        }
    }

    private Long readId(JSONObject params, String key) {
        Object value = params.get(key);
        if (value == null || value == JSONNull.NULL || "".equals(value)) return null;
        String text = value.toString();
        if (!text.matches("[1-9][0-9]*")) throw new BusinessException(ErrorCode.PARAMS_ERROR, "无效的资源 ID");
        return Long.valueOf(text);
    }

    private Long mergeId(Long id, Long alias) {
        if (id != null && alias != null && !id.equals(alias)) throw new BusinessException(ErrorCode.PARAMS_ERROR, "资源 ID 不一致");
        return id == null ? alias : id;
    }

    @Override public List<String> getRoleList(Object loginId, String loginType) {
        return Collections.emptyList();
    }
}
