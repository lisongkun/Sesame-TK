# PK 阵营好友列表 · 设计文档

日期：2026-08-30
状态：待评审

## 1. 背景与目标

蚂蚁森林能量挑战赛（PK 赛）会把用户和一批**随机匹配的同阵营陌生人**放进同一个排行榜。这些人不是用户的支付宝好友，赛季结束后就失去联系。

用户希望在模块里看到这批人，并最终能一键把他们**加为真实的支付宝好友**。

本期目标：把这批人**抓取、落盘、展示**出来，并标记谁已经是好友、谁还是陌生人。

## 2. 范围

**本期实现**

- 支付宝进程侧：调用两个已有 RPC 抓取 PK 榜成员，合并、与真实好友表 diff，落盘为按账号隔离的 JSON 快照
- 模块 UI 侧：「账号配置」下新增入口 → 新页面展示该列表，支持刷新、筛选、头像

**明确不在本期**

- 一键添加为支付宝好友。加好友的 RPC 尚未抓包，接口未知。本期的落盘格式已为它预留 `userId`（加好友请求的必要入参），但不实现任何发起逻辑，UI 上也不出现「添加」按钮。

## 3. 关键约束与已验证事实

| # | 事实 | 依据 |
|---|---|---|
| C1 | 两个 RPC 已存在，无需新写 | `AntForestRpcCall.java:64` `queryTopEnergyChallengeRanking()`、`:93` `fillUserRobFlag(userIdList, needFillUserInfo)` |
| C2 | RPC 只能在支付宝进程发。`RequestManager` 依赖 `rpcBridge`，而它只在被 hook 的支付宝进程里初始化 | `ApplicationHook.kt:751-752` |
| C3 | 因此抓取的身份**永远是当前登录的那个支付宝账号**，无法代替其他账号抓取 | C2 推论 |
| C4 | `friend.json` 是**真实好友表**，由 `HookUtil.hookUser()` 直接读支付宝本地通讯录库 `AliAccountDaoOp.getAllFriends()` 得到，带 `friendStatus`，不含 PK 陌生人 | `HookUtil.kt:471-509` |
| C5 | 模块 UI 进程能直接读 `friend.json`（`SettingActivity` 已在做） | `SettingActivity.java:74` |
| C6 | 广播接收器只在 `if (BuildConfig.DEBUG)` 里注册 | `ApplicationHook.kt:719-725` |
| C7 | `CustomTask.entries` 被手动任务页直接遍历，新增枚举值会自动多出一张卡 | `ManualTaskScreen.kt:37` |
| C8 | `ApplicationHook` 的手动任务 `when (task)` 只用于取额外参数，且有 `else` 兜底，所以新增枚举值**不会**破坏编译。但那个 `else` 会打出「❌ 无效的任务指令」，对无参任务是误导性错误日志 —— 这就是 `FOREST_VITALITY_REWARD` 特意写了一个空分支的原因。新任务必须照此加一个带注释的空分支 | `ApplicationHook.kt:463`、`:474-476`、`:489-491` |
| C9 | `ManualTask.isManualEnabled` 全项目从未被赋值，恒为 `true`，不构成阻碍；但 `isManualRunning` 是真的互斥门，运行中的重复触发会被丢弃 | `ManualTask.kt:24`、`:48`、`:60-65` |
| C10 | 项目无图片加载库（只有 OkHttp），需要新增 Coil | `app/build.gradle.kts:143-217` |
| C11 | targetSdk 36，明文 HTTP 默认被禁；manifest 无 `usesCleartextTraffic`、无 network security config。而 `headPortrait` 是 `http://` 开头 | `app/build.gradle.kts:42`、`AndroidManifest.xml` |
| C12 | `Files.getTargetFileofUser()` 会在文件不存在时**创建空文件** | `Files.kt:137-139` |

| C13 | **实测：`RequestManager.requestString()` 返回的是平铺结构**，`success` / `rankMemberStatus` / `myself` / `totalData` / `friendRanking` 直接在顶层，**没有 `resData` 包裹**。本文档早期版本按 capture 日志写成 `resData.xxx` 是错的 —— capture 记录的是整个 RPC 信封（含 `header`、`ariverRpcTraceId`），而 RPC 方法只返回内层载荷。合并器对两种结构都兼容（`root?.get("resData") ?: root`） | 2026-08-30 真机验证，设备 663fb1e8 |

