package com.yupi.yupicturebackend.config;

import com.qcloud.cos.COSClient;
import com.qcloud.cos.ClientConfig;
import com.qcloud.cos.auth.BasicCOSCredentials;
import com.qcloud.cos.auth.COSCredentials;
import com.qcloud.cos.http.HttpProtocol;
import com.qcloud.cos.region.Region;
import lombok.Data;
import lombok.ToString;
import org.springframework.util.StringUtils;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConfigurationProperties(prefix = "cos.client")
@Data
public class CosClientConfig {

    /**
     * 域名
     */
    private String host;

    /**
     * secretId
     */
    @ToString.Exclude
    private String secretId;

    /**
     * 密钥（注意不要泄露）
     */
    @ToString.Exclude
    private String secretKey;

    /**
     * 区域
     */
    private String region;

    /**
     * 桶名
     */
    private String bucket;

    @Bean(destroyMethod = "shutdown")
    public COSClient cosClient() {
        if (!StringUtils.hasText(secretId) || !StringUtils.hasText(secretKey)
                || !StringUtils.hasText(region) || !StringUtils.hasText(bucket)
                || !StringUtils.hasText(host)) {
            throw new IllegalStateException("配置缺失：请填写 application-local.yml 或 COS_SECRET_ID、COS_SECRET_KEY、COS_REGION、COS_BUCKET、COS_HOST 环境变量");
        }
        // 1 初始化用户身份信息（secretId, secretKey）。
        // SECRETID 和 SECRETKEY 请登录访问管理控制台 https://console.cloud.tencent.com/cam/capi 进行查看和管理
        COSCredentials cred = new BasicCOSCredentials(secretId, secretKey);
        // 2 设置 bucket 的地域, COS 地域的简称请参见 https://cloud.tencent.com/document/product/436/6224
        // clientConfig 中包含了设置 region, https(默认 http), 超时, 代理等 set 方法, 使用可参见源码或者常见问题 Java SDK 部分。
        ClientConfig clientConfig = new ClientConfig(new Region(region));
        // 这里建议设置使用 https 协议
        // 从 5.6.54 版本开始，默认使用了 https
        clientConfig.setHttpProtocol(HttpProtocol.https);
        clientConfig.setConnectionTimeout(10000);
        clientConfig.setSocketTimeout(30000);
        // 3 生成 cos 客户端。
        return new COSClient(cred, clientConfig);
    }
}
