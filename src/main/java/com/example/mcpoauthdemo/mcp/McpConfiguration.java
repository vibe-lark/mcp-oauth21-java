package com.example.mcpoauthdemo.mcp;

import java.util.EnumSet;
import java.util.List;
import java.util.Map;

import com.example.mcpoauthdemo.config.DemoProperties;
import com.example.mcpoauthdemo.feishu.FeishuClient;
import com.example.mcpoauthdemo.feishu.FeishuClient.FeishuUser;
import com.example.mcpoauthdemo.oauth.InMemoryOAuthStore;
import com.example.mcpoauthdemo.oauth.OAuthModels.McpAuthContext;
import com.example.mcpoauthdemo.security.CryptoSupport;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpStatelessSyncServer;
import io.modelcontextprotocol.server.transport.HttpServletStatelessServerTransport;
import io.modelcontextprotocol.spec.McpSchema;
import jakarta.servlet.DispatcherType;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.boot.web.servlet.ServletRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class McpConfiguration {

    private static final String AUTH_CONTEXT_KEY = "oauth";

    @Bean
    HttpServletStatelessServerTransport mcpTransport() {
        return HttpServletStatelessServerTransport.builder()
            .messageEndpoint("/mcp")
            .maxRequestSize(1024 * 1024)
            .contextExtractor(request -> {
                Object auth = request.getAttribute(McpAuthenticationFilter.AUTH_ATTRIBUTE);
                return auth == null ? McpTransportContext.EMPTY
                    : McpTransportContext.create(Map.of(AUTH_CONTEXT_KEY, auth));
            })
            .build();
    }

    @Bean
    ServletRegistrationBean<HttpServletStatelessServerTransport> mcpServlet(
        HttpServletStatelessServerTransport transport
    ) {
        ServletRegistrationBean<HttpServletStatelessServerTransport> registration =
            new ServletRegistrationBean<>(transport, "/mcp");
        registration.setName("mcpServlet");
        registration.setLoadOnStartup(1);
        registration.setAsyncSupported(true);
        return registration;
    }

    @Bean
    FilterRegistrationBean<McpAuthenticationFilter> mcpAuthenticationFilter(
        InMemoryOAuthStore store,
        DemoProperties properties,
        ObjectMapper objectMapper
    ) {
        FilterRegistrationBean<McpAuthenticationFilter> registration = new FilterRegistrationBean<>();
        registration.setFilter(new McpAuthenticationFilter(store, properties, objectMapper));
        registration.setName("mcpAuthenticationFilter");
        registration.addUrlPatterns("/mcp");
        registration.setDispatcherTypes(EnumSet.of(DispatcherType.REQUEST, DispatcherType.ASYNC));
        registration.setOrder(Integer.MIN_VALUE + 100);
        registration.setAsyncSupported(true);
        return registration;
    }

    @Bean(destroyMethod = "closeGracefully")
    McpStatelessSyncServer mcpServer(
        HttpServletStatelessServerTransport transport,
        FeishuClient feishuClient,
        CryptoSupport crypto,
        ObjectMapper objectMapper
    ) {
        McpSchema.Tool whoami = McpSchema.Tool.builder("whoami", Map.of(
                "type", "object",
                "additionalProperties", false
            ))
            .description("返回当前 MCP token 对应的飞书用户姓名和 open_id")
            .build();

        return McpServer.sync(transport)
            .serverInfo("mcp21-java-demo", "0.0.1")
            .capabilities(McpSchema.ServerCapabilities.builder().tools(false).build())
            .toolCall(whoami, (context, request) -> {
                McpAuthContext auth = (McpAuthContext) context.get(AUTH_CONTEXT_KEY);
                if (auth == null || !auth.hasScope("mcp:read")) {
                    return errorResult("The access token does not include mcp:read");
                }
                try {
                    String userAccessToken = crypto.decrypt(auth.grant().accessTokenEncrypted());
                    FeishuUser user = feishuClient.getUserInfo(userAccessToken);
                    String json = objectMapper.writeValueAsString(Map.of(
                        "name", user.name(),
                        "open_id", user.openId()
                    ));
                    return McpSchema.CallToolResult.builder()
                        .content(List.of(McpSchema.TextContent.builder(json).build()))
                        .isError(false)
                        .build();
                }
                catch (JsonProcessingException ex) {
                    return errorResult("Could not serialize the user identity");
                }
                catch (RuntimeException ex) {
                    return errorResult("Feishu user lookup failed: " + ex.getMessage());
                }
            })
            .build();
    }

    private McpSchema.CallToolResult errorResult(String message) {
        return McpSchema.CallToolResult.builder()
            .content(List.of(McpSchema.TextContent.builder(message).build()))
            .isError(true)
            .build();
    }
}
