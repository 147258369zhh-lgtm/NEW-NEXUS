# NEXA Linux 工具箱完整报告

核验日期：2026-11-15。范围：读取现有源码及官方在线发布资料，形成方案；未修改程序或界面，未安装/升级手机上的Linux环境，未运行构建或测试。下列“官方最新”是当日资料，不代表手机已经安装该版本。

## 一、结论

Linux工具可以明显增加，重点应是把环境变成具备开发、媒体、文档、数据与服务能力的完整执行平台，而不是增加一长列安装按钮。

现有底座无需重做：已有Debian/Alpine、Root chroot/免Root PRoot、基础命令、Python、Node、SSH、Kimi Code、APK分析、终端和后台任务。要补的是工具目录、依赖管理、实例绑定、实际版本检测、升级修复与项目/MCP联动。

用户“所有东西都用最新”建议落实为默认最新正式版本。最新稳定版、发行版仓库版、长期支持版和开发测试版需要明确区分，不能用一个latest标签混在一起。允许同一工具保留项目需要的旧版本，正在执行的任务固定原版本，新任务再使用已升级版本。

## 二、现有能力盘点

| 类型 | 当前实际能力 | 仍需补的内容 |
| --- | --- | --- |
| Linux环境 | Debian、Alpine；Root/免Root独立目录 | 明确实例ID、名称、版本、工作区与终端绑定 |
| 通用命令 | bash、git、curl、wget、rg、fd、jq、rsync、SQLite、OpenSSL、压缩工具等 | 整合展示、版本检测、升级/修复，不能重复宣称为新增 |
| Python | uv安装和管理Python | 更新uv、显式选择最新可用Python、项目虚拟环境及能力包 |
| Node | Node/npm；Alpine仓库安装，Debian官方归档 | 最新版本解析、升级、项目版本锁定与依赖检测 |
| SSH | 客户端/服务器工具与host key准备 | 统一服务启动/停止、日志与远程目标管理；安装不等于服务正在运行 |
| Kimi Code | 安装、验证CLI、后台启动网页和停止 | 登录状态、版本更新、是否选择它执行任务的明确入口 |
| APK分析 | Java、apktool、jadx、smali/baksmali | 保持工具最新及检查；当前不含完整ARM64 AAPT2回编译能力 |
| 执行 | 多终端、后台进程、日志和资源限制 | 按实例汇总、服务恢复、避免同发行版不同后端复用错终端 |
| MCP | HTTP/stdio已有客户端 | 本地启动目前走Android Shell，需明确绑定Linux实例及运行依赖 |

## 三、版本是否最新

| 项目 | 当前源码策略/版本 | 官方当日最新 | 判断与动作 |
| --- | --- | --- | --- |
| Debian | 13/trixie；基础归档来自proot-distro v4.29.0，2025-09-27发布 | 13.7，2026-09-12 | 主系列最新，基础归档较旧；通过trixie更新源更新环境中的包，不能把标签13等同于已更新到13.7 |
| Alpine | minirootfs 3.24.1，使用v3.24仓库 | 3.24.4，2026-11-05 | 更新新安装归档；现有环境另做apk升级 |
| Node / Debian | 固定26.8.1 | 26.13.1，2026-11-10，当前也是Lithium LTS | 更新受管理归档、校验值和版本链接 |
| Node / Alpine | nodejs-current/npm仓库包 | 26.13.1-r0 | 仓库可取得，但旧安装不会因打开页面自动升级 |
| uv | 固定0.12.7 | 0.13.8，2026-11-12 | 更新受管理安装器及校验信息 |
| Python | 旧uv执行无明确版本参数的uv python install | 3.15.2，2026-10-25 | 不能保证最新；旧uv内置ARM64下载目录最新稳定为3.14.7。更新uv后核验目标架构/libc能下载3.15.2，再安装 |
| Kimi Code | npm @moonshot-ai/kimi-code@latest | 2.6.2，2026-11-13 | 新安装解析latest；已有完成标记会跳过，需独立升级机制 |
| apktool | 3.0.3 | 3.0.3 | 当日已是所用官方项目最新发布 |
| jadx | 1.5.6 | 1.5.6 | 当日已是最新发布 |
| smali/baksmali | baksmali/smali 3.0.10 | 3.0.10 | 当日已是所用项目最新发布，不能拿google/smali另一仓库的版本直接比较 |

建议新增运行时的当日官方版本：Go 1.27.4、Rust 1.100.0、JDK 27正式版。这里只是候选目标，不表示已经提供手机安装器或实机验证。Java工具可并存25 LTS满足旧项目；Linux JDK选择不应自动改变NEXA应用自身的构建工具链。

