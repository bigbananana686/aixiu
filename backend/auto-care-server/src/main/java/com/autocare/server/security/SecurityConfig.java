package com.autocare.server.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimNames;
import org.springframework.security.oauth2.jwt.JwtClaimValidator;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.web.SecurityFilterChain;

import java.util.List;

/**
 * Spring Security 配置（DISPATCH-009 DECIDE-1：用户拍板走 Spring Security 全套）。
 *
 * <p><b>这张表就是"角色闸门"本身</b>：哪条路径公开、哪条要认证——由这里一句话决定，
 * 而不是散在 Controller 的 if 里。本片只有两个公开入口，其余全部要认证。
 *
 * <p><b>算法白名单</b>：解码器用 {@code MacAlgorithm.HS256} 固定下来。含义是"服务端只认 HS256"——
 * 令牌头部自称 {@code alg: none} 或 {@code alg: RS256} 都不影响我们的判定，
 * 因为我们根本不按它自称的算法来验。这是防 {@code alg=none} 这类伪造的基本姿势。
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    /** 公开入口：只有注册与登录。其余一律要认证。 */
    private static final String[] PUBLIC_ENDPOINTS = {
            "/api/v1/auth/register",
            "/api/v1/auth/login"
    };

    @Bean
    public SecurityFilterChain apiFilterChain(HttpSecurity http,
                                             ApiAuthenticationEntryPoint authenticationEntryPoint,
                                             ApiAccessDeniedHandler accessDeniedHandler) throws Exception {
        http
                // 无 Cookie 状态认证（令牌只在 Authorization 头里），因此不需要 CSRF 防护。
                // 副作用正是我们要的：401 响应里不再出现 Set-Cookie: JSESSIONID。
                .csrf(AbstractHttpConfigurer::disable)
                // CORS 允许源属部署决定，本片不开；开了要显式列出允许源，不能用 * 配凭证。
                .cors(AbstractHttpConfigurer::disable)
                // 不建会话：每个请求都自带凭证，服务端不记状态
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .formLogin(AbstractHttpConfigurer::disable)
                // 关掉 Basic：契约要的挑战头是 Bearer，不是 Basic。
                // （第 2 节那条 401 里出现 WWW-Authenticate: Basic realm="Realm"，就是这个默认值。）
                .httpBasic(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(PUBLIC_ENDPOINTS).permitAll()
                        // 本片还没有角色受限端点；第 6 节会用测试态路由验证 403。
                        // 真实角色闸门（.hasRole / .hasAnyRole）随 G1 的接口一起加。
                        .anyRequest().authenticated())
                .oauth2ResourceServer(oauth2 -> oauth2
                        // 令牌解不开（过期/签名不对/格式不对）时，走我们的信封
                        .authenticationEntryPoint(authenticationEntryPoint)
                        .jwt(Customizer.withDefaults()))
                .exceptionHandling(ex -> ex
                        // 没带凭证就走到了鉴权判定，也走同一个信封
                        .authenticationEntryPoint(authenticationEntryPoint)
                        // 已认证但角色不够 → 403
                        .accessDeniedHandler(accessDeniedHandler));
        return http.build();
    }

    /**
     * 密码哈希。
     *
     * <p>强度取 <b>10</b>（自行拍板，回报总控追认）：与种子数据里那批 {@code $2a$10$...} 的
     * cost 一致——登录必须能校验种子哈希，校验时 BCrypt 用的是哈希里自带的 cost，
     * 所以取值只需保证**新注册**与种子同一档次即可，10 也正是 Spring 的默认值。
     */
    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(10);
    }

    /**
     * JWT 解码器：HS256 固定算法 + 校验签名、iss、aud、exp、nbf。
     *
     * <p>iss / aud 的取值是自行拍板（{@code autocare.jwt.issuer} / {@code .audience}），
     * 回报总控追认。校验用的密钥与签发用的是同一个 {@link JwtProperties#secretKey()}。
     */
    @Bean
    public JwtDecoder jwtDecoder(JwtProperties props) {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withSecretKey(props.secretKey())
                .macAlgorithm(MacAlgorithm.HS256)
                .build();

        // 时间类声明（exp / nbf，带默认容差）+ iss
        OAuth2TokenValidator<Jwt> timestampAndIssuer = JwtValidators.createDefaultWithIssuer(props.getIssuer());
        // aud 要自己加：默认校验器不管它
        OAuth2TokenValidator<Jwt> audience = new JwtClaimValidator<List<String>>(
                JwtClaimNames.AUD,
                audiences -> audiences != null && audiences.contains(props.getAudience()));

        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(timestampAndIssuer, audience));
        return decoder;
    }
}
