package com.invr.web.config;

import org.apache.catalina.connector.Connector;
import org.springframework.boot.web.embedded.tomcat.TomcatServletWebServerFactory;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 双访问监听：TCP 9080（局域网直连）+ fnOS 统一网关 Unix Socket。
 *
 * <p>零配置：是否启用网关完全由 fnOS 运行环境自动判断——
 * 当存在环境变量 {@code TRIM_APPDEST}（fnOS 安装后系统自动注入）时，自动追加一个
 * 监听 {@code ${TRIM_APPDEST}/app.sock} 的 Tomcat Connector；本地开发没有该变量时，
 * 只监听 TCP 9080，行为与原来完全一致。</p>
 *
 * <p>Tomcat 10.1 NIO 原生支持 Unix Domain Socket，无需 APR/native 库。</p>
 */
@Configuration
public class UdsConnectorConfig {

    @Bean
    public WebServerFactoryCustomizer<TomcatServletWebServerFactory> udsConnectorCustomizer() {
        return factory -> {
            String appDest = System.getenv("TRIM_APPDEST");
            if (appDest == null || appDest.isBlank()) {
                return; // 本地开发 / 非 fnOS 环境：纯 TCP 直连
            }
            String sock = appDest.trim() + "/app.sock";
            Connector uds = new Connector("org.apache.coyote.http11.Http11NioProtocol");
            uds.setProperty("unixDomainSocketPath", sock);
            uds.setProperty("unixDomainSocketPathPermissions", "rw-rw-rw-");
            factory.addAdditionalTomcatConnectors(uds);
        };
    }
}
