# PK 阵营好友列表 实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在模块「账号配置」下新增一个页面，展示蚂蚁森林 PK 赛同阵营成员（含头像、排名、能量、是否已是好友），并支持刷新。

**Architecture:** 抓取只能在支付宝进程完成（RPC 依赖 `rpcBridge`）。因此拆成三层：纯函数合并器 `PkCampMerger`（JVM 可测）→ `AntForest.manualFetchPkCampFriends()` I/O 外壳（发 RPC、落盘 `pkCamp.json`）→ 模块 UI 进程读该文件展示，刷新走 `manual_task` 广播 + 轮询文件 mtime。

**Tech Stack:** Kotlin、Jetpack Compose + Material 3、Jackson（`jackson-module-kotlin` 2.20.1）、Coil 3、JUnit 4、Gradle Kotlin DSL

**Spec:** `docs/superpowers/specs/2026-08-30-pk-camp-friends-design.md`

## Global Constraints

- **合并器只用 Jackson，绝不用 `org.json`。** `org.json` 在 JVM 单元测试里是 android.jar 的桩，一调即抛 `RuntimeException("Stub!")`。已实测 Jackson 在 `:app:testDebugUnitTest` 下可用。
- **纯函数不碰 Android。** `PkCampMerger` 内部不得引用 `UserMap`、`Files`、`Log`、`System.currentTimeMillis()`；`friendIds: Set<String>` 和 `now: Long` 一律由调用方注入。
- **`rank` 只从 `queryTopEnergyChallengeRanking` 的 `totalData` 取。** `fillUserRobFlag` 返回的 `rank` 恒为 `-1`，必须丢弃。
- **快照只增不毁。** 拉榜失败 → 不写文件；`rankMemberStatus != "JOIN"` → 只更新 `rankMemberStatus` 与 `updatedAt`，`members` 保留原样。任何情况下不得清空已有 `members`。
- **`fillUserRobFlag` 批量大小 = 20**，与 `AntForest.kt:2039` 现有做法一致。
- **头像 URL 加载前把 `http://` 重写为 `https://`。** targetSdk 36 默认禁明文 HTTP，不重写会导致头像全部静默失败。
- 单元测试命令：`./gradlew :app:testDebugUnitTest --tests "<FQCN>" --console=plain`
- 构建命令：`./gradlew assembleDebug`
- 落盘路径：`/sdcard/Android/media/com.eg.android.AlipayGphone/sesame-TK/config/<userId>/pkCamp.json`

---

## File Structure

| 文件 | 职责 |
|---|---|
| `entity/PkCampSnapshot.kt` | 数据模型：`PkCampMember`、`PkCampSnapshot`。无逻辑。 |
| `task/antForest/PkCampMerger.kt` | 纯函数：解析两个 RPC 响应、合并、diff、排序。JVM 可测。 |
| `app/src/test/java/.../PkCampMergerTest.kt` | 合并器单元测试。 |
| `task/antForest/AntForest.kt` | 新增 `manualFetchPkCampFriends()`：I/O 外壳，发 RPC + 落盘。 |
| `task/customTasks/CustomTask.kt` | 新增枚举值 `FOREST_PK_CAMP`。 |
| `task/customTasks/ManualTask.kt` | 新增 `when` 分支，调用森林实例的方法。 |
| `hook/ApplicationHook.kt` | 新增空 `when` 分支，避免误导性错误日志。 |
| `ui/viewmodel/PkCampViewModel.kt` | 读文件、发广播、轮询 mtime、维护 UI 状态。 |
| `ui/screen/PkCampScreen.kt` | 纯 UI：TopAppBar、筛选 chips、列表、各空状态。 |
| `ui/screen/components/PkCampMemberRow.kt` | 单个成员行，含头像/首字占位与 chip。 |
| `ui/PkCampActivity.kt` | Activity 壳：主题 + 水印 + 取当前账号 uid。 |
| `ui/screen/content/SettingsContent.kt` | 「账号配置」区块加入口。 |
| `gradle/libs.versions.toml`、`app/build.gradle.kts`、`AndroidManifest.xml` | Coil 依赖与 Activity 声明。 |

任务顺序：先建可测的纯逻辑内核（Task 1-2），再接支付宝进程侧（Task 3-4），最后做 UI（Task 5-8）。每个任务结束都能独立验证。

---

### Task 1: 数据模型 PkCampSnapshot

**Files:**
- Create: `app/src/main/java/fansirsqi/xposed/sesame/entity/PkCampSnapshot.kt`

**Interfaces:**
- Consumes: 无
- Produces: `PkCampMember(userId: String, displayName: String, headPortrait: String, rank: Int, energySummation: Long, treeAmount: Int, challengeRankLevelName: String, isFriend: Boolean)`；`PkCampSnapshot(updatedAt: Long, selfUserId: String, rankMemberStatus: String, members: List<PkCampMember>)`

这两个类被 Task 2 的合并器、Task 4 的落盘、Task 6 的 ViewModel 共用。字段名同时也是 JSON 的键名，Jackson 直接按属性名序列化，不加任何注解。

- [ ] **Step 1: 创建数据类**

```kotlin
package fansirsqi.xposed.sesame.entity

/**
 * PK 阵营成员。
 * 字段名即 pkCamp.json 的键名，由 Jackson 按属性名直接序列化，不要加注解。
 */
data class PkCampMember(
    val userId: String = "",
    val displayName: String = "",
    val headPortrait: String = "",
    val rank: Int = -1,
    val energySummation: Long = 0L,
    val treeAmount: Int = 0,
    val challengeRankLevelName: String = "",
    val isFriend: Boolean = false
)

/**
 * 一次抓取的完整快照，落盘为 <CONFIG_DIR>/<userId>/pkCamp.json
 */
data class PkCampSnapshot(
    val updatedAt: Long = 0L,
    val selfUserId: String = "",
    val rankMemberStatus: String = "",
    val members: List<PkCampMember> = emptyList()
)
```

所有属性都给默认值，这样 Jackson 反序列化旧版本或残缺文件时不会因缺字段抛异常。

- [ ] **Step 2: 确认编译通过**

Run: `./gradlew :app:compileDebugKotlin --console=plain`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: 提交**

```bash
git add app/src/main/java/fansirsqi/xposed/sesame/entity/PkCampSnapshot.kt
git commit -m "feat(forest): 新增 PK 阵营成员快照数据模型"
```

---

### Task 2: 纯函数合并器 PkCampMerger（TDD）

**Files:**
- Create: `app/src/main/java/fansirsqi/xposed/sesame/task/antForest/PkCampMerger.kt`
- Test: `app/src/test/java/fansirsqi/xposed/sesame/task/antForest/PkCampMergerTest.kt`

**Interfaces:**
- Consumes: `PkCampMember`、`PkCampSnapshot`（Task 1）
- Produces:
  - `PkCampMerger.missingUserIds(rankingJson: String): List<String>` — 返回 `totalData` 里有、`friendRanking` 里没有的 userId，供调用方决定补哪些人
  - `PkCampMerger.merge(rankingJson: String, fillJsons: List<String>, friendIds: Set<String>, now: Long): PkCampSnapshot?` — 拉榜失败返回 `null`
  - `PkCampMerger.BATCH_SIZE: Int = 20`

