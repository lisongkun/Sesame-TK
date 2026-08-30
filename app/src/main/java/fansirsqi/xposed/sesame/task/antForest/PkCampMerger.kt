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