### C3 的产品含义

入口放在「账号配置」区块下，但语义是「**当前登录账号**的 PK 阵营好友」，不是每个账号卡片各自一个入口。数据仍按 `userId` 分文件存放，所以切换账号后各自的快照互不干扰；但页面永远只展示当前登录账号的那一份。想看别的账号，必须先在支付宝里切号。

## 4. 架构与数据流

```
[模块 UI 进程]                          [支付宝进程]

PkCampActivity
  │
  ├─ 进页面：直接读 pkCamp.json 展示快照（不自动抓取）
  │
  └─ 点「刷新」
       │ sendBroadcast(
       │   com.eg.android.AlipayGphone.sesame.manual_task,
       │   task=FOREST_PK_CAMP)
       │                        ──────→  AlipayBroadcastReceiver
       │                                   └─ ManualTask.runSingle(FOREST_PK_CAMP)
       │                                        └─ AntForest.manualFetchPkCampFriends()
       │                                             ① queryTopEnergyChallengeRanking
       │                                             ② 缺失 userId 按 20 分批 fillUserRobFlag
       │                                             ③ 与 UserMap 做 diff → isFriend
       │                                             ④ 写 pkCamp.json
       │                                                   │
       └─ 轮询 pkCamp.json 的 lastModified()  ←────────────┘
            └─ mtime 变化且内容可解析 → 重读 → 更新列表
```

### 为什么用轮询而不是 DataStore watcher

`DataStore` 有跨进程文件 watcher，但它的 `setOnChangeListener` 是**单槽**的，且全项目从未被使用过（`DataStore.kt:43`）。而「发广播 → 轮询文件 → 超时兜底」已经是本项目验证过的模式（`RpcDebugViewModel.kt:206-224`）。复用现成模式，少一个活动部件。

轮询参数：间隔 **500ms**，超时 **30s**。取 30s 是因为 `RpcIntervalLimit` 可能给 RPC 加延迟，两个 RPC 加上分批可能远慢于抓包里看到的百毫秒级。

### 为什么复用 MANUAL_TASK 广播而不是新建 action

复用能白拿三样东西：现成的广播链路、现成的 `CustomTask` 枚举分发、以及手动任务页面自动多出的一张触发卡（C7）。新建 action 要改 `IntentFilter`、加分发分支，收益为零。

## 5. 数据模型与落盘格式

**落盘路径**：`Files.getTargetFileofUser(userId, "pkCamp.json")`，实际落在

```
/sdcard/Android/media/com.eg.android.AlipayGphone/sesame-TK/config/<userId>/pkCamp.json
```

与 `friend.json` 同目录，天然按账号隔离。

**新增** `entity/PkCampSnapshot.kt`：

```kotlin
data class PkCampMember(
    val userId: String,
    val displayName: String,        // 可能为空串，UI 兜底
    val headPortrait: String,       // 可能为空串，UI 用首字占位
    val rank: Int,                  // 来自 totalData，权威
    val energySummation: Long,
    val treeAmount: Int,
    val challengeRankLevelName: String,
    val isFriend: Boolean           // 与 friend.json diff 得出
)

data class PkCampSnapshot(
    val updatedAt: Long,
    val selfUserId: String,
    val rankMemberStatus: String,   // "JOIN" / 其他
    val members: List<PkCampMember>
)
```

**文件样例**：

```json
{
  "updatedAt": 1788054686512,
  "selfUserId": "2088722678450315",
  "rankMemberStatus": "JOIN",
  "members": [
    {
      "userId": "2088922535800312",
      "displayName": "淡泊",
      "headPortrait": "http://tfs.alipayobjects.com/images/partner/TB1KqWFX3yJDuNkUQclXXX5fFXa_160X160",
      "rank": 1,
      "energySummation": 41280,
      "treeAmount": 439,
      "challengeRankLevelName": "青铜",
      "isFriend": false
    }
  ]
}
```

`members` 按 `rank` 升序落盘。序列化用 Jackson（项目已有），字段名与上面完全一致。

`rankMemberStatus` 必须存下来：没参加 PK 赛时页面要能明确说「当前账号未加入 PK 赛」，而不是显示成一个空列表让人以为坏了。

## 6. 抓取逻辑（支付宝进程）

