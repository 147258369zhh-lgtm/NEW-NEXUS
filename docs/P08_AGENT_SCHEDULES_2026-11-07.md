# P08 Agent 自建定时任务

新增 `schedule_create/list/pause/delete` 工具，接入已有 `eta_task.db`、租约状态机和 WorkManager 精确唤醒。创建和删除要求 `confirmed=true`，标题、提示词、最小间隔和 IANA 时区均有边界校验；错过执行可选择跳过、补跑一次或进入待处理队列。任务数据库已在完整备份中。

本批继续沿用现有 Miuix 任务中心和通知恢复路径，Agent 自建任务不会绕过用户确认；暂停、删除和进程重启恢复都经过同一租约状态机。设备是否允许精确闹钟、后台启动和厂商省电豁免仍交由 Android 16/17 真机验收。
