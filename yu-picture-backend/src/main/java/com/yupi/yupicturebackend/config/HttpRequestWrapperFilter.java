package com.yupi.yupicturebackend.config;

import org.springframework.http.MediaType;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import javax.servlet.*;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;

/**
 * 请求包装过滤器
 *
 * @author pine
 */
@Order(1)
@Component
public class HttpRequestWrapperFilter implements Filter {

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain) throws ServletException, IOException {
        if (request instanceof HttpServletRequest) {
            HttpServletRequest servletRequest = (HttpServletRequest) request;
            String contentType = servletRequest.getContentType();
            if (isJson(contentType)) {
                RequestWrapper wrapped;
                try {
                    wrapped = new RequestWrapper(servletRequest);
                } catch (RequestWrapper.BodyTooLargeException ex) {
                    if (response instanceof HttpServletResponse) {
                        ((HttpServletResponse) response).sendError(413, ex.getMessage());
                        return;
                    }
                    throw ex;
                }
                chain.doFilter(wrapped, response);
            } else {
                chain.doFilter(request, response);
            }
        } else {
            chain.doFilter(request, response);
        }
    }

    private boolean isJson(String value) {
        if (value == null) return false;
        try {
            MediaType type = MediaType.parseMediaType(value);
            return MediaType.APPLICATION_JSON.isCompatibleWith(type)
                    || ("application".equals(type.getType()) && type.getSubtype().endsWith("+json"));
        } catch (IllegalArgumentException ignored) {
            return false;
        }
    }

}