这是整个功能的逻辑核心，也是唯一能在 JVM 上完整测试的部分。Task 4 的 I/O 外壳只负责把字符串喂进来。

**RPC 响应结构提要**（来自实际抓包，测试夹具照此构造）：

```
{ "resData": {
    "success": true,
    "rankMemberStatus": "JOIN",
    "myself":   { "userId": "<自己>" },
    "totalData":     [ { "userId": "..", "rank": 1, "energySummation": 41280 }, ... ],   // 全部 30 人，rank 权威
    "friendRanking": [ { "userId": "..", "displayName": "..", "headPortrait": "..",
                         "treeAmount": 439, "challengeRankLevelName": "青铜",
                         "rank": 1, "energySummation": 41280 }, ... ]                     // 仅前 20 人
} }
```

`fillUserRobFlag` 的响应只有 `resData.friendRanking`，且其中每条的 `rank` 都是 `-1`。

- [ ] **Step 1: 写失败测试**

```kotlin
package fansirsqi.xposed.sesame.task.antForest

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PkCampMergerTest {

    /** 3 人在 totalData，其中 2 人有完整资料；self 是 s-self */
    private val rankingJson = """
        {"resData":{
          "success":true,
          "rankMemberStatus":"JOIN",
          "myself":{"userId":"s-self"},
          "totalData":[
            {"userId":"u-1","rank":1,"energySummation":41280},
            {"userId":"s-self","rank":2,"energySummation":31817},
            {"userId":"u-3","rank":3,"energySummation":13938}
          ],
          "friendRanking":[
            {"userId":"u-1","displayName":"淡泊","headPortrait":"http://cdn/a",
             "treeAmount":439,"challengeRankLevelName":"青铜","rank":1,"energySummation":41280},
            {"userId":"s-self","displayName":"我","headPortrait":"","treeAmount":51,
             "challengeRankLevelName":"青铜","rank":2,"energySummation":31817}
          ]
        }}
    """.trimIndent()

    /** u-3 的补全响应，注意 rank 是 -1，必须被丢弃 */
    private val fillJson = """
        {"resData":{
          "success":true,
          "friendRanking":[
            {"userId":"u-3","displayName":"友良","headPortrait":"http://cdn/c",
             "treeAmount":41,"challengeRankLevelName":"青铜","rank":-1,"energySummation":0}
          ]
        }}
    """.trimIndent()

    @Test
    fun `missingUserIds returns ids present in totalData but absent from friendRanking`() {
        assertEquals(listOf("u-3"), PkCampMerger.missingUserIds(rankingJson))
    }

    @Test
    fun `merge excludes self from members`() {
        val snapshot = PkCampMerger.merge(rankingJson, listOf(fillJson), emptySet(), 1000L)!!
        assertEquals(listOf("u-1", "u-3"), snapshot.members.map { it.userId })
        assertEquals("s-self", snapshot.selfUserId)
    }

    @Test
    fun `merge takes rank from totalData and ignores the minus one from fillUserRobFlag`() {
        val snapshot = PkCampMerger.merge(rankingJson, listOf(fillJson), emptySet(), 1000L)!!
        val u3 = snapshot.members.first { it.userId == "u-3" }
        assertEquals(3, u3.rank)
        assertEquals(13938L, u3.energySummation)
        // 资料字段仍取自 fillUserRobFlag
        assertEquals("友良", u3.displayName)
        assertEquals(41, u3.treeAmount)
    }

    @Test
    fun `merge marks isFriend from the injected friend id set`() {
        val snapshot = PkCampMerger.merge(rankingJson, listOf(fillJson), setOf("u-3"), 1000L)!!
        assertFalse(snapshot.members.first { it.userId == "u-1" }.isFriend)
        assertTrue(snapshot.members.first { it.userId == "u-3" }.isFriend)
    }

    @Test
    fun `merge sorts members by rank ascending`() {
        val snapshot = PkCampMerger.merge(rankingJson, listOf(fillJson), emptySet(), 1000L)!!
        assertEquals(listOf(1, 3), snapshot.members.map { it.rank })
    }

    @Test
    fun `merge keeps members with no profile data instead of dropping them`() {
        // 不提供任何 fill 响应，u-3 就没有资料，但必须仍然出现
        val snapshot = PkCampMerger.merge(rankingJson, emptyList(), emptySet(), 1000L)!!
        val u3 = snapshot.members.first { it.userId == "u-3" }
        assertEquals("", u3.displayName)
        assertEquals("", u3.headPortrait)
        assertEquals(3, u3.rank)
    }

    @Test
    fun `merge uses the injected timestamp`() {
        val snapshot = PkCampMerger.merge(rankingJson, listOf(fillJson), emptySet(), 4242L)!!
        assertEquals(4242L, snapshot.updatedAt)
    }

    @Test
    fun `merge returns null when success is false`() {
        val failed = """{"resData":{"success":false,"resultDesc":"系统繁忙"}}"""
        assertNull(PkCampMerger.merge(failed, emptyList(), emptySet(), 1000L))
    }

    @Test
    fun `merge returns null on unparseable input`() {
        assertNull(PkCampMerger.merge("not json at all", emptyList(), emptySet(), 1000L))
    }

    @Test
    fun `merge yields empty members when not joined`() {
        val notJoined = """
            {"resData":{"success":true,"rankMemberStatus":"QUIT",
             "myself":{"userId":"s-self"},"totalData":[],"friendRanking":[]}}
        """.trimIndent()
        val snapshot = PkCampMerger.merge(notJoined, emptyList(), emptySet(), 1000L)!!
        assertEquals("QUIT", snapshot.rankMemberStatus)
        assertTrue(snapshot.members.isEmpty())
    }

    @Test
    fun `missingUserIds returns empty list on unparseable input`() {
        assertEquals(emptyList<String>(), PkCampMerger.missingUserIds("garbage"))
    }
}
```

注意最后那个 `merge yields empty members when not joined` 测的是**合并器**返回空 members——这是正确的，因为合并器是纯函数、不知道磁盘上有什么。「保留旧名单」的责任在 Task 4 的落盘外壳，Task 4 会单独测。

- [ ] **Step 2: 运行测试确认失败**

Run: `./gradlew :app:testDebugUnitTest --tests "fansirsqi.xposed.sesame.task.antForest.PkCampMergerTest" --console=plain`
Expected: FAIL，编译错误 `Unresolved reference: PkCampMerger`

- [ ] **Step 3: 实现合并器**

