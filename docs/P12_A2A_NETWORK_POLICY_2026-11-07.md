# P12 A2A 与跨网策略

A2A 延续手动 Host allowlist、HTTPS、DNS 重绑定防护和远端产物隔离；本地 HTTP 只允许用户明确填写的私有网地址。新增回归测试覆盖局域网端点和未允许公网主机，保持 Android 16/17 网络安全边界。

## 4.8.2 补强：安全配对与连接控制

- 服务端可生成 `eta-a2a://pair?payload=...` 连接信息，包含 Agent Card 地址和当前 Bearer Token。连接信息只在用户主动点击复制时离开应用，并标记为敏感剪贴板内容。
- 添加 Agent 时支持从剪贴板导入配对信息，自动填入名称、Agent Card 和 Token，但仍要求用户核对后执行联网发现，不会因粘贴而自动授权。
- 配对载荷带版本、长度、控制字符、URL scheme、嵌入账号密码和片段校验；未知版本、损坏 Base64、foreign scheme 和超长字段直接拒绝。
- 已添加 Agent 可直接启用或停用；停用后所有主动调用在 Repository 层拒绝。删除 Agent 同时删除其 Keystore Token 别名；服务端轮换 Token 会令旧配对连接失效。
- 当前入站 Token 仍是设备级共享 Bearer Token，不是逐设备 ACL。逐端 Token、过期时间和独立撤销记录尚未实现。
- 配对链接不能穿透公网 NAT，也不包含 tsnet/Headscale。跨公网仍需用户提供可达 HTTPS 入口；当前只用明确填写的局域网地址或外部隧道。

双批安装包：`[本地交付目录]/Eta-P11-P12-v4.7.0-debug.apk`。
