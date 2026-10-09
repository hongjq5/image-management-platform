package com.yupi.yupicturebackend.manager;

import com.qcloud.cos.COSClient;
import com.qcloud.cos.http.HttpMethodName;
import com.yupi.yupicturebackend.config.CosClientConfig;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.util.Date;

/** Issues temporary read URLs at response boundaries; persisted URLs stay canonical. */
@Component
public class PictureUrlSigner {
    private static final long EXPIRATION_MILLIS = 15L * 60 * 1000;

    private final COSClient cosClient;
    private final CosClientConfig config;
    private final URI allowedOrigin;

    public PictureUrlSigner(COSClient cosClient, CosClientConfig config) {
        this.cosClient = cosClient;
        this.config = config;
        this.allowedOrigin = parseUrl(config.getHost());
        if (allowedOrigin == null || allowedOrigin.getRawQuery() != null
                || allowedOrigin.getRawFragment() != null
                || !(allowedOrigin.getRawPath().isEmpty() || "/".equals(allowedOrigin.getRawPath()))) {
            throw new IllegalArgumentException("COS host must be an HTTP(S) origin without a path or query");
        }
    }

    /**
     * Signs only canonical URLs belonging to the configured COS origin.
     * Foreign, malformed and already signed URLs are returned unchanged and are never signed.
     * The caller must authorize access to the picture before calling this method.
     */
    public String sign(String canonicalUrl) {
        URI url = parseUrl(canonicalUrl);
        if (url == null || !sameOrigin(url) || url.getRawQuery() != null || url.getRawFragment() != null) {
            return canonicalUrl;
        }
        String path = url.getPath();
        if (path == null || !path.startsWith("/") || path.length() <= 1) {
            return canonicalUrl;
        }
        // URI decodes escaped characters once and preserves literal plus signs.
        String key = path.substring(1);
        if (!validKey(key)) {
            return canonicalUrl;
        }
        return cosClient.generatePresignedUrl(config.getBucket(), key,
                new Date(System.currentTimeMillis() + EXPIRATION_MILLIS), HttpMethodName.GET).toExternalForm();
    }

    private boolean sameOrigin(URI url) {
        return allowedOrigin.getScheme().equalsIgnoreCase(url.getScheme())
                && allowedOrigin.getHost().equalsIgnoreCase(url.getHost())
                && effectivePort(allowedOrigin) == effectivePort(url);
    }

    private static int effectivePort(URI url) {
        return url.getPort() >= 0 ? url.getPort() : ("https".equalsIgnoreCase(url.getScheme()) ? 443 : 80);
    }

    private static URI parseUrl(String value) {
        if (value == null || value.isEmpty()) {
            return null;
        }
        try {
            URI url = new URI(value);
            if (url.getHost() == null || url.getRawUserInfo() != null
                    || !("https".equalsIgnoreCase(url.getScheme()) || "http".equalsIgnoreCase(url.getScheme()))) {
                return null;
            }
            return url;
        } catch (URISyntaxException e) {
            return null;
        }
    }

    private static boolean validKey(String key) {
        if (key.getBytes(StandardCharsets.UTF_8).length > 1024 || key.indexOf('\\') >= 0
                || key.chars().anyMatch(Character::isISOControl)) {
            return false;
        }
        for (String segment : key.split("/", -1)) {
            if (segment.isEmpty() || ".".equals(segment) || "..".equals(segment)) {
                return false;
            }
        }
        return true;
    }
}