```kotlin
package fansirsqi.xposed.sesame.task.antForest

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import fansirsqi.xposed.sesame.entity.PkCampMember
import fansirsqi.xposed.sesame.entity.PkCampSnapshot

/**
 * PK 阵营成员合并器：纯函数，无任何 Android 依赖，可在 JVM 单元测试中完整覆盖。
 *
 * 两条硬约束（改动前请先读 docs/superpowers/specs/2026-08-30-pk-camp-friends-design.md）：
 *  1. 只用 Jackson，不要用 org.json —— 后者在 JVM 测试里是桩，一调就抛 Stub 异常。
 *  2. friendIds 与 now 由调用方注入，本类不得触碰 UserMap / System.currentTimeMillis()。
 */
object PkCampMerger {

    /** fillUserRobFlag 每批的 userId 数量，与 AntForest.kt:2039 现有做法保持一致 */
    const val BATCH_SIZE: Int = 20

    private val mapper = jacksonObjectMapper()

    /** 成员资料，仅来自 friendRanking / fillUserRobFlag，不含 rank */
    private data class Profile(
        val displayName: String,
        val headPortrait: String,
        val treeAmount: Int,
        val challengeRankLevelName: String
    )

    private fun readResData(json: String): JsonNode? = try {
        mapper.readTree(json)?.get("resData")
    } catch (_: Exception) {
        null
    }

    private fun readProfiles(resData: JsonNode?): Map<String, Profile> {
        val ranking = resData?.get("friendRanking") ?: return emptyMap()
        val out = LinkedHashMap<String, Profile>()
        ranking.forEach { node ->
            val uid = node["userId"]?.asText().orEmpty()
            if (uid.isNotEmpty()) {
                out[uid] = Profile(
                    displayName = node["displayName"]?.asText().orEmpty(),
                    headPortrait = node["headPortrait"]?.asText().orEmpty(),
                    treeAmount = node["treeAmount"]?.asInt(0) ?: 0,
                    challengeRankLevelName = node["challengeRankLevelName"]?.asText().orEmpty()
                )
            }
        }
        return out
    }

    /**
     * totalData 里有、friendRanking 里没有的 userId —— 这些人需要用 fillUserRobFlag 补资料。
     * 输入无法解析时返回空列表。
     */
    fun missingUserIds(rankingJson: String): List<String> {
        val resData = readResData(rankingJson) ?: return emptyList()
        val known = readProfiles(resData).keys
        val total = resData["totalData"] ?: return emptyList()
        return total.mapNotNull { node ->
            node["userId"]?.asText()?.takeIf { it.isNotEmpty() && it !in known }
        }
    }

    /**
     * 合并成完整快照。
     *
     * @param rankingJson queryTopEnergyChallengeRanking 的原始返回
     * @param fillJsons   各批 fillUserRobFlag 的原始返回
     * @param friendIds   真实好友 id 集合，由调用方从 UserMap.getUserIdSet() 传入
     * @param now         时间戳，由调用方注入
     * @return 拉榜失败或无法解析时返回 null，调用方据此跳过落盘、保留旧快照
     */
    fun merge(
        rankingJson: String,
        fillJsons: List<String>,
        friendIds: Set<String>,
        now: Long
    ): PkCampSnapshot? {
        val resData = readResData(rankingJson) ?: return null
        if (resData["success"]?.asBoolean(false) != true) return null

        val selfUserId = resData["myself"]?.get("userId")?.asText().orEmpty()

        // 资料来源：先 friendRanking（前 20），再各批 fillUserRobFlag 补齐
        val profiles = LinkedHashMap<String, Profile>()
        profiles.putAll(readProfiles(resData))
        fillJsons.forEach { profiles.putAll(readProfiles(readResData(it))) }

        // rank 与 energySummation 只认 totalData —— fillUserRobFlag 的 rank 恒为 -1
        val members = ArrayList<PkCampMember>()
        resData["totalData"]?.forEach { node ->
            val uid = node["userId"]?.asText().orEmpty()
            if (uid.isEmpty() || uid == selfUserId) return@forEach
            val profile = profiles[uid]
            members.add(
                PkCampMember(
                    userId = uid,
                    displayName = profile?.displayName.orEmpty(),
                    headPortrait = profile?.headPortrait.orEmpty(),
                    rank = node["rank"]?.asInt(-1) ?: -1,
                    energySummation = node["energySummation"]?.asLong(0L) ?: 0L,
                    treeAmount = profile?.treeAmount ?: 0,
                    challengeRankLevelName = profile?.challengeRankLevelName.orEmpty(),
                    isFriend = uid in friendIds
                )
            )
        }

        return PkCampSnapshot(
            updatedAt = now,
            selfUserId = selfUserId,
            rankMemberStatus = resData["rankMemberStatus"]?.asText().orEmpty(),
            members = members.sortedBy { it.rank }
        )
    }
}
```

- [ ] **Step 4: 运行测试确认全部通过**

Run: `./gradlew :app:testDebugUnitTest --tests "fansirsqi.xposed.sesame.task.antForest.PkCampMergerTest" --console=plain`
Expected: BUILD SUCCESSFUL，11 个测试全绿

若有失败，报告页在 `app/build/reports/tests/testDebugUnitTest/index.html`。

- [ ] **Step 5: 提交**

```bash
git add app/src/main/java/fansirsqi/xposed/sesame/task/antForest/PkCampMerger.kt \
        app/src/test/java/fansirsqi/xposed/sesame/task/antForest/PkCampMergerTest.kt
git commit -m "feat(forest): 新增 PK 阵营成员合并器与单元测试"
```

---

### Task 3: 落盘读写与「只增不毁」规则（TDD）

**Files:**
- Create: `app/src/main/java/fansirsqi/xposed/sesame/task/antForest/PkCampStore.kt`
- Test: `app/src/test/java/fansirsqi/xposed/sesame/task/antForest/PkCampStoreTest.kt`

**Interfaces:**
- Consumes: `PkCampSnapshot`（Task 1）
- Produces:
  - `PkCampStore.serialize(snapshot: PkCampSnapshot): String`
  - `PkCampStore.deserialize(json: String): PkCampSnapshot?` — 空串/残缺/无法解析返回 `null`
  - `PkCampStore.reconcile(fresh: PkCampSnapshot, existing: PkCampSnapshot?): PkCampSnapshot` — 实现「只增不毁」

把序列化和「只增不毁」规则也做成纯函数单独测，是因为这条规则是整个设计里最关键的不变式（写错会在赛季结束时静默吃掉用户数据），必须有自动化测试守住，不能只靠手工验证。

- [ ] **Step 1: 写失败测试**

