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