Debian testing=forky、unstable=sid；Alpine edge、Node alpha、Python开发系列不应与“最新正式版”混称。用户若明确选择开发渠道，再为独立环境提供该渠道。

特别注意：proot-distro上游最新v5.10.3没有当前Debian RootFS下载资产，不能只把URL中v4.29.0改成最新tag。当前稳妥做法是保留已校验基础归档并执行发行版包更新，后续再维护新的可验证基础镜像。

## 四、Kimi Code究竟是什么

Kimi Code是运行在Linux里的独立编程Agent，可读取和修改项目文件、运行命令、搜索资料、规划任务，也支持MCP、插件和子Agent。它相当于给工具箱增加另一位可选的编程助手。

它与NEXA自身Agent分开：NEXA使用自己的模型配置、会话、工具与审批；Kimi Code自己维护认证、模型、权限、会话和配置。当前NEXA只是负责安装、启动、停止和打开其网页，没有把两套配置自动同步，也不是把NEXA会话全部交给Kimi执行。

当前源码使用有效的新npm路线，Node要求≥22.19.0。安装后首次仍需/login，通过Kimi Code设备码OAuth或Moonshot API Key登录；可配置其他提供商，但不能保证每个账号都有相同模型权限。

kimi web --no-open在工作区启动，地址默认为本机127.0.0.1，并带网页认证token；这个token不是模型API Key。旧Python kimi-cli仓库的1.52.0已经是迁移入口，不能拿它当新版Kimi Code版本。

官方现在还提供2.6.2 Linux ARM64独立二进制和SHA256，可减少对Node的依赖。建议优先核验Debian的独立二进制，Alpine仍需检查musl兼容性，不能因同为ARM64就保证通用；现有npm路线可以保留作兼容路径。Kimi Code不是Linux必装项，应归入“可选Agent”。

## 五、建议扩充的工具库

以下是拟议能力包，尚未新增安装器。默认按需要安装，基础包已有的命令不重复安装或重复宣传。

| 能力包 | 建议工具 | 能做什么 | 建议阶段 |
| --- | --- | --- | --- |
| 基础命令 | bash、coreutils、git、curl、rg、fd、jq、rsync、zip/unzip、xz、zstd | 文件、搜索、下载、压缩、同步 | 先整理现有能力 |
| 原生编译 | GCC/G++或Clang、make、CMake、Ninja、pkg-config、构建头文件 | 编译程序、Python/Node原生扩展 | 第一批 |
| Python开发 | Python、uv、venv、pip/pipx、pytest、ruff | 脚本、自动化、测试、项目依赖 | 第一批 |
| Node开发 | Node/npm、pnpm、TypeScript | JS/TS项目、网页和MCP运行 | 第一批 |
| Go开发 | Go工具链 | 网络服务、CLI、并发任务 | 第一批 |
| Rust开发 | Rust、Cargo、rustfmt、Clippy | 高性能CLI、原生组件 | 第一批 |
| Java开发 | JDK、Maven、Gradle | Java/Kotlin项目及已有APK分析工具 | 第一批，JDK已有基础 |
| 媒体处理 | FFmpeg、ffprobe、ImageMagick、ExifTool | 转码、抽帧、裁剪、压缩、元信息 | 第一批 |
| PDF与文档 | Pandoc、Poppler、qpdf、必要字体 | 文档转换、PDF提取/拆合、渲染 | 第一批 |
| 数据处理 | SQLite、DuckDB、数据库客户端、按需pandas/NumPy | CSV、JSON、SQL、数据清洗与统计 | 第一批，SQLite已有 |
| 服务与网页 | Nginx/Caddy、Python或Node服务、按需code-server | 预览项目、运行本地API和网页编辑 | 第二批 |
| 网络与远程 | OpenSSH/SFTP、DNS查询、HTTP调试、rsync | 连接电脑服务器、传输与网络诊断 | 第一批整理，远程调度第二批 |
| 图表与科研 | matplotlib、Jupyter及按需科学计算包 | 图表、笔记本和数据分析 | 第二批 |
| 音视频与文档重型包 | OCR/Tesseract、字体扩充、按需TeX | OCR、多语言PDF和复杂排版 | 第二批，较大组件单独选择 |
| Android分析 | 已有apktool/jadx/smali、按需adb客户端 | APK检查、反编译、连接其他设备 | 保留并加强，ARM64构建工具另核验 |
| 可选Agent | Kimi Code及后续经过核验的CLI | 独立编程助手、协作任务 | 独立分类，需各自认证 |