新增 `AntForest.manualFetchPkCampFriends()`，命名跟随 `manualWhackMole` / `manualUseEnergyRain` 的既有习惯（`AntForest.kt:5537`、`:5567`）。

### 拆分：纯函数合并器 + I/O 外壳

第 3-8 步全是对 JSON 的纯数据变换，不碰任何 Android API。把它抽成独立的纯函数，就能在 JVM 单元测试里完整覆盖，不需要设备：

```kotlin
object PkCampMerger {
    fun merge(
        rankingJson: String,              // queryTopEnergyChallengeRanking 的原始返回
        fillJsons: List<String>,          // 各批 fillUserRobFlag 的原始返回
        friendIds: Set<String>,           // 由调用方传入，来自 UserMap.getUserIdSet()
        now: Long                         // 由调用方传入，便于断言 updatedAt
    ): PkCampSnapshot?                    // 拉榜失败返回 null，调用方据此跳过落盘

    fun missingUserIds(rankingJson: String): List<String>   // 供调用方决定要补哪些人
}
```

两条硬约束：

1. **合并器只用 Jackson，绝不用 `org.json`。** `org.json` 在 JVM 单元测试里是 android.jar 的桩，一调就抛 `RuntimeException("Stub!")`。已实测确认 Jackson 在 `:app:testDebugUnitTest` 下正常工作。
2. **`friendIds` 和 `now` 由调用方注入，合并器内部不碰 `UserMap` / `System.currentTimeMillis()`。** `UserMap` 依赖 `Files`、`Log`，全是 Android 耦合；注入之后合并器是纯的，测试可以精确断言。

`AntForest.manualFetchPkCampFriends()` 因此只剩薄薄一层外壳：发 RPC、把字符串喂给合并器、拿 `UserMap.getUserIdSet()`、写文件。

## 6.1 步骤明细

1. **拉榜**：`queryTopEnergyChallengeRanking()`。`success != true` → 记日志、直接返回，不动已有快照。

2. **取 status**：读 `rankMemberStatus` 存入快照。

   **落盘时的保留规则以「新名单是否为空」为判据，而不是以 `rankMemberStatus` 为判据。**
   即：新抓到的 `members` 为空、而磁盘上已有非空名单时，保留旧名单，只更新
   `rankMemberStatus` 与 `updatedAt`；新名单非空则直接覆盖。

   这条是硬要求，不是优化。赛季结束后如果把 `members` 清空，用户一点刷新就会亲手抹掉
   他唯一想留住的那份名单 —— 而"赛季结束后还能翻出这批人"正是这个功能存在的理由。
   任何情况下都只增不毁：抓到新名单才覆盖，抓不到就保留旧的。

   > **修订记录（2026-08-30，实现期）**：本条原先写作「`rankMemberStatus != "JOIN"`
   > 时保留旧 members」。改为以「新名单是否为空」为判据，理由：空列表才是真正的数据
   > 丢失条件，status 只是它的一个代理指标；若按 status 判据，一旦该字段出现非 JOIN
   > 的中间态（赛季之间、预热期、或未预期的枚举值）而服务端仍返回了有效名单，新数据
   > 会被拒绝写入，名单从此冻结再不更新 —— 那是比它想防的问题更糟的后果。
   > 与「允许缩小到较小的非空名单」那条裁决同源：非空的新数据就是事实，理应胜出。
   > UI 顶部那条「未加入 PK 赛」横幅仍然读 `rankMemberStatus`，不受此改动影响。

3. **建索引**：
   - `totalData`（全部 30 条）→ `userId -> (rank, energySummation)`。**这是 rank 的唯一权威来源。**
   - `friendRanking`（前 20 条）→ `userId -> (displayName, headPortrait, treeAmount, challengeRankLevelName)`

4. **补全**：`totalData` 里有、但 `friendRanking` 里没有的 `userId`，按每批 **20** 个调 `fillUserRobFlag(userIdList, needFillUserInfo = true)`，从返回的 `friendRanking` 里取同样四个资料字段。批量大小 20 与 `AntForest.kt:2039` 现有做法一致。

   **关键**：`fillUserRobFlag` 返回的 `rank` 全是 `-1`（已在抓包中确认），必须丢弃，只取资料字段。

5. **剔自己**：从 `myself.userId` 拿到自己的 id，从结果里排除。