```kotlin
package fansirsqi.xposed.sesame.task.antForest

import fansirsqi.xposed.sesame.entity.PkCampMember
import fansirsqi.xposed.sesame.entity.PkCampSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PkCampStoreTest {

    private fun member(id: String, rank: Int) =
        PkCampMember(userId = id, displayName = "名$id", rank = rank)

    private val joined = PkCampSnapshot(
        updatedAt = 100L,
        selfUserId = "s",
        rankMemberStatus = "JOIN",
        members = listOf(member("u-1", 1), member("u-2", 2))
    )

    @Test
    fun `serialize then deserialize round trips`() {
        val back = PkCampStore.deserialize(PkCampStore.serialize(joined))
        assertEquals(joined, back)
    }

    @Test
    fun `deserialize returns null for blank content`() {
        // Files.getTargetFileofUser 会创建空文件，这个分支必须走通
        assertNull(PkCampStore.deserialize(""))
        assertNull(PkCampStore.deserialize("   "))
    }

    @Test
    fun `deserialize returns null for garbage`() {
        assertNull(PkCampStore.deserialize("not json"))
    }

    @Test
    fun `deserialize tolerates missing fields`() {
        val snapshot = PkCampStore.deserialize("""{"rankMemberStatus":"JOIN"}""")!!
        assertEquals("JOIN", snapshot.rankMemberStatus)
        assertEquals(emptyList<PkCampMember>(), snapshot.members)
    }

    @Test
    fun `reconcile keeps fresh members when joined`() {
        val existing = PkCampSnapshot(
            updatedAt = 1L, selfUserId = "s", rankMemberStatus = "JOIN",
            members = listOf(member("old", 9))
        )
        val result = PkCampStore.reconcile(joined, existing)
        assertEquals(listOf("u-1", "u-2"), result.members.map { it.userId })
        assertEquals(100L, result.updatedAt)
    }

    @Test
    fun `reconcile preserves existing members when fresh is not joined`() {
        // 这是本设计最关键的不变式：赛季结束后刷新不得抹掉名单
        val quit = joined.copy(rankMemberStatus = "QUIT", members = emptyList(), updatedAt = 200L)
        val result = PkCampStore.reconcile(quit, joined)
        assertEquals(listOf("u-1", "u-2"), result.members.map { it.userId })
        assertEquals("QUIT", result.rankMemberStatus)
        assertEquals(200L, result.updatedAt)
    }

    @Test
    fun `reconcile accepts fresh members when not joined but existing is empty`() {
        val quit = joined.copy(rankMemberStatus = "QUIT", members = emptyList())
        val result = PkCampStore.reconcile(quit, null)
        assertEquals(emptyList<PkCampMember>(), result.members)
        assertEquals("QUIT", result.rankMemberStatus)
    }

    @Test
    fun `reconcile never shrinks a non empty list to empty`() {
        val emptyFresh = joined.copy(members = emptyList(), updatedAt = 300L)
        val result = PkCampStore.reconcile(emptyFresh, joined)
        assertEquals(2, result.members.size)
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

Run: `./gradlew :app:testDebugUnitTest --tests "fansirsqi.xposed.sesame.task.antForest.PkCampStoreTest" --console=plain`
Expected: FAIL，`Unresolved reference: PkCampStore`

- [ ] **Step 3: 实现**

```kotlin
package fansirsqi.xposed.sesame.task.antForest

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import fansirsqi.xposed.sesame.entity.PkCampSnapshot

/**
 * pkCamp.json 的序列化与合并规则。纯函数，无 Android 依赖，可在 JVM 测试中覆盖。
 *
 * reconcile() 实现设计文档里的「只增不毁」不变式：任何情况下都不允许把已有的
 * members 清空。赛季结束后 rankMemberStatus 不再是 JOIN，若此时覆盖成空列表，
 * 用户一点刷新就会抹掉他唯一想留住的名单 —— 而那正是这个功能存在的理由。
 */
object PkCampStore {

    private val mapper = jacksonObjectMapper()
        .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)

    fun serialize(snapshot: PkCampSnapshot): String =
        mapper.writerWithDefaultPrettyPrinter().writeValueAsString(snapshot)

    /** 空串、残缺、非法 JSON 一律返回 null，交给调用方当作「从没抓过」处理 */
    fun deserialize(json: String): PkCampSnapshot? {
        if (json.isBlank()) return null
        return try {
            mapper.readValue<PkCampSnapshot>(json)
        } catch (_: Exception) {
            null
        }
    }

    /**
     * 合并新旧快照。新快照的 members 为空、而旧快照有内容时，保留旧的 members，
     * 只更新 rankMemberStatus / updatedAt / selfUserId。
     */
    fun reconcile(fresh: PkCampSnapshot, existing: PkCampSnapshot?): PkCampSnapshot {
        val kept = existing?.members.orEmpty()
        return if (fresh.members.isEmpty() && kept.isNotEmpty()) {
            fresh.copy(members = kept)
        } else {
            fresh
        }
    }
}
```

- [ ] **Step 4: 运行测试确认通过**

Run: `./gradlew :app:testDebugUnitTest --tests "fansirsqi.xposed.sesame.task.antForest.PkCampStoreTest" --console=plain`
Expected: BUILD SUCCESSFUL，8 个测试全绿

- [ ] **Step 5: 提交**

```bash
git add app/src/main/java/fansirsqi/xposed/sesame/task/antForest/PkCampStore.kt \
        app/src/test/java/fansirsqi/xposed/sesame/task/antForest/PkCampStoreTest.kt
git commit -m "feat(forest): 新增 PK 阵营快照存储与只增不毁合并规则"
```

---

### Task 4: 支付宝进程侧抓取与手动任务接线

**Files:**
- Modify: `app/src/main/java/fansirsqi/xposed/sesame/task/antForest/AntForest.kt`（在文件末尾 `manualForestVitalityRewardTask()` 之后，`AntForest.kt:5591-5593` 附近新增方法）
- Modify: `app/src/main/java/fansirsqi/xposed/sesame/task/customTasks/CustomTask.kt`
- Modify: `app/src/main/java/fansirsqi/xposed/sesame/task/customTasks/ManualTask.kt`（`when (task)` 块内，`SPORTS_SYNC_STEP` 分支之后，`ManualTask.kt:137-144` 附近）
- Modify: `app/src/main/java/fansirsqi/xposed/sesame/hook/ApplicationHook.kt`（`when (task)` 块内，`OCEAN_AI_FISH` 分支之后，`ApplicationHook.kt:485-487` 附近）

**Interfaces:**
- Consumes: `PkCampMerger.merge/missingUserIds/BATCH_SIZE`（Task 2）、`PkCampStore.serialize/deserialize/reconcile`（Task 3）、`AntForestRpcCall.queryTopEnergyChallengeRanking()`、`AntForestRpcCall.fillUserRobFlag(JSONArray, Boolean)`
- Produces: `AntForest.manualFetchPkCampFriends()`（无参、非 suspend）、`CustomTask.FOREST_PK_CAMP`

**注意**：`ManualTask.run` 里的 `when (task)` 对 `CustomTask` 是穷尽匹配且没有 `else`。加了新枚举值之后**编译会先报错**，必须补上分支才能通过 —— 这是预期行为，不是出错。

- [ ] **Step 1: 新增枚举值**

在 `CustomTask.kt` 的枚举末尾追加：

```kotlin
    SPORTS_SYNC_STEP("同步运动步数"),
    FOREST_PK_CAMP("拉取PK阵营好友")
