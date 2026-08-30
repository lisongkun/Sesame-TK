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
