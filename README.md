[豆包工作MCP OAuth 2.1：快速指南与标准接口清单](https://bytedance.larkoffice.com/wiki/J67uwaisKilV8MkkWmAcfOPKnDd?from=wiki)

# Java MCP OAuth 2.1 Demo

通过飞书登录授权，将 MCP 服务接入豆包工作。示例工具 `whoami` 返回当前授权用户的姓名和 `open_id`。

基于 Spring Boot 和 MCP Java SDK，支持动态客户端注册、授权码流程、S256 PKCE、MCP 令牌签发与刷新，以及 Streamable HTTP。**Java 17 / 21 均已验证，无需数据库。**

![在豆包工作中安装、授权并调用 whoami 的效果](docs/images/mcp21-java-demo-result.gif)

## 快速接入豆包工作

### 1. 准备飞书应用

1. 在飞书开放平台创建企业自建应用，获取 App ID 和 App Secret。
2. 在应用“安全设置”中添加重定向 URL：`https://localhost:8080/callback/feishu`。
3. 按业务需要开通用户权限并发布应用。配置模板使用 `contact:user.employee_id:readonly offline_access`。

### 2. 配置并启动 HTTPS 服务

在项目根目录执行：

```bash
cp .env.local.example .env.local
chmod 600 .env.local
chmod +x mvnw run-local.sh scripts/generate-local-cert.sh
```

编辑 `.env.local`，填写自己的 `FEISHU_APP_ID`、`FEISHU_APP_SECRET` 和 `FEISHU_SCOPES`。本地 HTTPS 配置已在模板中提供：

```dotenv
PUBLIC_BASE_URL=https://localhost:8080
PORT=8080
SSL_ENABLED=true
SSL_CERTIFICATE=file:./.certs/localhost.crt
SSL_CERTIFICATE_PRIVATE_KEY=file:./.certs/localhost.key
FEISHU_MOCK=false
```

生成证书：

```bash
./scripts/generate-local-cert.sh
```

在 macOS“钥匙串访问”中导入 `.certs/local-ca.crt`，设为 SSL 信任，然后启动：

```bash
./run-local.sh
```

`run-local.sh` 自动选择 Java 17，也可通过 `JAVA_17_HOME` 指定 JDK 17 路径。使用 Java 21 时，先将 `JAVA_HOME` 指向 JDK 21，再在 Bash / Zsh 中执行：

```bash
set -a
source .env.local
set +a
./mvnw spring-boot:run
```

首次运行会通过 Maven Wrapper 下载构建依赖。`.env.local` 和 `.certs/` 已被 Git 忽略，App Secret 仅保存在服务端。

`FEISHU_SCOPES` 以空格分隔；设为 `''` 时，飞书授权请求不携带 `scope` 参数，这不代表一定跳过用户授权确认。

### 3. 后台配置连接器并上传技能

管理员在豆包工作管理后台配置 MCP 连接器：

| 配置项 | 本地 Demo 配置 |
| --- | --- |
| 唯一标识 | `mcp21-java-demo` |
| 连接器类型 | MCP 连接器 |
| 认证类型 | MCP OAuth 2.1 |
| 网络要求 | 仅内网可用 |
| 传输类型 | `streamableHttp` |
| MCP Server URL | `https://localhost:8080/mcp` |
| 请求头 | `Authorization: Bearer ${access_token}` |
| 查询参数 | 留空 |

![豆包工作管理后台的 MCP 连接器配置](docs/images/mcp-connector-config.png)

本地联调需由运行服务的电脑访问 `localhost`，且证书受信任。“仅内网可用”仍需使用 HTTPS；云端执行时应配置云端可达的 HTTPS 地址。更换部署地址时，同步更新 `PUBLIC_BASE_URL`、证书和飞书后台的重定向 URL。

**管理员在豆包工作管理后台上传技能，并在插件中关联该技能。** 技能源码位于 [`skills/feishu-whoami-mcp/`](skills/feishu-whoami-mcp/)，可在项目根目录打包：

```bash
mkdir -p dist
(cd skills && zip -r ../dist/feishu-whoami-mcp.zip feishu-whoami-mcp -x '*/.DS_Store')
```

`dist/` 为本地打包产物，不提交到仓库。

### 4. 完成授权并验证

1. 在豆包工作安装管理员配置的插件，点击“连接”，确认授权并登录飞书。
2. 在对话中询问“我是谁”，确认返回当前飞书账号的 `name` 和 `open_id`。

直接在浏览器打开 `/mcp` 返回 `401` 属于正常鉴权行为。

## 本地模拟与测试

不接入真实飞书时，在未加载 `.env.local` 的新终端执行：

```bash
./mvnw spring-boot:run
```

默认启用飞书模拟器，服务地址为 `http://localhost:8080`，无需 App ID 或 App Secret。此模式用于本地开发；接入豆包工作按上文配置 HTTPS。

运行测试：

```bash
./mvnw test
```

测试覆盖动态注册、模拟飞书回调、PKCE、授权码防重放、MCP 令牌刷新及飞书错误信息脱敏。

## 授权流程与接口

豆包动态注册获取 MCP `client_id` → 用户经飞书授权 → 服务端保存飞书凭证并返回 MCP 授权码 → 豆包携带 PKCE 兑换 MCP token → 调用 `whoami`。

**MCP token → grant_id → 飞书凭证。** MCP `client_id` / `client_secret` 与飞书 App ID / App Secret 是两套独立凭证，飞书 token 始终保留在服务端。

| 接口 | 用途 |
| --- | --- |
| `GET /.well-known/oauth-protected-resource/mcp` | MCP 资源与授权服务器发现 |
| `GET /.well-known/oauth-authorization-server` | OAuth 端点和支持能力 |
| `POST /register` | 动态客户端注册 |
| `GET /authorize`、`POST /authorize` | 校验授权请求、展示确认页并跳转飞书 |
| `GET /callback/feishu` | 兑换飞书凭证，返回 MCP 授权码 |
| `POST /token` | 兑换 MCP token 或轮换 MCP refresh token |
| `/mcp` | 受保护的 Streamable HTTP 服务 |

手动发送 MCP POST 请求时，携带以下请求头：

```http
Authorization: Bearer <MCP access token>
Accept: application/json, text/event-stream
Content-Type: application/json
```

未授权响应通过 `WWW-Authenticate` 中的 `resource_metadata` 指向资源发现接口。可检查 HTTPS 发现端点：

```bash
curl --cacert .certs/local-ca.crt https://localhost:8080/.well-known/oauth-protected-resource/mcp
curl --cacert .certs/local-ca.crt https://localhost:8080/.well-known/oauth-authorization-server
```

## 常见问题与 Demo 边界

| 现象 | 检查项 |
| --- | --- |
| 安装失败，服务没有请求日志 | 服务是否启动、地址是否可达、证书是否受信任，以及 `localhost` 是否指向服务所在电脑 |
| `INVALID_AUTHORIZATION_URL` | 授权端点是否使用 HTTPS |
| 飞书回调地址错误 | 登记的 URL 与实际 `redirect_uri` 的协议、主机、端口、路径是否完全一致 |
| `Missing state or browser cookie` | 从豆包重新发起授权，保持同一浏览器及主机名，不要直接打开回调地址 |
| 重启后出现 `invalid_client` 或 `/mcp` 返回 `401` | 内存中的注册和令牌已清空，需重新注册并授权；客户端沿用旧注册时，卸载后重新安装连接器 |
| `/token` 返回 `invalid_grant` | 授权码是否过期或重复使用，PKCE、客户端、resource、回调地址是否匹配；刷新时是否使用最新令牌 |

Demo 采用单进程内存存储，重启会清空客户端注册、授权流程和令牌。已实现 **MCP refresh token 轮换**，尚未实现**飞书 token 刷新**；MCP access token 和 refresh token 的有效期均不超过飞书 access token，到期后需重新授权。

生产部署需补充持久化、飞书凭证刷新、统一密钥管理和多实例并发控制，并使用受信任的 HTTPS 证书。
