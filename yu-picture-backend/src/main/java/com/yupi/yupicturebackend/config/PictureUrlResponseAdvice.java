package com.yupi.yupicturebackend.config;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.yupi.yupicturebackend.common.BaseResponse;
import com.yupi.yupicturebackend.manager.PictureUrlSigner;
import com.yupi.yupicturebackend.model.entity.Picture;
import com.yupi.yupicturebackend.model.vo.PictureVO;
import org.springframework.beans.BeanUtils;
import org.springframework.core.MethodParameter;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyAdvice;

import java.util.Collection;
import java.util.stream.Collectors;

/** Signs outgoing picture copies without changing database entities or cached response objects. */
@RestControllerAdvice(basePackages = "com.yupi.yupicturebackend.controller")
public class PictureUrlResponseAdvice implements ResponseBodyAdvice<Object> {
    private final PictureUrlSigner signer;

    public PictureUrlResponseAdvice(PictureUrlSigner signer) {
        this.signer = signer;
    }

    @Override
    public boolean supports(MethodParameter returnType, Class<? extends HttpMessageConverter<?>> converterType) {
        return BaseResponse.class.isAssignableFrom(returnType.getParameterType())
                && MappingJackson2HttpMessageConverter.class.isAssignableFrom(converterType);
    }

    @Override
    public Object beforeBodyWrite(Object body, MethodParameter returnType, MediaType selectedContentType,
                                  Class<? extends HttpMessageConverter<?>> selectedConverterType,
                                  ServerHttpRequest request, ServerHttpResponse response) {
        return copyForResponse(body);
    }

    private Object copyForResponse(Object value) {
        if (value instanceof BaseResponse<?>) {
            BaseResponse<?> original = (BaseResponse<?>) value;
            return new BaseResponse<>(original.getCode(), copyForResponse(original.getData()), original.getMessage());
        }
        if (value instanceof Page<?>) {
            Page<?> original = (Page<?>) value;
            Page<Object> copy = new Page<>();
            BeanUtils.copyProperties(original, copy, "records");
            copy.setRecords(original.getRecords().stream().map(this::copyForResponse).collect(Collectors.toList()));
            return copy;
        }
        if (value instanceof Collection<?>) {
            return ((Collection<?>) value).stream().map(this::copyForResponse).collect(Collectors.toList());
        }
        if (value instanceof PictureVO) {
            PictureVO original = (PictureVO) value;
            PictureVO copy = new PictureVO();
            BeanUtils.copyProperties(original, copy);
            copy.setUrl(signer.sign(original.getUrl()));
            copy.setThumbnailUrl(signer.sign(original.getThumbnailUrl()));
            return copy;
        }
        if (value instanceof Picture) {
            Picture original = (Picture) value;
            Picture copy = new Picture();
            BeanUtils.copyProperties(original, copy);
            copy.setUrl(signer.sign(original.getUrl()));
            copy.setThumbnailUrl(signer.sign(original.getThumbnailUrl()));
            return copy;
        }
        return value;
    }
}
