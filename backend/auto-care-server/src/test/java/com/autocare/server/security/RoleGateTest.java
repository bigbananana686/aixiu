package com.autocare.server.security;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 403 角色闸门验证。
 *
 * <p><b>为什么受保护路由必须住在测试目录里</b>：本片三个接口都是"登录即可访问"，
 * 没有任何角色受限端点。要验 403 就得有一条"要 OWNER 才能进"的路径——但那条路径
 * **不能进生产代码**：仓库里会多出一个只为测试存在的假接口，被打进 fat jar，
 * 还可能被误当成真接口。放 {@code src/test/java}，它只在这个测试的上下文里存在。
 *
 * <p>测试链用 {@code securityMatcher("/test/**")} 只覆盖测试路径，并且因为它是后注册的
 * Bean 而生产链在它之前，需要显式让测试链优先——用 {@code @Order(1)} 标在 Bean 上。
 * 生产链仍然兜住其余路径，行为不变。
 *
 * <p>三条用例对应契约 §1 的判定顺序：<b>未认证 401 → 已认证但角色不允许 403</b>。
 * 两者不能混：401 是"不知道你是谁"（重新登录有用），403 是"知道你是谁但你没权限"
 * （重新登录没用）。
 */
@SpringBootTest
@AutoConfigureMockMvc
class RoleGateTest {

    @Autowired
    private MockMvc mockMvc;

    @TestConfiguration
    static class TestOnlyRoutes {

        /**
         * 只为测试存在的受保护路径。生产代码里没有它，也不会被打进产物。
         *
         * <p><b>注意它不要再写成 {@code @Bean}</b>：{@code @RestController} 本身是组件注解，
         * 会被 {@code @ComponentScan}（本应用扫 {@code com.autocare}）扫到并注册一次；
         * 再显式声明一次就会出现两份定义，`requestMappingHandlerMapping` 看到同一路径映射两次，
         * 启动直接失败：{@code Ambiguous mapping. Cannot map 'ownerOnlyTestController' method}。
         * （2026-10-24 实测就是这么炸的。）
         */
        @RestController
        static class OwnerOnlyTestController {
            @GetMapping("/test/owner-only")
            String ownerOnly() {
                return "ok";
            }
        }

        @Bean
        @org.springframework.core.annotation.Order(1)
        SecurityFilterChain testOnlyRoleChain(HttpSecurity http,
                                             ApiAuthenticationEntryPoint authenticationEntryPoint,
                                             ApiAccessDeniedHandler accessDeniedHandler) throws Exception {
            http.securityMatcher("/test/**")
                    .csrf(AbstractHttpConfigurer::disable)
                    .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                    .formLogin(AbstractHttpConfigurer::disable)
                    .httpBasic(AbstractHttpConfigurer::disable)
                    .authorizeHttpRequests(auth -> auth.anyRequest().hasRole("OWNER"))
                    .exceptionHandling(ex -> ex
                            .authenticationEntryPoint(authenticationEntryPoint)
                            .accessDeniedHandler(accessDeniedHandler));
            return http.build();
        }
    }

    /** OWNER 能进：证明这条闸门不是"谁都挡"。 */
    @Test
    @WithMockUser(username = "owner-probe", roles = "OWNER")
    void ownerPassesTheGate() throws Exception {
        mockMvc.perform(get("/test/owner-only"))
                .andExpect(status().isOk());
    }

    /** TECH 被挡：403，且必须是**项目信封**，不是 Spring 的默认响应。 */
    @Test
    @WithMockUser(username = "tech-probe", roles = "TECH")
    void techIsForbiddenInProjectEnvelope() throws Exception {
        mockMvc.perform(get("/test/owner-only"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("FORBIDDEN"))
                .andExpect(jsonPath("$.error.message").value("当前角色不允许执行该操作"))
                .andExpect(jsonPath("$.error.retryable").value(false))
                .andExpect(jsonPath("$.error.fieldErrors").isArray())
                .andExpect(jsonPath("$.error.details").isMap())
                .andExpect(jsonPath("$.requestId").isNotEmpty())
                // 响应头与 body 的编号必须一致
                .andExpect(header().exists("X-Request-Id"))
                // 403 不返回任何资源细节
                .andExpect(jsonPath("$.data").doesNotExist());
    }

    /** 匿名请求要 401 而不是 403：判定顺序里"未认证"排在"角色不允许"前面。 */
    @Test
    void anonymousIsUnauthenticatedNotForbidden() throws Exception {
        mockMvc.perform(get("/test/owner-only"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("UNAUTHENTICATED"))
                .andExpect(header().string("WWW-Authenticate", "Bearer"));
    }
}