建议第一批以Debian ARM64为主要适配目标；Alpine提供同功能中可安装的包和明确的兼容替代。PRoot与chroot分别记录安装、运行和验证状态，不把一套成功当成两套都成功。

新运行时应有自己的版本目录和项目环境，避免Python、Node、Java升级后破坏其他工具。C/C++、Rust等需要真实编译环境，不能只放一份下载脚本。FFmpeg等首先提供软件处理能力；不承诺Android硬件编码或GPU加速。Linux ARM64的JDK可运行，不代表所有桌面Android SDK构建工具都可运行。完整桌面环境、Docker、systemd和内核模块不作为通用保证。

## 六、把“基础属性更强”真正做出来

1. 每个工具记录实际版本、官方下载渠道、架构/libc、依赖、支持后端、安装状态和验证结果。
2. 选择一个能力包自动计算依赖。安装Kimi、MCP或其他工具前先确认环境和运行时，失败能从正确阶段修复。
3. 终端、Agent、MCP、包管理和后台服务都绑定明确Linux实例，不再只读取全局“当前发行版”。
4. 项目有独立依赖和工作目录；工具生成的文件回到对应会话/会议室项目，不能混入别的项目。
5. 服务统一管理启动、停止、重启、日志、端口和状态；已有后台进程底座渐进升级，不另造一套不相通的任务管理。
6. 让Agent能读取“当前实例有哪些工具、版本是多少、哪些服务可用”，按任务使用现有工具，减少首次调用缺依赖的失败。

## 七、最新版本政策

界面应分别显示“官方最新”“当前环境已安装”“项目正在使用”。安装完成标记只证明当时安装成功，不证明现在仍是最新。

新安装：解析官方最新正式版本 → 确认ARM64及对应glibc/musl资产 → 核验校验值/来源 → 下载与安装 → 最小可运行检查 → 保存实际版本。

已有工具：独立检查升级，完成后再切换版本链接；失败保留原版本。正在运行的任务保持原执行上下文，不在中途替换运行时。具体项目可使用版本锁，其他新任务默认最新。

发行版升级与工具升级分开。Debian稳定仓库里的软件不一定是上游最新，Python/Node/Go/Rust/Java等可使用独立官方工具链满足“最新”；系统库则遵守发行版兼容关系。uv包装器绑定受管理版本目录，不能只运行uv self update后就宣称整个安装器升级完成。

整体实施顺序：统一入口与能力状态 → Linux实例管理/终端绑定 → 最新版本管理和现有环境升级 → 第一批能力包 → MCP/项目/服务贯通 → 第二批重型与远程能力。

## 八、官方核验来源

- Debian：https://www.debian.org/releases/
- Debian基础镜像：https://github.com/termux/proot-distro/releases/tag/v4.29.0
- Alpine：https://alpinelinux.org/releases/
- Alpine ARM64 Node包：https://pkgs.alpinelinux.org/package/v3.24/community/aarch64/nodejs-current
- Node：https://nodejs.org/dist/index.json
- uv：https://github.com/astral-sh/uv/releases/tag/0.13.8
- 旧uv Python目录：https://raw.githubusercontent.com/astral-sh/uv/0.12.7/crates/uv-python/download-metadata.json
- Python：https://www.python.org/downloads/
- Kimi Code：https://github.com/MoonshotAI/kimi-code 、 https://registry.npmjs.org/@moonshot-ai/kimi-code/latest
- Kimi安装/登录：https://moonshotai.github.io/kimi-code/en/guides/getting-started
- Kimi网页/命令：https://moonshotai.github.io/kimi-code/en/reference/kimi-command
- Go：https://go.dev/dl/?mode=json
- Rust：https://static.rust-lang.org/dist/channel-rust-stable.toml
- OpenJDK：https://jdk.java.net/
- APK工具：https://github.com/iBotPeaches/Apktool/releases/latest 、 https://github.com/skylot/jadx/releases/latest 、 https://github.com/baksmali/smali/releases/latest

源码核验所有者：agent/terminal/LinuxPackageProfiles.kt、PinnedLinuxToolInstaller.kt、DebianEnvironmentInstaller.kt、AlpineEnvironmentInstaller.kt、LinuxApkAnalysisInstaller.kt，ui/app/KimiWebLauncher.kt和KimiWebSession.kt。当前工作目录为本次F:/NEXUS交接源码；未回读旧聊天。
