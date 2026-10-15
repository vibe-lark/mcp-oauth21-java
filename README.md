# Java MCP OAuth 2.1 demo（无数据库）

这个项目实现了动态客户端注册、OAuth 授权码流程、PKCE、飞书登录、MCP token 签发和刷新，以及受保护的 Streamable HTTP `/mcp` 入口。授权状态保存在 JVM 内存中，无需数据库。

服务重启后，客户端注册、授权流程和令牌都会清空，需要客户端重新注册并授权。

## 运行

Java 17 和 21 均已验证。首次运行会通过 Maven Wrapper 下载构建依赖：

```bash
./mvnw spring-boot:run
```

上述命令默认开启本地飞书模拟器，不需要 App ID 或 App Secret，服务地址为 `http://localhost:8080`。

接入豆包及真实飞书时使用 HTTPS。先复制配置模板，填写自己的飞书应用凭证：

```bash
cp .env.local.example .env.local
chmod 600 .env.local
```

编辑 `.env.local` 中的 `FEISHU_APP_ID`、`FEISHU_APP_SECRET` 和 `FEISHU_SCOPES`，然后生成证书：

```bash
./scripts/generate-local-cert.sh
```

在 macOS 中，将 `.certs/local-ca.crt` 加入登录钥匙串并设为 SSL 信任，然后启动服务：

```bash
./run-local.sh
```

`run-local.sh` 会加载 `.env.local` 并自动选择 Java 17。可以通过 `JAVA_17_HOME` 指定 JDK 17 路径。使用 Java 21 时，先将 `JAVA_HOME` 指向 JDK 21，再在 Bash / Zsh 中执行：

```bash
set -a
source .env.local
set +a
./mvnw spring-boot:run
```

本地回调地址固定为：

```text
https://localhost:8080/callback/feishu
```

真实凭证和生成的证书不会上传到仓库；配置模板见 `.env.local.example`。

两个发现接口：

```bash
curl --cacert .certs/local-ca.crt https://localhost:8080/.well-known/oauth-protected-resource/mcp
curl --cacert .certs/local-ca.crt https://localhost:8080/.well-known/oauth-authorization-server
```

运行测试：

```bash
./mvnw test
```

测试覆盖动态注册、模拟飞书回调、PKCE、授权码防重放、MCP 令牌刷新及飞书错误信息脱敏。

## 接入真实飞书

先在飞书开放平台配置回调地址。本地联调可以使用：

```text
https://localhost:8080/callback/feishu
```

按上文配置 `.env.local` 并启动服务。部署到其他地址时，修改 `PUBLIC_BASE_URL`，配置 HTTPS，并同步更新飞书后台的回调地址。

`FEISHU_SCOPES` 使用以空格分隔的 scope；设为 `''` 时，飞书授权请求不携带 `scope` 参数。Demo 已实现 MCP 令牌刷新，但尚未实现飞书令牌刷新和持久化；飞书 user access token 到期后需要重新授权。

## 主要代码

- `OAuthController`：`/register`、`/authorize`、`/callback/feishu` 和 `/token`
- `InMemoryOAuthStore`：保存授权状态，原子消费 MCP code 并轮换 MCP refresh token
- `DefaultFeishuClient`：真实飞书 OAuth 和用户信息请求
- `McpConfiguration`：官方 MCP Java SDK 的无会话 Streamable HTTP 服务和 `whoami` 工具
- `McpAuthenticationFilter`：校验 MCP Bearer token，并把对应 grant 传给 MCP 工具

`/mcp` 请求必须同时带上：

```text
Authorization: Bearer <MCP access token>
Accept: application/json, text/event-stream
Content-Type: application/json
```

未登录时返回 `401`，并通过 `WWW-Authenticate` 的 `resource_metadata` 指向受保护资源发现接口。

## 豆包技能

项目附带一个用于调用 `whoami` 的 Agent Skill：

```text
skills/feishu-whoami-mcp/
```

管理员在豆包工作管理后台配置 MCP 连接器：标识填写 `mcp21-java-demo`，认证类型选择 MCP OAuth 2.1，传输类型选择 `streamableHttp`，本地联调地址填写 `https://localhost:8080/mcp`，请求头配置 `Authorization: Bearer ${access_token}`。

管理员在后台上传技能，并在插件中关联该技能。用户安装插件并完成授权后，询问“我是谁”，技能会调用 `whoami` 并返回当前授权用户的 `name` 和 `open_id`。

如需生成技能上传包，在项目根目录执行：

```bash
mkdir -p dist
(cd skills && zip -r ../dist/feishu-whoami-mcp.zip feishu-whoami-mcp -x '*/.DS_Store')
```

`dist/` 为本地打包产物，不提交到仓库。

技能不会保存 App ID、App Secret 或 token；真实凭证仍只保存在本机被 Git 忽略的 `.env.local` 中。
