package com.zcode.remote.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.zcode.remote.R

/**
 * 保活引导（M3）：HyperOS/MIUI 上保证审批通知可达的分步设置。
 * App 自身的保障是前台服务 + 高优先级通知；这一页解决厂商侧的拦截。
 */
@Composable
fun KeepAliveGuideScreen(onBack: () -> Unit) {
    Column(
        Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row {
            TextButton(onClick = onBack, contentPadding = PaddingValues(0.dp)) { Text(stringResource(R.string.keepalive_back)) }
        }
        Text(stringResource(R.string.keepalive_title), style = MaterialTheme.typography.titleLarge)
        Text(
            stringResource(R.string.keepalive_intro),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        val steps =
            listOf(
                Step(
                    stringResource(R.string.keepalive_step1_title),
                    stringResource(R.string.keepalive_step1_body),
                ),
                Step(
                    stringResource(R.string.keepalive_step2_title),
                    stringResource(R.string.keepalive_step2_body),
                ),
                Step(
                    stringResource(R.string.keepalive_step3_title),
                    stringResource(R.string.keepalive_step3_body),
                ),
                Step(stringResource(R.string.keepalive_step4_title), stringResource(R.string.keepalive_step4_body)),
                Step(stringResource(R.string.keepalive_step5_title), stringResource(R.string.keepalive_step5_body)),
                Step(stringResource(R.string.keepalive_step6_title), stringResource(R.string.keepalive_step6_body)),
            )
        steps.forEach { s ->
            Card {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(s.title, style = MaterialTheme.typography.titleSmall)
                    Text(
                        s.body,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        Text(
            stringResource(R.string.keepalive_note),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private data class Step(
    val title: String,
    val body: String,
)