6. **diff 好友**：`UserMap.getUserIdSet()` 里有 → `isFriend = true`，否则 `false`。依据 C4，`friend.json` 是干净的真实好友表，这个 diff 可靠。

7. **兜底**：`totalData` 里有、但两轮都没拿到资料的 `userId`，仍然写入，`displayName` 和 `headPortrait` 留空串，由 UI 兜底显示。宁可多一条不完整的，也不要静默丢人。

8. **落盘**：按 `rank` 升序排序后写文件。

### 与自动任务的互斥

`ManualTask.run()` 在 `isManualRunning == true` 时直接返回（C9）。也就是说刷新期间再点刷新会被静默丢弃。UI 侧在轮询等待期间禁用刷新按钮，避免用户以为点了没反应。

## 7. UI

### 入口

`SettingsContent.kt` 的「账号配置」区块，在用户卡片列表**之后**追加一个 `SettingsItem`：

```
账号配置
┌────────────────────────────┐
│ 迷迷糊糊喜欢干饭        ›   │
│ 淡泊                    ›   │
└────────────────────────────┘
┌────────────────────────────┐
│ 👥 PK 阵营好友          ›   │
│    当前登录账号的同阵营成员    │
└────────────────────────────┘
```

用 `SettingsItem(title, subtitle, icon, onClick)` 现成组件（`components/SettingsItem.kt:24`），图标 `Icons.Rounded.Groups`（该文件已 import）。

点击**直接启动 Activity**：`context.startActivity(Intent(context, PkCampActivity::class.java))`，与同文件里「RPC 调试工具」（`SettingsContent.kt:127`）和「手动调度任务」（`:137`）的既有做法一致。

**不新增 `MainUiEvent`。** `MainUiEvent` 是留给必须由 `MainActivity` 处理的事情（打开日志文件、验证门控、清空配置）；单纯跳一个 Activity 走事件绕一圈是多余的仪式。

### 页面分层

照 `ManualTaskActivity` + `ManualTaskScreen` 的既有分法：

- **`ui/PkCampActivity.kt`** — 壳。`AppTheme` + `WatermarkLayer` + `finish()`。`onCreate` 里只需要拿到当前登录账号的 `userId`：`DataStore.get("activedUser", UserEntity::class.java)?.userId`，取法同 `ManualTaskActivity.kt:51`。

  **不调用** `Model.initAllModel()` / `Config.load()` / `UserMap.load()`。`ManualTaskActivity` 需要它们是因为 `ManualTaskScreen` 要读模型字段；本页面只读 `pkCamp.json`，`isFriend` 已在支付宝进程算好落盘，UI 侧不需要 `UserMap`。少加载就少一份耦合。
- **`ui/viewmodel/PkCampViewModel.kt`** — 读文件、发广播、轮询 mtime、维护状态。
- **`ui/screen/PkCampScreen.kt`** — 纯 UI。`Scaffold` + `TopAppBar`（返回 + 刷新）+ 筛选 chips + `LazyColumn`。

### 状态机

```kotlin
sealed interface PkCampUiState {
    data object NoAccount : PkCampUiState           // 拿不到当前登录账号
    data object NeverFetched : PkCampUiState         // 文件不存在、为空或解析失败（注意 C12：文件会被自动创建成空文件）
    data class Content(
        val members: List<PkCampMember>,
        val updatedAt: Long,
        val joined: Boolean                          // rankMemberStatus == "JOIN"
    ) : PkCampUiState
}
```

`joined = false` 时**不是**一个独立的空状态，而是在 `Content` 上挂一条横幅 —— 因为按第 6 节第 2 步，未参赛时 `members` 会保留上一次抓到的名单，那份名单恰恰是最该给用户看的东西。

刷新状态与上面正交，单独一个 `isRefreshing: Boolean`；刷新失败用 Snackbar 提示，**不覆盖已有快照**。

各状态文案：

| 状态 | 展示 |
|---|---|
| `NoAccount` | 「未检测到已登录账号，请先在支付宝登录并让模块跑一次」 |
| `NeverFetched` | 「还没有数据，点右上角刷新」 |
| `Content`，`joined = true` | 列表 + 顶部「更新于 08-30 09:51」 |
| `Content`，`joined = false`，`members` 非空 | 顶部横幅「当前账号未加入 PK 赛，以下是最后一次抓到的名单」+ 列表 |
| `Content`，`joined = false`，`members` 为空 | 「当前账号未加入 PK 赛」 |
| 刷新超时 | Snackbar：「未收到支付宝进程响应，请确认模块已激活、支付宝正在运行」，同时保留旧快照 |

