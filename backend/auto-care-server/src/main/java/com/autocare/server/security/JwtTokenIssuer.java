package com.autocare.server.security;

import com.autocare.common.identity.Role;
import com.nimbusds.jose.jwk.source.ImmutableSecret;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;

/**
 * 签发访问令牌。只做一件事：把「谁 + 什么角色」签成一段短期可验证的字符串。
 *
 * <p><b>令牌里装什么、不装什么</b>：
 * <ul>
 *   <li>{@code sub} = userId，{@code iss}/{@code aud}/{@code iat}/{@code nbf}/{@code exp} 按契约给全。</li>
 *   <li>{@code role} 只是**副本**。契约明确「角色与 enabled 仍须从可信账号状态核验，不能只相信
 *       可变 JWT 字段」——所以后续每个请求都会重新查库，这个 claim 不用来授权。
 *       装它只为了让排查问题时一眼看出这枚令牌当初是给谁签的。</li>
 *   <li>**不装密码、不装哈希、不装 loginName**：令牌会出现在客户端、可能被贴进日志或截图，
 *       能少放就少放。</li>
 * </ul>
 *
 * <p>签名算法与解码器白名单是同一个 HS256，密钥同一个来源，所以"签得出"必然"验得过"。
 */
@Component
public class JwtTokenIssuer {

    private final JwtProperties props;
    private final JwtEncoder encoder;

    public JwtTokenIssuer(JwtProperties props) {
        this.props = props;
        // ImmutableSecret 把单一密钥包成 JWKSource：项目只用一把对称密钥，不引入密钥轮换
        this.encoder = new NimbusJwtEncoder(new ImmutableSecret<>(props.secretKey()));
    }

    /** 有效期秒数（写进响应 expiresInSeconds）。 */
    public long ttlSeconds() {
        return props.getTtlSeconds();
    }

    /** 签发时刻 + 有效期 = 到期时刻，用于响应里的 expiresAt。 */
    public Instant expiresAt(Instant issuedAt) {
        return issuedAt.plusSeconds(props.getTtlSeconds());
    }

    public String issue(long userId, Role role, Instant issuedAt) {
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(props.getIssuer())
                .audience(List.of(props.getAudience()))
                .subject(String.valueOf(userId))
                .issuedAt(issuedAt)
                .notBefore(issuedAt)
                .expiresAt(expiresAt(issuedAt))
                .claim("role", role.name())
                .build();

        JwsHeader header = JwsHeader.with(MacAlgorithm.HS256).build();
        return encoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
    }
}
