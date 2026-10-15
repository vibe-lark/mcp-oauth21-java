package com.example.mcpoauthdemo.config;

import java.net.URI;
import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "demo")
public class DemoProperties {

    private URI publicBaseUrl = URI.create("http://localhost:8080");
    private Duration flowTtl = Duration.ofMinutes(10);
    private Duration codeTtl = Duration.ofMinutes(2);
    private Duration tokenTtl = Duration.ofHours(1);
    private Duration refreshTokenTtl = Duration.ofDays(30);
    private final Feishu feishu = new Feishu();

    public URI getPublicBaseUrl() {
        return publicBaseUrl;
    }

    public void setPublicBaseUrl(URI publicBaseUrl) {
        this.publicBaseUrl = publicBaseUrl;
    }

    public Duration getFlowTtl() {
        return flowTtl;
    }

    public void setFlowTtl(Duration flowTtl) {
        this.flowTtl = flowTtl;
    }

    public Duration getCodeTtl() {
        return codeTtl;
    }

    public void setCodeTtl(Duration codeTtl) {
        this.codeTtl = codeTtl;
    }

    public Duration getTokenTtl() {
        return tokenTtl;
    }

    public void setTokenTtl(Duration tokenTtl) {
        this.tokenTtl = tokenTtl;
    }

    public Duration getRefreshTokenTtl() {
        return refreshTokenTtl;
    }

    public void setRefreshTokenTtl(Duration refreshTokenTtl) {
        this.refreshTokenTtl = refreshTokenTtl;
    }

    public Feishu getFeishu() {
        return feishu;
    }

    public String baseUrl() {
        String value = publicBaseUrl.toString();
        return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }

    public String resourceUrl() {
        return baseUrl() + "/mcp";
    }

    public String callbackUrl() {
        return baseUrl() + "/callback/feishu";
    }

    public String protectedResourceMetadataUrl() {
        return baseUrl() + "/.well-known/oauth-protected-resource/mcp";
    }

    public static class Feishu {
        private boolean mock = true;
        private String appId = "";
        private String appSecret = "";
        private String scopes = "";
        private String mockUserName = "小王";
        private String mockOpenId = "ou_demo_user";
        private String mockTenantKey = "tenant_demo";

        public boolean isMock() {
            return mock;
        }

        public void setMock(boolean mock) {
            this.mock = mock;
        }

        public String getAppId() {
            return appId;
        }

        public void setAppId(String appId) {
            this.appId = appId;
        }

        public String getAppSecret() {
            return appSecret;
        }

        public void setAppSecret(String appSecret) {
            this.appSecret = appSecret;
        }

        public String getScopes() {
            return scopes;
        }

        public void setScopes(String scopes) {
            this.scopes = scopes;
        }

        public String getMockUserName() {
            return mockUserName;
        }

        public void setMockUserName(String mockUserName) {
            this.mockUserName = mockUserName;
        }

        public String getMockOpenId() {
            return mockOpenId;
        }

        public void setMockOpenId(String mockOpenId) {
            this.mockOpenId = mockOpenId;
        }

        public String getMockTenantKey() {
            return mockTenantKey;
        }

        public void setMockTenantKey(String mockTenantKey) {
            this.mockTenantKey = mockTenantKey;
        }
    }
}