```

**预期副作用**：`ManualTaskScreen.kt:37` 直接遍历 `CustomTask.entries`，所以「手动任务」页面会自动多出一张「拉取PK阵营好友」卡片。这是白送的第二个触发入口，不是 bug，无需处理。

- [ ] **Step 2: 确认编译失败（穷尽 when 缺分支）**

Run: `./gradlew :app:compileDebugKotlin --console=plain`
Expected: FAIL，`ManualTask.kt` 报 `'when' expression must be exhaustive` 或 `must be exhaustive, add necessary 'FOREST_PK_CAMP' branch`

- [ ] **Step 3: 在 AntForest 里实现抓取外壳**

在 `AntForest.kt` 末尾、`manualForestVitalityRewardTask()` 之后加入：

```kotlin
    /**
     * 手动拉取 PK 阵营成员并落盘为 pkCamp.json，供模块 UI 展示。
     *
     * 本方法只做 I/O：发 RPC、注入好友集合与时间戳、落盘。
     * 所有解析与合并逻辑都在 PkCampMerger（纯函数，有单元测试覆盖）。
     */
    fun manualFetchPkCampFriends() {
        try {
            val userId = UserMap.currentUid
            if (userId.isNullOrEmpty()) {
                Log.record(TAG, "❌ PK阵营好友：当前用户未知，跳过")
                return
            }

            val rankingJson = AntForestRpcCall.queryTopEnergyChallengeRanking()
            if (rankingJson.isEmpty()) {
                Log.record(TAG, "❌ PK阵营好友：拉取排行榜失败，保留旧快照")
                return
            }

            // 补全 friendRanking 里缺失的成员资料，每批 BATCH_SIZE 个
            val fillJsons = ArrayList<String>()
            val missing = PkCampMerger.missingUserIds(rankingJson)
            missing.chunked(PkCampMerger.BATCH_SIZE).forEach { batch ->
                val arr = JSONArray()
                batch.forEach { arr.put(it) }
                val filled = AntForestRpcCall.fillUserRobFlag(arr, true)
                if (filled.isEmpty()) {
                    Log.record(TAG, "⚠️ PK阵营好友：补全 ${batch.size} 人失败，这批资料将留空")
                } else {
                    fillJsons.add(filled)
                }
            }

            val fresh = PkCampMerger.merge(
                rankingJson = rankingJson,
                fillJsons = fillJsons,
                friendIds = UserMap.getUserIdSet(),
                now = System.currentTimeMillis()
            )
            if (fresh == null) {
                Log.record(TAG, "❌ PK阵营好友：响应解析失败，保留旧快照")
                return
            }

            val file = Files.getTargetFileofUser(userId, "pkCamp.json")
            if (file == null) {
                Log.record(TAG, "❌ PK阵营好友：无法定位落盘文件")
                return
            }
            val existing = PkCampStore.deserialize(Files.readFromFile(file))
            val merged = PkCampStore.reconcile(fresh, existing)
            Files.write2File(PkCampStore.serialize(merged), file)

            val strangers = merged.members.count { !it.isFriend }
            Log.record(
                TAG,
                "✅ PK阵营好友：共 ${merged.members.size} 人，其中陌生人 $strangers 人" +
                    "（状态 ${merged.rankMemberStatus}）"
            )
        } catch (t: Throwable) {
            Log.printStackTrace(TAG, "manualFetchPkCampFriends 失败", t)
        }
    }
```

确认 `AntForest.kt` 顶部已 import `org.json.JSONArray`、`fansirsqi.xposed.sesame.util.Files`、`fansirsqi.xposed.sesame.util.maps.UserMap`；缺哪个补哪个。

- [ ] **Step 4: 在 ManualTask 里接线**

在 `ManualTask.kt` 的 `when (task)` 块内，`CustomTask.SPORTS_SYNC_STEP` 分支之后加入：

```kotlin
                            // PK 阵营好友
                            CustomTask.FOREST_PK_CAMP -> {
                                val instance = getForestInstance()
                                if (instance != null) {
                                    instance.manualFetchPkCampFriends()
                                } else {
                                    Log.record("ManualTask", "❌ 无法加载森林模块")
                                }
                            }
```

- [ ] **Step 5: 在 ApplicationHook 里加空分支**

在 `ApplicationHook.kt` 的 `when (task)` 块内，`CustomTask.OCEAN_AI_FISH` 分支之后、`else ->` 之前加入：

```kotlin
                                    CustomTask.FOREST_PK_CAMP -> {
                                        // 拉取PK阵营好友无需额外参数
                                    }
```

不加这一条的话，任务仍会执行，但会落进 `else` 打出「❌ 无效的任务指令」，排查问题时极具误导性 —— `FOREST_VITALITY_REWARD` 特意写空分支就是这个原因。

- [ ] **Step 6: 确认编译通过且既有测试未被破坏**

Run: `./gradlew :app:compileDebugKotlin :app:testDebugUnitTest --console=plain`
Expected: BUILD SUCCESSFUL

- [ ] **Step 7: 在设备上验证落盘**

按 `CLAUDE.md` 的完整 Receiver 刷新流程装包：

```bash
./gradlew assembleDebug
adb install -r -d app/build/outputs/apk/debug/Sesame-TK-arm64-v8a-0.9.9-debug.apk
adb shell am force-stop fansirsqi.xposed.sesame
adb shell am force-stop com.eg.android.AlipayGphone
adb shell su -c 'pkill -f com.eg.android.AlipayGphone'
adb shell monkey -p com.eg.android.AlipayGphone -c android.intent.category.LAUNCHER 1
```

等支付宝起来后触发并读文件：

```bash
adb shell am broadcast -a com.eg.android.AlipayGphone.sesame.manual_task --es task FOREST_PK_CAMP
adb shell su -c 'ls /sdcard/Android/media/com.eg.android.AlipayGphone/sesame-TK/config/'
adb shell su -c 'cat /sdcard/Android/media/com.eg.android.AlipayGphone/sesame-TK/config/<上一步得到的userId>/pkCamp.json'
```

判定标准：

- `members` 条数 = `totalData` 条数 − 1（自己被剔除）
- `rank` 从 1 起连续递增，**没有任何 `-1`**
- `selfUserId` 不出现在 `members` 里
- `isFriend` 同时存在 `true` 和 `false`
- 排名 21 以后的成员也有非空 `displayName`（证明 `fillUserRobFlag` 补全生效）

若 `No enum constant ... CustomTask.FOREST_PK_CAMP`，说明注入的是旧代码，重跑上面的完整刷新流程；仍失败则重启设备刷新 Zygisk/LSPosed 注入链。

- [ ] **Step 8: 提交**

```bash
git add app/src/main/java/fansirsqi/xposed/sesame/task/antForest/AntForest.kt \
        app/src/main/java/fansirsqi/xposed/sesame/task/customTasks/CustomTask.kt \
        app/src/main/java/fansirsqi/xposed/sesame/task/customTasks/ManualTask.kt \
        app/src/main/java/fansirsqi/xposed/sesame/hook/ApplicationHook.kt