### 列表项

```
┌──────────────────────────────────────┐
│ (头像) 淡泊            [陌生人]        │
│        #1 · 41280 能量 · 439 棵 · 青铜 │
└──────────────────────────────────────┘
```

- 头像空串 → 昵称首字 + 主题色圆形占位
- 昵称空串 → 显示 `用户 ****0312`（userId 后四位），避免出现空白行
- `isFriend` → 「已是好友」灰色 chip；否则「陌生人」主题色 chip

### 排序与筛选

- **排序**：`rank` 升序，与支付宝原页面一致。不提供排序切换。
- **筛选**：TopAppBar 下一行 `FilterChip`：「只看陌生人 (N)」/「全部 (M)」，两个 chip 都带计数。

**默认「只看陌生人」。** 这是自行决定的：这个页面存在的唯一理由就是找出还不是好友的人，已是好友的对这个目的没有价值。计数放在 chip 上，用户一眼能看出被筛掉了多少，不会误以为数据缺失。

### 进页面行为

**只读缓存，不自动抓取。** 也是自行决定的。理由：模块未激活或支付宝没在运行时，自动抓取会让用户干等 30s 超时才看到错误；而只读缓存能立刻显示上次的快照和更新时间，把「要不要发请求」交给用户。刷新是一次明确的点击，代价和结果都清楚。

## 8. 头像加载与明文 HTTP

新增 Coil 3（Kotlin 2.3.0，用 Coil 3.x）：

- `gradle/libs.versions.toml`：加 `coil = "3.2.0"`（查证过的 Maven Central 最新稳定版），加 `coil-compose`、`coil-network-okhttp` 两个 library 条目
- `app/build.gradle.kts`：`implementation(libs.coil.compose)`、`implementation(libs.coil.network.okhttp)`

Coil 3 必须显式引入网络 fetcher，`coil-network-okhttp` 复用项目已有的 OkHttp（C10）。

### 明文 HTTP（C11）

`headPortrait` 形如 `http://tfs.alipayobjects.com/images/partner/xxx_160X160`。targetSdk 36 下明文 HTTP 被默认拦截，**不处理会导致所有头像静默加载失败**。

**方案**：加载前把 `http://` 前缀重写成 `https://`。阿里 CDN 支持 HTTPS，这样不动 manifest、不降低全局网络安全策略。

```kotlin
private fun String.toHttpsUrl(): String =
    if (startsWith("http://")) "https://" + substring(7) else this
```

**备选**（仅当实测该域名不支持 HTTPS 时启用）：加 `res/xml/network_security_config.xml`，只对 `tfs.alipayobjects.com` 和 `mdn.alipayobjects.com` 开 `cleartextTrafficPermitted="true"`，manifest 里挂 `android:networkSecurityConfig`。不使用全局 `usesCleartextTraffic="true"`。

## 9. 改动清单

| 文件 | 改动 |
|---|---|
| `gradle/libs.versions.toml` | 新增 `coil` 版本与 `coil-compose`、`coil-network-okhttp` 条目 |
| `app/build.gradle.kts` | 引入两个 Coil 依赖 |
| `app/src/main/AndroidManifest.xml` | 声明 `.ui.PkCampActivity`，`android:exported="false"` |
| `entity/PkCampSnapshot.kt` | **新增** `PkCampMember` + `PkCampSnapshot` |
| `task/antForest/PkCampMerger.kt` | **新增** 纯函数合并器（JVM 可测，只用 Jackson） |
| `task/antForest/PkCampStore.kt` | **新增** 纯函数：序列化 + 「只增不毁」合并规则（JVM 可测） |
| `app/src/test/java/.../PkCampMergerTest.kt` | **新增** 合并器单元测试 |
| `app/src/test/java/.../PkCampStoreTest.kt` | **新增** 存储与不变式单元测试 |
| `task/customTasks/CustomTask.kt` | 新增 `FOREST_PK_CAMP("PK阵营好友")` |
| `task/customTasks/ManualTask.kt` | `when (task)` 加分支 → `getForestInstance()?.manualFetchPkCampFriends()` |
| `task/antForest/AntForest.kt` | 新增 `manualFetchPkCampFriends()`：抓取 + 合并 + diff + 落盘 |
| `ui/PkCampActivity.kt` | **新增** Activity 壳 |
| `ui/screen/PkCampScreen.kt` | **新增** 列表 UI |
| `ui/screen/components/PkCampMemberRow.kt` | **新增** 单个成员行（头像/首字占位、chip） |
| `ui/viewmodel/PkCampViewModel.kt` | **新增** 读文件 / 发广播 / 轮询 |
| `ui/screen/content/SettingsContent.kt` | 「账号配置」区块加入口，直接 `startActivity` |
| `hook/ApplicationHook.kt` | 手动任务 `when (task)` 加一个带注释的空分支，避免落进 `else` 打出误导性的「❌ 无效的任务指令」（C8） |

