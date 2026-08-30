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