git commit -m "feat(forest): 支付宝进程侧抓取 PK 阵营成员并落盘"
```

---

### Task 5: 引入 Coil 依赖

**Files:**
- Modify: `gradle/libs.versions.toml`
- Modify: `app/build.gradle.kts`（依赖块，`app/build.gradle.kts:205` 的 okhttp 附近）

**Interfaces:**
- Consumes: 无
- Produces: `coil3.compose.AsyncImage` 可用于 Task 7

Coil 3 必须显式引入网络 fetcher，`coil-network-okhttp` 会复用项目已有的 OkHttp。

- [ ] **Step 1: 加版本与 library 条目**

在 `gradle/libs.versions.toml` 的 `[versions]` 段加：

```toml
coil = "3.2.0"
```

（3.2.0 是查证过的 Maven Central 最新稳定版；`3.3.0` 不存在，别写成它。）

在 `[libraries]` 段加：

```toml
coil-compose = { module = "io.coil-kt.coil3:coil-compose", version.ref = "coil" }
coil-network-okhttp = { module = "io.coil-kt.coil3:coil-network-okhttp", version.ref = "coil" }
```

- [ ] **Step 2: 在 app/build.gradle.kts 引入**

在 `implementation(libs.okhttp)` 那一行附近加：

```kotlin
    implementation(libs.coil.compose)              // 图片加载（PK 阵营好友头像）
    implementation(libs.coil.network.okhttp)       // Coil 3 网络 fetcher，复用项目已有 OkHttp
```

- [ ] **Step 3: 确认依赖解析成功**

Run: `./gradlew :app:compileDebugKotlin --console=plain`
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: 提交**

```bash
git add gradle/libs.versions.toml app/build.gradle.kts
git commit -m "build: 引入 Coil 3 用于加载好友头像"
```

---

### Task 6: PkCampViewModel（读文件 / 发广播 / 轮询）

**Files:**
- Create: `app/src/main/java/fansirsqi/xposed/sesame/ui/viewmodel/PkCampViewModel.kt`

**Interfaces:**
- Consumes: `PkCampSnapshot`、`PkCampMember`（Task 1）、`PkCampStore.deserialize`（Task 3）、`CustomTask.FOREST_PK_CAMP`（Task 4）
- Produces:
  - `PkCampUiState`：`NoAccount` / `NeverFetched` / `Content(members, updatedAt, joined)`
  - `PkCampViewModel(application)`，暴露 `uiState: StateFlow<PkCampUiState>`、`isRefreshing: StateFlow<Boolean>`、`toastMessage: StateFlow<String?>`、`showStrangersOnly: StateFlow<Boolean>`
  - 方法：`load()`、`refresh(context: Context)`、`toggleFilter()`、`consumeToast()`
  - `PkCampViewModel.visibleMembers(all: List<PkCampMember>, strangersOnly: Boolean): List<PkCampMember>`（companion 里的纯函数，Task 7 用）

- [ ] **Step 1: 实现 ViewModel**

```kotlin
package fansirsqi.xposed.sesame.ui.viewmodel

import android.app.Application
import android.content.Context
import android.content.Intent
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import fansirsqi.xposed.sesame.entity.PkCampMember
import fansirsqi.xposed.sesame.entity.UserEntity
import fansirsqi.xposed.sesame.task.antForest.PkCampStore
import fansirsqi.xposed.sesame.task.customTasks.CustomTask
import fansirsqi.xposed.sesame.util.DataStore
import fansirsqi.xposed.sesame.util.Files
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

sealed interface PkCampUiState {
    /** 拿不到当前登录账号 */
    data object NoAccount : PkCampUiState

    /** 文件不存在、为空或解析失败。注意 Files.getTargetFileofUser 会创建空文件 */
    data object NeverFetched : PkCampUiState

    data class Content(
        val members: List<PkCampMember>,
        val updatedAt: Long,
        val joined: Boolean
    ) : PkCampUiState
}

class PkCampViewModel(application: Application) : AndroidViewModel(application) {

    private val _uiState = MutableStateFlow<PkCampUiState>(PkCampUiState.NeverFetched)
    val uiState = _uiState.asStateFlow()

    private val _isRefreshing = MutableStateFlow(false)
    val isRefreshing = _isRefreshing.asStateFlow()

    private val _toastMessage = MutableStateFlow<String?>(null)
    val toastMessage = _toastMessage.asStateFlow()

    /** 默认「只看陌生人」：这个页面存在的唯一理由就是找出还不是好友的人 */
    private val _showStrangersOnly = MutableStateFlow(true)
    val showStrangersOnly = _showStrangersOnly.asStateFlow()

    private val userId: String? =
        DataStore.get("activedUser", UserEntity::class.java)?.userId?.takeIf { it.isNotEmpty() }

    init {
        load()
    }

    private fun snapshotFile(): File? =
        userId?.let { Files.getTargetFileofUser(it, SNAPSHOT_FILE_NAME) }

    /** 从磁盘读一次快照并更新状态 */
    fun load() {
        if (userId == null) {
            _uiState.value = PkCampUiState.NoAccount
            return
        }
        viewModelScope.launch {
            _uiState.value = withContext(Dispatchers.IO) { readState() }
        }
    }

    private fun readState(): PkCampUiState {
        val file = snapshotFile() ?: return PkCampUiState.NoAccount
        val snapshot = PkCampStore.deserialize(Files.readFromFile(file))
            ?: return PkCampUiState.NeverFetched
        return PkCampUiState.Content(
            members = snapshot.members,
            updatedAt = snapshot.updatedAt,
            joined = snapshot.rankMemberStatus == "JOIN"
        )
    }

    /**
     * 发广播让支付宝进程抓取，然后轮询文件 mtime 等它写完。
     * 这个「发广播 → 轮询文件 → 超时兜底」的模式与 RpcDebugViewModel.kt:206-224 一致。
     */
    fun refresh(context: Context) {
        if (userId == null) {
            _uiState.value = PkCampUiState.NoAccount
            return
        }
        if (_isRefreshing.value) return

        viewModelScope.launch {
            _isRefreshing.value = true
            try {
                val file = snapshotFile()
                val baseline = file?.lastModified() ?: 0L

                context.sendBroadcast(
                    Intent(ACTION_MANUAL_TASK).putExtra("task", CustomTask.FOREST_PK_CAMP.name)
                )

                var waited = 0L
                var updated = false
                while (waited < TIMEOUT_MS) {
                    delay(POLL_INTERVAL_MS)
                    waited += POLL_INTERVAL_MS
                    if (file != null && file.lastModified() > baseline) {
                        // 再等一小段，确保写入完成而不是读到半个文件
                        delay(POLL_INTERVAL_MS)
                        updated = true
                        break
                    }
                }

                if (updated) {
                    _uiState.value = withContext(Dispatchers.IO) { readState() }
                } else {
                    _toastMessage.value = "未收到支付宝进程响应，请确认模块已激活、支付宝正在运行"
                }
            } finally {
                _isRefreshing.value = false
            }
        }
    }

    fun toggleFilter() {
        _showStrangersOnly.value = !_showStrangersOnly.value
    }

    fun consumeToast() {
        _toastMessage.value = null
    }

