package com.autocare.server;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * auto-care 启动类。
 *
 * <p>{@code scanBasePackages = "com.autocare"} 是刻意写的：启动类自己住在
 * {@code com.autocare.server}，Spring 默认只扫这个包和它的子包。而 AI 模块的适配器实现住在
 * {@code com.autocare.ai}——不显式放宽扫描范围，将来 G3 把适配器注册成 bean 时会「代码明明写了、
 * 容器里就是没有」，且报错指向调用处而不是扫描范围，属于难查的一类。这里提前把边界说清楚。
 *
 * <p>本类不含任何业务逻辑：G0-02 只要求「服务能起来并监听端口」。
 */
@SpringBootApplication(scanBasePackages = "com.autocare")
public class AutoCareServerApplication {

    public static void main(String[] args) {
        SpringApplication.run(AutoCareServerApplication.class, args);
    }
}
