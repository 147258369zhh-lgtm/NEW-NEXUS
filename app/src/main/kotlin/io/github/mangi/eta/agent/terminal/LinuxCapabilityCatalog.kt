package io.github.mangi.eta.agent.terminal

/** Package names are checked against official Alpine 3.24 / Debian trixie arm64 indices. */
internal object LinuxCapabilityCatalog {
    private fun check(command: String, version: String = "$command --version") = LinuxPackageCheck(command, version)
    private fun spec(packages: String, vararg checks: LinuxPackageCheck) = LinuxPackageSpec(packages.split(' ').filter(String::isNotBlank), checks = checks.toList())
    private fun profile(id: String, name: String, description: String, alpine: LinuxPackageSpec?, debian: LinuxPackageSpec?, dependency: LinuxPackageProfile? = null, large: Boolean = false, optional: Boolean = false, revision: Int = 1) = LinuxPackageProfile(
        id, ".nexa-profile-$id-ready", revision, buildMap { alpine?.let { put(LinuxDistribution.ALPINE, it) }; debian?.let { put(LinuxDistribution.DEBIAN, it) } }, dependency,
        displayName = name, description = description, isLargeDownload = large, isOptional = optional,
    )
    val C_CPP = profile("c_cpp", "C / C++ 编译", "GCC、CMake、Ninja 构建工具", spec("build-base cmake samurai pkgconf", check("gcc"), check("g++"), check("cmake"), check("ninja")), spec("gcc g++ make cmake ninja-build pkg-config", check("gcc"), check("g++"), check("cmake"), check("ninja")))
    val GO = profile("go", "Go", "官方正式版 Go 工具链", LinuxPackageSpec(managedTool = ManagedLinuxTool.GO, checks = listOf(check("go", "go version"))), LinuxPackageSpec(managedTool = ManagedLinuxTool.GO, checks = listOf(check("go", "go version"))))
    val RUST = profile("rust", "Rust", "官方 stable Rust / Cargo 工具链", LinuxPackageSpec(packages = listOf("build-base"), managedTool = ManagedLinuxTool.RUST, checks = listOf(check("rustc"), check("cargo"))), LinuxPackageSpec(packages = listOf("gcc", "libc6-dev"), managedTool = ManagedLinuxTool.RUST, checks = listOf(check("rustc"), check("cargo"))), large = true)
    val JAVA = profile("java", "Java 开发", "包含 javac；Alpine 使用仓库兼容 JDK，Debian 官方正式 JDK", spec("openjdk25-jdk", check("java", "java -version"), check("javac", "javac -version")), LinuxPackageSpec(managedTool = ManagedLinuxTool.JDK, checks = listOf(check("java", "java -version"), check("javac", "javac -version"))), large = true)
    val JAVA_BUILD = profile("java_build", "Maven / Gradle", "官方最新正式 Maven / Gradle 构建工具", LinuxPackageSpec(managedTools = listOf(ManagedLinuxTool.MAVEN, ManagedLinuxTool.GRADLE), checks = listOf(check("mvn", "mvn -version"), check("gradle"))), LinuxPackageSpec(managedTools = listOf(ManagedLinuxTool.MAVEN, ManagedLinuxTool.GRADLE), checks = listOf(check("mvn", "mvn -version"), check("gradle"))), JAVA, large = true)
    val NODE_DEV = profile("node_dev", "Node 开发", "最新正式 pnpm / TypeScript", LinuxPackageSpec(setupScript = "npm install -g --prefix /usr/local pnpm@latest typescript@latest", checks = listOf(check("pnpm"), check("tsc"))), LinuxPackageSpec(setupScript = "npm install -g --prefix /usr/local pnpm@latest typescript@latest", checks = listOf(check("pnpm"), check("tsc"))), LinuxPackageProfiles.NODE)
    val PYTHON_DEV = profile("python_dev", "Python 开发", "隔离安装最新 pytest / Ruff", LinuxPackageSpec(setupScript = "uv tool install --upgrade pytest\nuv tool install --upgrade ruff", checks = listOf(check("pytest"), check("ruff"))), LinuxPackageSpec(setupScript = "uv tool install --upgrade pytest\nuv tool install --upgrade ruff", checks = listOf(check("pytest"), check("ruff"))), LinuxPackageProfiles.PYTHON)
    val MEDIA = profile("media", "媒体处理", "FFmpeg、ImageMagick、ExifTool", spec("ffmpeg imagemagick exiftool", check("ffmpeg", "ffmpeg -version"), check("magick", "magick -version"), check("exiftool", "exiftool -ver")), spec("ffmpeg imagemagick libimage-exiftool-perl", check("ffmpeg", "ffmpeg -version"), check("magick", "magick -version"), check("exiftool", "exiftool -ver")))
    val PDF = profile("pdf", "PDF / 文档", "Pandoc、Poppler、qpdf；TeX 和字体单独选择", spec("pandoc-cli poppler-utils qpdf", check("pandoc"), check("pdftotext", "pdftotext -v"), check("qpdf")), spec("pandoc poppler-utils qpdf", check("pandoc"), check("pdftotext", "pdftotext -v"), check("qpdf")))
    private val fontChecks = arrayOf(
        check("fc-list", "fc-list --version"),
        check("fc-match", "fc-match -f '%{family}\\n' 'sans:lang=zh-cn' | grep -E 'Noto.*(CJK|SC)'"),
    )
    val PDF_FONTS = profile("pdf_fonts", "文档字体", "Noto 中文及通用字体，独立大包", spec("font-noto font-noto-cjk fontconfig", *fontChecks), spec("fonts-noto-core fonts-noto-cjk fontconfig", *fontChecks), large = true, optional = true, revision = 2)
    private val dataCheck = check("/usr/bin/python3", "/usr/bin/python3 -c 'import numpy,pandas; print(\"numpy=\"+numpy.__version__+\" pandas=\"+pandas.__version__)'")
    val DATA = profile("data", "数据处理", "发行版 NumPy / pandas，使用 /usr/bin/python3", spec("python3 py3-numpy py3-pandas", dataCheck), spec("python3 python3-numpy python3-pandas", dataCheck))
    val DUCKDB = profile("duckdb", "DuckDB", "独立 Python 数据库环境（Debian 二进制 wheel）", null, LinuxPackageSpec(setupScript = "uv venv --allow-existing /opt/nexa/duckdb\nuv pip install --upgrade --only-binary :all: --python /opt/nexa/duckdb/bin/python duckdb", checks = listOf(check("/opt/nexa/duckdb/bin/python", "/opt/nexa/duckdb/bin/python -c 'import duckdb; print(duckdb.__version__)'"))), LinuxPackageProfiles.PYTHON, optional = true)
    val NGINX = profile("nginx", "Nginx", "网页服务工具；安装后不会自动启动服务", spec("nginx", check("nginx", "nginx -v")), spec("nginx", check("nginx", "nginx -v")))
    val CADDY = profile("caddy", "Caddy", "网页服务工具；安装后不会自动启动服务", spec("caddy", check("caddy", "caddy version")), spec("caddy", check("caddy", "caddy version")))
    val SCIENTIFIC = profile("scientific", "科研 / Jupyter", "JupyterLab、Matplotlib（系统 Python），独立大包", spec("python3 py3-matplotlib jupyterlab", check("jupyter-lab"), check("/usr/bin/python3", "/usr/bin/python3 -c 'import matplotlib; print(matplotlib.__version__)'")), spec("python3 python3-matplotlib jupyterlab", check("jupyter-lab"), check("/usr/bin/python3", "/usr/bin/python3 -c 'import matplotlib; print(matplotlib.__version__)'")), DATA, large = true, optional = true)
    val OCR = profile("ocr", "OCR 文字识别", "Tesseract 英文及简体中文模型，按需下载", spec("tesseract-ocr tesseract-ocr-data-eng tesseract-ocr-data-chi_sim", check("tesseract"), check("tesseract", "tesseract --list-langs | grep -F chi_sim")), spec("tesseract-ocr tesseract-ocr-eng tesseract-ocr-chi-sim", check("tesseract"), check("tesseract", "tesseract --list-langs | grep -F chi_sim")), large = true, optional = true)
    val TEX = profile("tex", "TeX 排版", "XeTeX 和扩展 LaTeX 包，按需大下载", spec("texlive texlive-xetex texmf-dist-latexextra texmf-dist-langchinese", check("xelatex"), check("xelatex", linuxTexSmokeProbe())), spec("texlive-xetex texlive-latex-extra texlive-lang-chinese", check("xelatex"), check("xelatex", linuxTexSmokeProbe())), PDF_FONTS, large = true, optional = true, revision = 2)
    // Probes below only print versions/help; installation never scans hosts or opens capture devices.
    // Root/raw sockets/ptrace remain Android-kernel requirements when the user later runs a tool.
    val NETWORK_DIAGNOSTICS = profile(
        "network_diagnostics", "网络诊断", "DNS、路由与吞吐诊断；部分运行方式需要 Root 或内核支持",
        spec("bind-tools mtr iperf3 traceroute", check("dig", "dig -v"), check("mtr"), check("iperf3"), check("traceroute")),
        spec("bind9-dnsutils mtr-tiny iperf3 traceroute", check("dig", "dig -v"), check("mtr"), check("iperf3"), check("traceroute")),
    )
    val NETWORK_SCAN = profile(
        "network_scan", "网络扫描", "Nmap 授权网络的端口与服务检测；原始套接字扫描需要 Root 或内核支持",
        spec("nmap", check("nmap")), spec("nmap", check("nmap")), optional = true,
    )
    val PACKET_ANALYSIS = profile(
        "packet_analysis", "抓包 / 离线分析", "tcpdump、TShark、capinfos；离线分析可用，实时抓包需要 Root 和网卡权限",
        spec("tcpdump tshark wireshark-common", check("tcpdump"), check("tshark"), check("capinfos")),
        spec("tcpdump tshark wireshark-common", check("tcpdump"), check("tshark"), check("capinfos")), optional = true,
    )
    // Debian's configured main repository supplies SQLMap; Alpine's does not.
    val WEB_SQLMAP = profile(
        "web_sqlmap", "网页检测 · SQLMap", "授权网页的 SQL 注入检测；仅提供安装与版本检查",
        null, spec("sqlmap", check("sqlmap")), optional = true,
    )
    // Nikto is available in Alpine community, but not Debian's configured main repository.
    val WEB_NIKTO = profile(
        "web_nikto", "网页检测 · Nikto", "授权网页服务器的安全检测；仅提供安装与版本检查",
        spec("nikto", check("nikto.pl", "nikto.pl -Version")), null, optional = true,
    )
    val FILE_FORENSICS = profile(
        "file_forensics", "文件取证", "Binwalk 文件结构识别、Foremost 文件恢复；处理已有文件或镜像",
        null, spec("binwalk foremost", check("binwalk", "nexa_help=$(binwalk --help) && printf '%s\\n' \"${'$'}nexa_help\" | sed '/^[[:space:]]*$/d'"), check("foremost", "foremost -V")), optional = true,
    )
    val SYSTEM_PERFORMANCE = profile(
        "system_performance", "系统性能分析", "Hyperfine、sysstat、strace；进程跟踪和统计受 Android 内核及权限限制",
        spec("hyperfine sysstat strace", check("hyperfine"), check("sar", "sar -V"), check("iostat", "iostat -V"), check("strace", "strace -V")),
        spec("hyperfine sysstat strace", check("hyperfine"), check("sar", "sar -V"), check("iostat", "iostat -V"), check("strace", "strace -V")),
    )
    val DATABASE_CLIENTS = profile(
        "database_clients", "数据库客户端", "PostgreSQL、MariaDB；Debian 另含 Redis 客户端，不安装数据库服务",
        // Alpine redis includes the server; omit it instead of pretending it is a client-only package.
        spec("postgresql18-client mariadb-client", check("psql"), check("mariadb")),
        spec("postgresql-client mariadb-client redis-tools", check("psql"), check("mariadb"), check("redis-cli")),
    )
    val FILE_SYNC = profile(
        "file_sync", "文件同步", "rclone、rsync 的本地与远程文件同步工具",
        spec("rclone rsync", check("rclone", "rclone version"), check("rsync")),
        spec("rclone rsync", check("rclone", "rclone version"), check("rsync")),
    )
    val VECTOR_GRAPHICS = profile(
        "vector_graphics", "SVG 矢量转换", "rsvg-convert 命令行 SVG 渲染与格式转换，无需图形桌面",
        spec("rsvg-convert", check("rsvg-convert")), spec("librsvg2-bin", check("rsvg-convert")), optional = true,
    )
    val ALL = listOf(C_CPP, GO, RUST, JAVA, JAVA_BUILD, NODE_DEV, PYTHON_DEV, MEDIA, PDF, PDF_FONTS, DATA, DUCKDB, NGINX, CADDY, SCIENTIFIC, OCR, TEX,
        NETWORK_DIAGNOSTICS, NETWORK_SCAN, PACKET_ANALYSIS, WEB_SQLMAP, WEB_NIKTO, FILE_FORENSICS, SYSTEM_PERFORMANCE, DATABASE_CLIENTS, FILE_SYNC, VECTOR_GRAPHICS)
}
