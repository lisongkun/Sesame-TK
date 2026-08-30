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
            {"userId":"u-3","rank":3,"energySummation":13938},
            {"userId":"s-self","rank":2,"energySummation":31817},
            {"userId":"u-1","rank":1,"energySummation":41280}
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