`AntForestRpcCall.java` **不改动** —— 两个 RPC 方法都已就绪（C1）。

`ui/MainActivity.kt` **不改动** —— 入口直接 `startActivity`，不走 `MainUiEvent`（见第 7 节）。

### 关于 C6（广播只在 DEBUG 注册）

本期**不动**这个分支。按 CLAUDE.md 的流程日常构建的是 `assembleDebug`，调试与自测不受影响。正式包要用刷新功能就必须把 `registerBroadcastReceiver` 移出 `if (BuildConfig.DEBUG)`，那是一个影响面更大的独立决定（涉及正式包暴露广播接口的安全考量），留给后续单独处理。本期在页面上不做任何暗示刷新在正式包可用的表述。

## 10. 失败模式与处理

| 场景 | 行为 |
|---|---|
| 模块未激活 / 支付宝未运行 | 广播无人接收 → 30s 超时 → Snackbar 提示，保留旧快照 |
| 未登录支付宝 | `DataStore` 里没有 `activedUser` → `NoAccount` 状态 |
| 未参加 PK 赛 / 赛季已结束 | 落盘 `rankMemberStatus != "JOIN"`，`members` **保留上次的名单** → `Content(joined = false)`，横幅提示 + 仍展示名单 |
| 拉榜 RPC 失败 | 记日志，**不写文件**，旧快照保持不变 → UI 表现为超时 |
| `fillUserRobFlag` 某批失败 | 该批成员仍写入，资料字段留空，UI 兜底显示 |
| 刷新期间再点刷新 | 按钮已禁用；即使触发也会被 `isManualRunning` 丢弃（C9） |
| 头像 URL 为空串 | 昵称首字圆形占位 |
| 昵称为空串 | 显示 `用户 ****后四位` |

## 11. 验证方案

### 第一步：验证落盘

按 CLAUDE.md 的完整 Receiver 刷新流程装包（安装 → 停模块 → 停支付宝 → 杀残留进程 → 重启支付宝），然后：

```bash
adb shell am broadcast -a com.eg.android.AlipayGphone.sesame.manual_task --es task FOREST_PK_CAMP
```

读出落盘文件检查：

```bash
adb shell su -c 'cat /sdcard/Android/media/com.eg.android.AlipayGphone/sesame-TK/config/<userId>/pkCamp.json'
```

判定标准：

- `members` 条数等于 `totalData` 条数减 1（自己被剔除）
- `rank` 升序排列，**没有任何 `-1`**。注意：自己被剔除后 rank 必然出现一处缺口
  （例如自己是 rank 2 时，实际得到 1,3,4…30），这是正确行为，不要按「连续递增」判定
- `selfUserId` 不出现在 `members` 里
- `isFriend` 同时存在 `true` 和 `false`（抓包样本里 30 人中有真好友也有陌生人）
- 前 20 名与第 21-30 名都有非空 `displayName`（证明 `fillUserRobFlag` 补全生效）

### 第二步：验证 UI

打开模块 → 设置页 → 账号配置 → PK 阵营好友：

- 首次进入（清掉 `pkCamp.json` 后）显示 `NeverFetched` 文案
- 点刷新，30s 内列表出现，顶部显示更新时间
- 默认落在「只看陌生人」，chip 上两个计数与文件内容对得上
- 切到「全部」能看到「已是好友」chip
- 头像正常显示（验证 https 重写生效）；把某人的 `headPortrait` 手动改成空串，重进页面确认首字占位
- 强杀支付宝后再点刷新，30s 后出现超时 Snackbar 且**旧列表仍在**

### 第三步：验证快照不被摧毁

