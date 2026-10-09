package com.yupi.yupicturebackend.manager;

import com.qcloud.cos.COSClient;
import com.qcloud.cos.ClientConfig;
import com.qcloud.cos.auth.BasicCOSCredentials;
import com.qcloud.cos.http.HttpProtocol;
import com.qcloud.cos.endpoint.UserSpecifiedEndpointBuilder;
import com.qcloud.cos.region.Region;
import com.yupi.yupicturebackend.config.CosClientConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Map;
import java.util.stream.Collectors;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import static org.junit.jupiter.api.Assertions.*;

class PictureUrlSignerTest {
    private static final String HOST = "https://cos-fixture.example.invalid";
    private COSClient client;
    private PictureUrlSigner signer;

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
        signer = new PictureUrlSigner(client, config);
    }

    @AfterEach
    void tearDown() {
        client.shutdown();
    }

    @Test
    void signsTheFullObjectKeyWithHostBoundFifteenMinuteGetSignature() throws Exception {
        long before = Instant.now().getEpochSecond();
        URI signed = URI.create(signer.sign(HOST + "/public/123/photo.webp"));

        assertEquals("/public/123/photo.webp", signed.getPath());
        assertEquals(URI.create(HOST).getHost(), signed.getHost());
        assertEquals("https", signed.getScheme());
        assertNotNull(signed.getRawQuery(), "The response needs a signed URL, not the stored canonical URL");
        Map<String, String> query = parseQuery(signed);
        assertEquals("sha1", query.get("q-sign-algorithm"));
        assertEquals("host", query.get("q-header-list"));
        assertEquals("test-id", query.get("q-ak"));
        assertTrue(query.get("q-signature").matches("[0-9a-f]{40}"));
        String[] interval = query.get("q-sign-time").split(";");
        long expiration = Long.parseLong(interval[1]);
        assertTrue(expiration >= before + 899);
        assertTrue(expiration <= Instant.now().getEpochSecond() + 900);
        // Independently verify the COS V5 signature for this literal GET request.
        String httpRequest = "get\n/public/123/photo.webp\n\nhost=cos-fixture.example.invalid\n";
        String requestHash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-1")
                .digest(httpRequest.getBytes(StandardCharsets.UTF_8)));
        String signKey = hmac("test-key", query.get("q-key-time"));
        String stringToSign = "sha1\n" + query.get("q-key-time") + "\n" + requestHash + "\n";
        assertEquals(hmac(signKey, stringToSign), query.get("q-signature"));
        assertNotEquals(query.get("q-signature"),
                parseQuery(URI.create(signer.sign(HOST + "/public/123/photo_thumbnail.webp"))).get("q-signature"));
    }

    @Test
    void decodesAnEncodedKeyExactlyOnceWithoutTreatingPlusAsASpace() {
        URI signed = URI.create(signer.sign(HOST + "/public/%E7%8C%AB%20a+b.webp"));
        assertEquals("/public/猫 a+b.webp", signed.getPath());
        assertNotNull(signed.getRawQuery());
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {
            "https://other.example/public/photo.webp",
            "https://cos-fixture.example.invalid.evil.example/photo.webp",
            "https://cos-fixture.example.invalid@evil.example/photo.webp",
            "https://user@cos-fixture.example.invalid/photo.webp",
            "http://cos-fixture.example.invalid/photo.webp",
            "https://cos-fixture.example.invalid:8443/photo.webp",
            "https://cos-fixture.example.invalid/",
            "https://cos-fixture.example.invalid/public/../photo.webp",
            "https://cos-fixture.example.invalid/public/%2e%2e/photo.webp",
            "https://cos-fixture.example.invalid/public/%5cphoto.webp",
            "https://cos-fixture.example.invalid/public/%00photo.webp",
            "https://cos-fixture.example.invalid/public/photo.webp?download=1",
            "https://cos-fixture.example.invalid/public/photo.webp#preview",
            "not a URL"
    })
    void leavesForeignOrNonCanonicalUrlsUnsigned(String input) {
        assertEquals(input, signer.sign(input));
    }

    @Test
    void doesNotRenewAUrlThatAlreadyHasSignatureParameters() {
        String signed = signer.sign(HOST + "/public/photo.webp");
        assertEquals(signed, signer.sign(signed));
    }

    private static Map<String, String> parseQuery(URI uri) {
        return Arrays.stream(uri.getRawQuery().split("&"))
                .map(pair -> pair.split("=", 2))
                .collect(Collectors.toMap(pair -> pair[0],
                        pair -> URLDecoder.decode(pair[1], StandardCharsets.UTF_8)));
    }

    private static String hmac(String key, String text) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA1");
        mac.init(new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), "HmacSHA1"));
        return HexFormat.of().formatHex(mac.doFinal(text.getBytes(StandardCharsets.UTF_8)));
    }
}
