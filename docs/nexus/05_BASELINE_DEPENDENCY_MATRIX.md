# Nexus 阶段 0 依赖矩阵

本文件记录基线 `8f005e3` 上按源码 import 统计得到的模块依赖。统计只用于发现边界问题，不能替代逐文件调用审计。

## 1. 顶层包导入计数

统计范围：`app/src/main/kotlin/io/github/mangi/eta` 顶层包之间的 import 次数，包含同包内部 import。

| From | To | Import 次数 |
|---|---|---:|
| agent | agent | 463 |
| agent | data | 220 |
| agent | fork | 34 |
| agent | ui | 24 |
| data | agent | 55 |
| data | data | 207 |
| data | fork | 14 |
| data | ui | 2 |
| fork | agent | 54 |
| fork | data | 14 |
| fork | fork | 24 |
| fork | ui | 2 |
| hook | agent | 21 |
| hook | data | 1 |
| hook | hook | 6 |
| ui | agent | 452 |
| ui | data | 261 |
| ui | fork | 122 |
| ui | systemizer | 3 |
| ui | ui | 727 |

## 2. 实测循环

当前至少存在以下逻辑循环：

```text
agent ↔ data
agent ↔ fork
data  ↔ fork
agent ↔ ui
data  ↔ ui
fork  ↔ ui
```

其中：

- Agent Runtime 直接引用 `MainActivity` 和 UI model。
- Data Repository 引用 UI model。
- Fork 功能引用 Agent Runtime 和 Data，UI 又同时依赖 Agent、Data、Fork。
- Hook 层引用 Agent 和 Data，因此 Hook 也不是纯外围模块。

## 3. 不能直接按目录拆模块

如果现在只把 `agent`、`data`、`fork`、`ui` 四个目录直接变成四个 Gradle 模块，编译会立即因为循环依赖失败。

必须先完成以下依赖反转：

1. Runtime 不依赖 Activity 和 UI model。
2. Data 不使用 UI 的 Pending UI 类型。
3. Feature 不直接导入其他 Feature。
4. Host Adapter 通过 `HostAdapter`/`ToolDescriptor` 与 Kernel 对接。
5. UI 通过 Application API 和 Presentation DTO 读取状态。

## 4. 安全拆分顺序

### 第一批：新增无依赖契约

- `kernel-api`。
- `capability-api`。
- `provider-api`。
- `presentation-api`。

这些模块先不移动现有业务代码，只新增纯 Kotlin 类型和接口。

### 第二批：抽离只读与稳定组件

- AgentEvent 的序列化契约。
- Session/Run/Item DTO。
- ToolDescriptor 与 permission model。
- UI foundation 的纯设计 token 和通用组件。

### 第三批：反转 UI 依赖

- `MainActivity` 引用改为 NavigationIntent/Application API。
- Overlay/Notification 使用的 UI model 移入 Presentation 层。
- Data 层的 `PendingFileReferenceUi` 等类型改为 domain/application model。

### 第四批：抽离 Runtime 与 Store

- Runtime executable engine。
- Durable event store。
- Metrics/checkpoint projections。
- API facade。

### 第五批：抽离 Host

- Native Android Host。
- Linux Host。
- MCP Host。
- A2A Host。
- DSH Host。
- Skills Host。

### 第六批：抽离 Feature

- Chat。
- Meeting。
- Tasks/Goal。
- Settings。
- Appearance。

## 5. 依赖门禁

后续每次提交新增模块时，必须检查：

- 低层模块没有导入高层模块。
- 不得通过同一包名规避模块边界。
- 不允许 Feature 之间直接 import。
- 不允许 DAO/Entity 穿过 store facade。
- 不允许第三方协议类型出现在 kernel-api。
- 不允许 Prompt 或模型决定权限，只能使用 CapabilitySnapshot 与 ToolPolicy。
- 新增循环依赖必须先登记 ADR 并获得替代计划。

## 6. 首个可执行审计任务

在开始拆模块前，生成逐文件依赖清单：

```text
sourceFile
-> importedProjectPackage
-> intendedLayer
-> violation
-> replacementContract
```

第一阶段只处理阻塞 Runtime/Data/UI 分离的高风险 import，不进行全局 package 改名。
