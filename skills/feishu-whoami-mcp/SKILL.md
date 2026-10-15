---
name: feishu-whoami-mcp
description: 通过已连接的 mcp21-java-demo MCP 查询当前授权的飞书用户。当用户询问“我是谁”、当前飞书账号、open_id，或要求验证飞书 OAuth/MCP 授权是否成功时使用；不用于搜索通讯录或查询其他用户。
---

# 飞书当前用户

使用已连接的 `mcp21-java-demo` MCP 服务中原始名称为 `whoami` 的工具。不同客户端可能给工具名增加服务前缀，应按工具描述识别，不要依赖固定前缀。

## 执行

1. 每次都以空对象 `{}` 调用一次 `whoami`，不要根据对话内容猜测身份，也不要复用以前的结果。
2. 从工具返回的 JSON 中读取 `name` 和 `open_id`。
3. 默认回答：`当前授权的飞书用户是 {name}（open_id: {open_id}）。`
4. 若客户端提示需要授权，引导用户在弹出的飞书页面完成 OAuth；授权完成后重试一次。
5. 若 token 失效或授权被撤销，引导用户重新连接该 MCP；重新授权后重试一次。

## 边界

- 不向用户索取或输出飞书 App Secret、user access token、refresh token、MCP access token、授权码或 PKCE verifier。
- `open_id` 不是 `employee_id`，不要混用。
- 该 MCP 只提供当前授权用户的姓名和 `open_id`。不要声称它能搜索通讯录、查询其他成员或修改飞书数据。
- 工具调用失败时，简要说明可操作的错误原因；不要原样输出可能包含凭证的请求头、回调 URL 查询参数或服务日志。
