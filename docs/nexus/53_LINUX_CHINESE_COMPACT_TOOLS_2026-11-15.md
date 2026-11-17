# Linux 中文、紧凑布局与扩充工具 · 2026-11-15

## 本批授权

用户要求Linux界面中文、工具卡片紧凑且安装按钮置于末端、减少设置中的解释性小字，继续增加网络/渗透/取证等通用工具。仅实施对应范围，保留已有材质、配色、字体及实际状态。没有刷Root、安装系统框架、执行扫描/攻击/抓包任务或发送模型消息。

## 中文与布局

- 补齐与应用声明en/zh-Hans/zh-Hant一致的values-b+zh+Hans/Hant中文资源：此前新增资源仅置于values-zh-rCN/TW地区目录。简繁脚本资源与地区后备保持同步。Linux标题、分类、说明、操作和状态均有中文；工具专有名仍保留。
- 界面实际版本只显示版本数字，CLI原始证据保留后端。官方版本检查的异常字幕使用本地化失败提示，发行版枚举不再直接露出ALPINE/DEBIAN。
- 能力包及独立APK分析卡使用紧凑专用组件：彩色裸图标，标题/真实版本在左，安装、升级、修复在最右；窄屏右侧按钮换行。原字号、16dp边距、卡片光影与材质保留。
- 去掉能力包静态功能说明和依赖介绍，保留未安装基础系统、进度及失败等真实状态。
- 设置主页/系统与工具删除6条静态路由摘要和1个纯说明卡；外观删除14条重复静态说明，语音删除2条自动行为说明，备份删除3段重复说明。当前配置、版本、连接、权限、错误、密码/校验以及影响费用的说明保留。

## 新增按需能力

旧21组保留，新增10组，总31组。按所选发行版过滤支持项，不表示每个发行版都有31项。

| 能力 | Alpine | Debian |
|---|---|---|
| 网络诊断 | bind-tools / mtr / iperf3 / traceroute | bind9-dnsutils / mtr-tiny / iperf3 / traceroute |
| 网络扫描 | nmap | nmap |
| 抓包与离线分析 | tcpdump / tshark / wireshark-common | 同左 |
| 网页检测 · SQLMap | 当前所查仓库未提供 | sqlmap |
| 网页检测 · Nikto | nikto，命令nikto.pl | 当前main未提供 |
| 文件取证 | 完整组合未提供 | binwalk / foremost |
| 系统性能分析 | hyperfine / sysstat / strace | 同左 |
| 数据库客户端 | postgresql18-client / mariadb-client | postgresql-client / mariadb-client / redis-tools |
| 文件同步 | rclone / rsync | 同左 |
| SVG矢量转换 | rsvg-convert | librsvg2-bin |

新增“网络与安全”“实用工具”两类，旧四类顺序不变。沿用依赖安装、真实版本检测、缓存、升级修复及国内/海外/自定义源。数据库仅安装客户端，不自动启动数据库服务。

官方包索引核验：
- https://dl-cdn.alpinelinux.org/alpine/v3.24/main/aarch64/APKINDEX.tar.gz
- https://dl-cdn.alpinelinux.org/alpine/v3.24/community/aarch64/APKINDEX.tar.gz
- https://deb.debian.org/debian/dists/trixie/main/binary-arm64/Packages.gz

安装/检查仅使用版本或帮助命令，不运行扫描、攻击或抓包。版本提取改为优先取含点分版本的首行，避免TShark的Root警告占据版本字段；无版本时保留首个非空证据，完整退出码和就绪标记门槛不变。

## 实际设备边界

离线抓包/已有文件分析可免Root；实时抓包、原始套接字探测及部分MTR功能要求真实Root或内核能力。PRoot模拟root不能授予Android权限。strace可能与PRoot的ptrace冲突；sysstat受/proc可见范围限制；rclone同步可用，mount另外依赖FUSE。这些是运行能力边界，不能由安装标记证明全部可用。

## LSPosed开启说明

NEXA APK已经内置模块，不需要另找NEXA插件。源码module.prop要求libxposed API102、staticScope=true；声明作用域含system、SystemUI、小布/ColorOS搜索、Google及相应小米组件。

1. 普通默认助理、免Root Linux、普通无障碍与悬浮窗不要求LSPosed。系统接管/搜索Hook则需要运行中的兼容框架。
2. 确认手机真实Root已工作，并使用支持libxposed API102的LSPosed；仅安装KernelSU管理器不等于Root已工作。LSPatch不能代替系统进程注入。KernelSU路线的框架安装通常还需要兼容的Zygisk实现，须按所选框架的安装说明配置。
3. 进入LSPosed管理器（可能为寄生管理器），开启NEXA，保留模块预设作用域，不扩展到声明范围之外。
4. 重启手机，再到NEXA“设置→系统与工具→数字助理/搜索手势”开启所需功能；默认助理可在手机默认应用设置中选择NEXA。
5. “Root与系统增强”可查看框架连接状态。连接仅证明通信，仍要确保模块启用、目标进程实际加载。

手机只读确认有KernelSU管理器与LSPatch，尚未确认真实Root/系统级LSPosed已运行；没有因为普通管理器包缺失就断定框架不存在。

官方staticScope定义与现代API说明：
https://github.com/LSPosed/LSPosed/wiki/Develop-Xposed-Modules-Using-Modern-Xposed-API

## 验证与交付

新两类最小检查7项一次通过，0失败/跳过；没有重复上一批已通过测试。中文初稿先完成构建2m4s，期间用户又授权紧凑布局、说明精简及新工具，所以最终再构建合并包2m1s，没有安装中间包。最终0.7.7-smooth / 2026111515，64313361字节，SHA256 CBF7422BF1B7A52B72A6AB82DC76B01BBDA45A8F740448801BDEABED007199A2。

原证书、签名、非debuggable、ARM64及ZIP16KiB对齐通过；OPPO PHY110（1fe75f23）install -r成功，系统确认1515，更新时间2026-11-15 19:40:50，保留数据。没有手机页面或新Linux工具下载安装验收。

桌面交付C:/Users/29136/Desktop/NEXA-System-Linux-20261115，保留1514，新增1515 APK/SHA/安装JSON/本记录。不上传GitHub。
