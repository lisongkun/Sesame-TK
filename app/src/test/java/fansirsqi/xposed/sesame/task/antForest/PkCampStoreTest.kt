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

    @Test
    fun `reconcile accepts a smaller non empty fresh list because seasons turn over`() {
        // 收缩到更小的非空列表是刻意为之，不是缺陷：PK 赛季会轮换，新赛季的名单
        // 可以合法地比旧快照更少（如 30 → 10）。若按 fresh.size >= existing.size
        // 拦截，新赛季名单将永远无法替换旧名单，页面会一直显示早已退场的陌生人。
        // 规格的「只增不毁」精确含义是「绝不覆盖成空」——非空的新数据就是真相，必须取胜。
        val smallerFresh = joined.copy(members = listOf(member("u-1", 1)), updatedAt = 400L)
        val result = PkCampStore.reconcile(smallerFresh, joined)
        assertEquals(listOf("u-1"), result.members.map { it.userId })
    }
}
