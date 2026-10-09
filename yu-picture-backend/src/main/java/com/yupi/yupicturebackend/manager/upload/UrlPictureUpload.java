package com.yupi.yupicturebackend.manager.upload;

import com.yupi.yupicturebackend.exception.BusinessException;
import com.yupi.yupicturebackend.exception.ErrorCode;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;

/** Downloads exactly one bounded HTTP response to a public address. */
@Service
public class UrlPictureUpload extends PictureUploadTemplate {
    @Override
    protected void validPicture(Object inputSource) {
        URI uri = parseUrl(inputSource);
        try {
            resolvePublicAddresses(uri.getHost());
        } catch (UnknownHostException exception) {
            throw invalidUrl();
        }
    }

    private URI parseUrl(Object value) {
        try {
            if (!(value instanceof String) || ((String) value).length() > 2048) throw invalidUrl();
            URI uri = URI.create((String) value);
            if (!("https".equalsIgnoreCase(uri.getScheme()) || "http".equalsIgnoreCase(uri.getScheme()))
                    || uri.getHost() == null || uri.getUserInfo() != null || uri.getFragment() != null
                    || (uri.getPort() != -1 && uri.getPort() != 80 && uri.getPort() != 443)) throw invalidUrl();
            return uri;
        } catch (IllegalArgumentException exception) {
            throw invalidUrl();
        }
    }

    static InetAddress[] resolvePublicAddresses(String host) throws UnknownHostException {
        InetAddress[] addresses = InetAddress.getAllByName(host);
        for (InetAddress address : addresses) {
            if (!isPublicAddress(address)) throw new UnknownHostException("Non-public destination");
        }
        return addresses;
    }

    static boolean isPublicAddress(InetAddress address) {
        if (address.isAnyLocalAddress() || address.isLoopbackAddress() || address.isLinkLocalAddress()
                || address.isSiteLocalAddress() || address.isMulticastAddress()) return false;
        byte[] bytes = address.getAddress();
        int first = bytes[0] & 255;
        if (bytes.length == 4) {
            int second = bytes[1] & 255;
            return first != 0 && first != 127 && first < 224
                    && !(first == 100 && second >= 64 && second <= 127)
                    && !(first == 169 && second == 254)
                    && !(first == 192 && (second == 0 || second == 168))
                    && !(first == 198 && (second == 18 || second == 19 || second == 51))
                    && !(first == 203 && second == 0);
        }
        return (first & 0xe0) == 0x20 && !(first == 0x20 && bytes[1] == 0x02)
                && !(first == 0x20 && bytes[1] == 0x01 && (bytes[2] & 255) < 2)
                && !(first == 0x20 && bytes[1] == 0x01 && bytes[2] == 0x0d && (bytes[3] & 255) == 0xb8);
    }

    @Override
    protected String getOriginFilename(Object inputSource) {
        String path = parseUrl(inputSource).getPath();
        String name = path == null ? "image" : path.substring(path.lastIndexOf('/') + 1);
        return name.isEmpty() ? "image" : name;
    }

    @Override
    protected void processFile(Object inputSource, File file) throws Exception {
        URI uri = parseUrl(inputSource);
        RequestConfig config = RequestConfig.custom().setConnectTimeout(5000)
                .setConnectionRequestTimeout(5000).setSocketTimeout(5000).build();
        // The resolver validates the addresses handed to the socket, preventing DNS rebinding.
        try (CloseableHttpClient client = HttpClients.custom().setDefaultRequestConfig(config)
                .setDnsResolver(UrlPictureUpload::resolvePublicAddresses)
                .disableRedirectHandling().disableAutomaticRetries().disableCookieManagement().build();
             CloseableHttpResponse response = client.execute(new HttpGet(uri))) {
            if (response.getStatusLine().getStatusCode() != 200 || response.getEntity() == null) throw invalidUrl();
            if (response.getEntity().getContentLength() > MAX_FILE_SIZE) throw invalidUrl();
            try (InputStream input = response.getEntity().getContent(); FileOutputStream output = new FileOutputStream(file)) {
                copyBounded(input, output);
            }
        }
    }

    private static BusinessException invalidUrl() {
        return new BusinessException(ErrorCode.PARAMS_ERROR, "仅支持公网 HTTP(S) 图片地址，禁止重定向和非标准端口");
    }
}