    companion object {
        private const val SNAPSHOT_FILE_NAME = "pkCamp.json"
        private const val ACTION_MANUAL_TASK = "com.eg.android.AlipayGphone.sesame.manual_task"
        private const val POLL_INTERVAL_MS = 500L

        /** RpcIntervalLimit 可能给 RPC 加延迟，所以超时给到 30s 而非几秒 */
        private const val TIMEOUT_MS = 30_000L

        fun visibleMembers(all: List<PkCampMember>, strangersOnly: Boolean): List<PkCampMember> =
            if (strangersOnly) all.filter { !it.isFriend } else all
    }
}
```

- [ ] **Step 2: 确认编译通过**

Run: `./gradlew :app:compileDebugKotlin --console=plain`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: 提交**

```bash
git add app/src/main/java/fansirsqi/xposed/sesame/ui/viewmodel/PkCampViewModel.kt
git commit -m "feat(ui): 新增 PK 阵营好友 ViewModel"
```

---

### Task 7: 列表 UI（成员行 + 页面）

**Files:**
- Create: `app/src/main/java/fansirsqi/xposed/sesame/ui/screen/components/PkCampMemberRow.kt`
- Create: `app/src/main/java/fansirsqi/xposed/sesame/ui/screen/PkCampScreen.kt`

**Interfaces:**
- Consumes: `PkCampMember`（Task 1）、`PkCampUiState`、`PkCampViewModel.visibleMembers`（Task 6）、`coil3.compose.AsyncImage`（Task 5）
- Produces:
  - `@Composable PkCampMemberRow(member: PkCampMember)`
  - `@Composable PkCampScreen(state: PkCampUiState, isRefreshing: Boolean, showStrangersOnly: Boolean, onBackClick: () -> Unit, onRefresh: () -> Unit, onToggleFilter: () -> Unit, snackbarHost: @Composable () -> Unit = {})`

`PkCampScreen` 只接受值和回调，不直接持有 ViewModel，与 `ManualTaskScreen` 的写法一致。

- [ ] **Step 1: 实现成员行**

```kotlin
package fansirsqi.xposed.sesame.ui.screen.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import fansirsqi.xposed.sesame.entity.PkCampMember

/**
 * 头像 URL 是 http:// 开头，而 targetSdk 36 默认禁明文 HTTP。
 * 不重写成 https 会导致所有头像静默加载失败。
 */
internal fun String.toHttpsUrl(): String =
    if (startsWith("http://")) "https://" + substring(7) else this

/** 昵称可能为空串，用 userId 后四位兜底，避免出现空白行 */
internal fun PkCampMember.displayLabel(): String =
    displayName.ifBlank { "用户 ****" + userId.takeLast(4) }

