package com.autocare.server.security;

import jakarta.annotation.PostConstruct;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;

/**
 * JWT 相关配置，前缀 {@code autocare.jwt}。
 *
 * <p><b>密钥只从环境变量来</b>：配置里写的是 {@code ${JWT_SECRET:}}，仓库里永远只有占位。
 * 密钥不入日志、不入证据文件——这一点由取证脚本另做一次 0 命中检查。
 *
 * <p><b>为什么在启动时就校验长度</b>：HS256 要求密钥至少 256 位（32 字节）。给短了的话，
 * Nimbus 要到第一次签发或验证时才抛 {@code WeakKeyException}——那时已经跑起来、报错还指向
 * 某个请求。在这里直接卡住，报错信息能说清"哪个变量、差多少"，属于把故障挪到最早哪一步。
 */
@Component
@ConfigurationProperties(prefix = "autocare.jwt")
public class JwtProperties {

    /** HS256 的最小密钥长度：256 位 = 32 字节。 */
    private static final int MIN_SECRET_BYTES = 32;

    /** 签名密钥，来源环境变量 JWT_SECRET。 */
    private String secret;

    /** 签发者，写进 iss 并校验。本片自行拍板取值，回报总控追认。 */
    private String issuer = "auto-care";

    /** 受众，写进 aud 并校验。本片自行拍板取值。 */
    private String audience = "auto-care-api";

    /** 访问令牌有效期，契约规定演示值 1800 秒（30 分钟）。 */
    private long ttlSeconds = 1800;

    @PostConstruct
    void validate() {
        if (secret == null || secret.isBlank()) {
            throw new IllegalStateException(
                    "JWT_SECRET 没有注入：HS256 的签名密钥必须由环境变量提供，仓库里只有 ${JWT_SECRET:} 占位。");
        }
        if (secretBytes().length < MIN_SECRET_BYTES) {
            throw new IllegalStateException(
                    "JWT_SECRET 太短：HS256 要求至少 " + MIN_SECRET_BYTES + " 字节（256 位），当前 "
                            + secretBytes().length + " 字节。");
        }
        if (ttlSeconds <= 0) {
            throw new IllegalStateException("autocare.jwt.ttl-seconds 必须为正数，当前 " + ttlSeconds + "。");
        }
    }

    public byte[] secretBytes() {
        return secret == null ? new byte[0] : secret.getBytes(StandardCharsets.UTF_8);
    }

    public SecretKey secretKey() {
        return new SecretKeySpec(secretBytes(), "HmacSHA256");
    }

    public String getSecret() {
        return secret;
    }

    public void setSecret(String secret) {
        this.secret = secret;
    }

    public String getIssuer() {
        return issuer;
    }

    public void setIssuer(String issuer) {
        this.issuer = issuer;
    }

    public String getAudience() {
        return audience;
    }

    public void setAudience(String audience) {
        this.audience = audience;
    }

    public long getTtlSeconds() {
        return ttlSeconds;
    }

    public void setTtlSeconds(long ttlSeconds) {
        this.ttlSeconds = ttlSeconds;
    }
}