这是本设计最关键的一条不变式，必须单独验。把落盘文件里的 `rankMemberStatus` 手动改成 `"QUIT"`，然后点刷新：

- `members` 长度**不减少**
- 页面出现「当前账号未加入 PK 赛，以下是最后一次抓到的名单」横幅，且名单仍然完整可见

如果 `members` 被清空，说明第 6 节第 2 步实现错了 —— 这个 bug 会在赛季结束时静默吃掉用户唯一想保留的数据，务必在合并前确认。

## 12. 后续：一键添加

本期不实现，但记录已知与未知，供下一轮设计：

- **已知**：`userId` 是加好友请求的必要入参，本期落盘已保存；执行必须在支付宝进程（C2），因此会复用本期建立的「广播触发 + 落盘」链路；`isFriend` 已经算好，可直接用来过滤掉已是好友的人。
- **未知**：加好友的 RPC 接口本身。需要在支付宝里手动加一次陌生人、用 `capture` 日志或 `serve-debug/rpc_debug.py` 抓出 `operationType` 与 `requestData` 结构。
- **需要考虑**：批量加好友大概率有频率限制与风控，一键添加应当串行 + 间隔 + 失败可续，而不是并发轰炸。这会是下一个 spec 的主要内容。

## 13. 交付后遗留事项（2026-08-30 实现完成时记录）

按优先级排列。前两项由终审 review 提出，经权衡判定超出本轮范围、需产品决策。

### 13.1 跨赛季覆盖会丢掉正是本功能想留住的人（最高优先级）

`PkCampStore.reconcile` 只保证「不会覆盖成空」。赛季 N+1 的非空名单会**整体替换**赛季 N
的名单。而「一键添加好友」要更晚才上线 —— 第一批陌生人很可能在用户有能力添加他们之前
就已被新赛季名单冲掉，这恰好落空了本功能存在的理由。

可选解法：按 `userId` 求并集，给 `PkCampMember` 增加 `lastSeenAt`，UI 上区分「本赛季」
与「往期」成员。这会改变 `pkCamp.json` 格式，且需决定往期成员如何展示、是否设上限，
故留给产品决策，本轮未实现。

### 13.2 `Files.write2File` 非原子写

先截断再写。若进程在写入瞬间被杀，名单会被毁掉，UI 随后显示成「还没有数据」。
`DataStore.saveToDisk` 已用 tmp + rename 可参照。但 `write2File` 是全项目共用函数，
改它超出本分支范围。

### 13.3 真机 UI 验证尚未完成

已通过：26 项单元测试、`assembleDebug`、以及真机**数据链路**验证（广播 → 支付宝进程
重写 `pkCamp.json`，29 人 / 1 好友 / 28 陌生人 / 3 条空头像）。

但**渲染层未经任何人眼确认**（验证时设备处于图案锁屏，随后断开连接）。风险最高的三处：
头像加载（若 https 不可服务则 `AsyncImage` 失败、露出底层首字圆圈 —— 已按终审建议做了
兜底但未实测）；`joined=false` 横幅 + 「更新于」+ chip 行 + `fillMaxSize` 的 LazyColumn
在普通 `Column` 里的叠放（静态分析正确，因 Column 最后测量无权重子项）；刷新转圈 →
超时 Snackbar 的路径。合并进 release 前应先真机打开一次。

### 13.4 `.gitignore:82` 的裸 `test` 规则

该规则匹配任意名为 `test` 的目录，`app/src/test/` 因此整体被忽略 —— 本分支的测试文件
是靠 `git add -f` 才入库的，而在此之前仓库里一个测试文件都没有（既有的
`LogChunkerTest.kt` 从未提交）。下一位贡献者写的测试会静默消失。建议改成 `/test/`
之类更精确的写法。属仓库级问题，与本功能无关。

### 13.5 合并时须处理

分支上的 `a90462ce`（`fix(sports): 补充 manualSyncStep`）是为让分支能编译而加的最小
兜底 —— 已提交的 `main` 因 `ManualTask.kt` 调用了不存在的 `AntSports.manualSyncStep()`
而无法编译。用户工作区里有独立且更完整的实现（驱动捕获到的 `rpcManagerInstance`），
分支上的兜底只是转调 `syncStepTask()`，语义不同。**合并时应丢弃 `a90462ce`，采用用户
自己的版本。**