@Composable
fun PkCampMemberRow(member: PkCampMember) {
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            val label = member.displayLabel()
            if (member.headPortrait.isBlank()) {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primaryContainer),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = label.take(1),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
            } else {
                AsyncImage(
                    model = member.headPortrait.toHttpsUrl(),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                )
            }

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = "#${member.rank} · ${member.energySummation} 能量 · " +
                        "${member.treeAmount} 棵 · ${member.challengeRankLevelName}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Surface(
                shape = MaterialTheme.shapes.small,
                color = if (member.isFriend) MaterialTheme.colorScheme.surfaceVariant
                        else MaterialTheme.colorScheme.primaryContainer
            ) {
                Text(
                    text = if (member.isFriend) "已是好友" else "陌生人",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (member.isFriend) MaterialTheme.colorScheme.onSurfaceVariant
                            else MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                )
            }
        }
    }
}
```

- [ ] **Step 2: 实现页面**

```kotlin
package fansirsqi.xposed.sesame.ui.screen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import fansirsqi.xposed.sesame.entity.PkCampMember
import fansirsqi.xposed.sesame.ui.screen.components.PkCampMemberRow
import fansirsqi.xposed.sesame.ui.viewmodel.PkCampUiState
import fansirsqi.xposed.sesame.ui.viewmodel.PkCampViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private fun formatUpdatedAt(ts: Long): String =
    SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(ts))

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PkCampScreen(
    state: PkCampUiState,
    isRefreshing: Boolean,
    showStrangersOnly: Boolean,
    onBackClick: () -> Unit,
    onRefresh: () -> Unit,
    onToggleFilter: () -> Unit,
    snackbarHost: @Composable () -> Unit = {}
) {
    Scaffold(
        snackbarHost = snackbarHost,
        topBar = {
            TopAppBar(
                title = { Text("PK 阵营好友") },
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    if (isRefreshing) {
                        CircularProgressIndicator(modifier = Modifier.size(24.dp).padding(end = 12.dp))
                    } else {
                        IconButton(onClick = onRefresh) {
                            Icon(Icons.Rounded.Refresh, contentDescription = "刷新")
                        }
                    }
                }
            )
        }
    ) { innerPadding ->
        Column(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
            when (state) {
                PkCampUiState.NoAccount -> CenteredHint(
                    "未检测到已登录账号，请先在支付宝登录并让模块跑一次"
                )

                PkCampUiState.NeverFetched -> CenteredHint("还没有数据，点右上角刷新")

                is PkCampUiState.Content -> {
                    if (!state.joined && state.members.isEmpty()) {
                        CenteredHint("当前账号未加入 PK 赛")
                    } else {
                        ContentBody(
                            state = state,
                            showStrangersOnly = showStrangersOnly,
                            onToggleFilter = onToggleFilter
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun CenteredHint(text: String) {
    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.outline,
            textAlign = TextAlign.Center
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ContentBody(
    state: PkCampUiState.Content,
    showStrangersOnly: Boolean,
    onToggleFilter: () -> Unit
) {
    val strangerCount = state.members.count { !it.isFriend }
    val visible: List<PkCampMember> =
        PkCampViewModel.visibleMembers(state.members, showStrangersOnly)

    if (!state.joined) {
        Surface(
            color = MaterialTheme.colorScheme.errorContainer,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                text = "当前账号未加入 PK 赛，以下是最后一次抓到的名单",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier.padding(16.dp)
            )
        }
    }

    Text(
        text = "更新于 ${formatUpdatedAt(state.updatedAt)}",
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.outline,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
    )

    Row(
        modifier = Modifier.padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        FilterChip(
            selected = showStrangersOnly,
            onClick = { if (!showStrangersOnly) onToggleFilter() },
            label = { Text("只看陌生人 ($strangerCount)") }
        )
        FilterChip(
            selected = !showStrangersOnly,
            onClick = { if (showStrangersOnly) onToggleFilter() },
            label = { Text("全部 (${state.members.size})") }
        )
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        items(visible, key = { it.userId }) { member ->
            PkCampMemberRow(member = member)
        }
    }
}
```

- [ ] **Step 3: 确认编译通过**

Run: `./gradlew :app:compileDebugKotlin --console=plain`
Expected: BUILD SUCCESSFUL（若 `Modifier.size` 在 TopAppBar actions 里报未解析，补 import `androidx.compose.foundation.layout.size`）

- [ ] **Step 4: 提交**

```bash
git add app/src/main/java/fansirsqi/xposed/sesame/ui/screen/components/PkCampMemberRow.kt \
        app/src/main/java/fansirsqi/xposed/sesame/ui/screen/PkCampScreen.kt
git commit -m "feat(ui): 新增 PK 阵营好友列表界面"
```

---

### Task 8: Activity 壳、入口接线与端到端验证

**Files:**
- Create: `app/src/main/java/fansirsqi/xposed/sesame/ui/PkCampActivity.kt`
- Modify: `app/src/main/AndroidManifest.xml`（`.ui.ManualTaskActivity` 声明附近，`AndroidManifest.xml:80-82`）
- Modify: `app/src/main/java/fansirsqi/xposed/sesame/ui/screen/content/SettingsContent.kt`（「账号配置」区块，`items(userList)` 之后，`SettingsContent.kt:93-98`）

**Interfaces:**
- Consumes: `PkCampScreen`（Task 7）、`PkCampViewModel`（Task 6）
- Produces: `PkCampActivity`；「账号配置」区块下的新入口

- [ ] **Step 1: 实现 Activity 壳**

```kotlin
package fansirsqi.xposed.sesame.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import fansirsqi.xposed.sesame.ui.extension.WatermarkLayer
import fansirsqi.xposed.sesame.ui.screen.PkCampScreen
import fansirsqi.xposed.sesame.ui.theme.AppTheme
import fansirsqi.xposed.sesame.ui.theme.ThemeManager
import fansirsqi.xposed.sesame.ui.viewmodel.MainViewModel
import fansirsqi.xposed.sesame.ui.viewmodel.PkCampViewModel

/**
 * PK 阵营好友列表。
 *
 * 刻意不调用 Model.initAllModel() / Config.load() / UserMap.load()：
 * 本页面只读 pkCamp.json，isFriend 已在支付宝进程算好落盘，UI 侧不需要这些。
 */
class PkCampActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val isDynamicColor by ThemeManager.isDynamicColor.collectAsStateWithLifecycle()
            AppTheme(dynamicColor = isDynamicColor) {
                WatermarkLayer(uidList = MainViewModel.verifuids) {
                    val vm: PkCampViewModel = viewModel()
                    val state by vm.uiState.collectAsStateWithLifecycle()
                    val isRefreshing by vm.isRefreshing.collectAsStateWithLifecycle()
                    val strangersOnly by vm.showStrangersOnly.collectAsStateWithLifecycle()
                    val toast by vm.toastMessage.collectAsStateWithLifecycle()

                    val snackbarHostState = remember { SnackbarHostState() }
                    LaunchedEffect(toast) {
                        toast?.let {
                            snackbarHostState.showSnackbar(it)
                            vm.consumeToast()
                        }
                    }

                    PkCampScreen(
                        state = state,
                        isRefreshing = isRefreshing,
                        showStrangersOnly = strangersOnly,
                        onBackClick = { finish() },
                        onRefresh = { vm.refresh(this@PkCampActivity) },
                        onToggleFilter = { vm.toggleFilter() },
                        snackbarHost = { SnackbarHost(snackbarHostState) }
                    )
                }
            }
        }
    }
}
```

`PkCampScreen` 的 `snackbarHost` 参数在 Task 7 里已经定义好，这里直接传即可。

- [ ] **Step 2: 声明 Activity**

在 `AndroidManifest.xml` 里 `.ui.ManualTaskActivity` 的声明之后加：

```xml
        <activity
            android:name=".ui.PkCampActivity"
            android:exported="false" />
```

- [ ] **Step 3: 加入口**

在 `SettingsContent.kt` 的「账号配置」区块里，`items(userList) { ... }` 那个 `else` 分支之后、「扩展&外观」标题之前插入：

```kotlin
            item {
                SettingsItem(
                    title = "PK 阵营好友",
                    subtitle = "当前登录账号的同阵营成员",
                    icon = Icons.Rounded.Groups,
                    onClick = {
                        context.startActivity(Intent(context, PkCampActivity::class.java))
                    }
                )
            }
```

`Icons.Rounded.Groups`、`Intent`、`context`、`SettingsItem` 该文件都已具备；只需补 import `fansirsqi.xposed.sesame.ui.PkCampActivity`。

- [ ] **Step 4: 构建并确认全部测试通过**

Run: `./gradlew assembleDebug :app:testDebugUnitTest --console=plain`
Expected: BUILD SUCCESSFUL

- [ ] **Step 5: 端到端验证**

按 `CLAUDE.md` 的完整 Receiver 刷新流程装包后：

1. 打开模块 → 设置页 → 账号配置，确认多了「PK 阵营好友」入口
2. 先清掉快照，进页面应显示「还没有数据，点右上角刷新」：
   ```bash
   adb shell su -c 'rm /sdcard/Android/media/com.eg.android.AlipayGphone/sesame-TK/config/<userId>/pkCamp.json'
   ```
3. 点刷新，30s 内出现列表，顶部显示「更新于 …」
4. 默认落在「只看陌生人」，两个 chip 的计数与 `pkCamp.json` 内容对得上
5. 切到「全部」，能看到「已是好友」灰色 chip
6. 头像正常显示 —— 这一条验证 https 重写生效。把某人的 `headPortrait` 手动改成 `""` 后重进页面，应显示昵称首字圆形占位
7. 强杀支付宝后再点刷新，30s 后出现超时 Snackbar，且**旧列表仍在**：
   ```bash
   adb shell am force-stop com.eg.android.AlipayGphone
   ```

- [ ] **Step 6: 验证「只增不毁」不变式（端到端）**

Task 3 已有单元测试守住 `reconcile`，这一步验证它在真实链路上也成立。

把落盘文件里的 `rankMemberStatus` 手动改成 `"QUIT"`，重启支付宝，然后点刷新：

- `members` 长度**不减少**
- 页面顶部出现「当前账号未加入 PK 赛，以下是最后一次抓到的名单」横幅，名单仍完整可见

如果 `members` 被清空，说明 Task 4 的落盘外壳没有正确调用 `PkCampStore.reconcile`。这个 bug 会在赛季结束时静默吃掉用户唯一想保留的数据，必须修掉才算完成。

- [ ] **Step 7: 提交**

```bash
git add app/src/main/java/fansirsqi/xposed/sesame/ui/PkCampActivity.kt \
        app/src/main/AndroidManifest.xml \
        app/src/main/java/fansirsqi/xposed/sesame/ui/screen/content/SettingsContent.kt
git commit -m "feat(ui): 接入 PK 阵营好友页面与账号配置入口"
```

---

## 不在本计划范围内

- **一键添加为支付宝好友。** 加好友 RPC 尚未抓包，接口未知。落盘的 `userId` 与 `isFriend` 已为它铺好路，但本期 UI 上不出现任何「添加」按钮。
- **把广播接收器移出 `BuildConfig.DEBUG`。** 目前 `ApplicationHook.kt:719-725` 只在 debug 包注册接收器，因此**刷新功能只在 debug 包可用**。日常按 `CLAUDE.md` 走 `assembleDebug`，不受影响。正式包要支持刷新涉及暴露广播接口的安全权衡，是一个独立决定。
