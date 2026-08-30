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
