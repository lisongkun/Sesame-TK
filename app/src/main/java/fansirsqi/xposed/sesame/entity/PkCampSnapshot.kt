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
