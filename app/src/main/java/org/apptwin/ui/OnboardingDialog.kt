package org.apptwin.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Smartphone
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

@Composable
internal fun OnboardingDialog(onContinue: () -> Unit) {
    AlertDialog(
        modifier = Modifier.testTag("onboarding-dialog"),
        onDismissRequest = {},
        title = { Text("用分身空間區分每一種身分") },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(18.dp),
            ) {
                OnboardingPoint(
                    icon = { Icon(Icons.Default.Security, contentDescription = null) },
                    title = "空間彼此隔離",
                    description = "每個空間擁有自己的登入狀態與 App 資料。",
                )
                OnboardingPoint(
                    icon = { Icon(Icons.Default.Smartphone, contentDescription = null) },
                    title = "原始 App 不受影響",
                    description = "建立、使用或移除分身，不會清除手機上原始 App 的資料。",
                )
                OnboardingPoint(
                    icon = { Icon(Icons.Default.Apps, contentDescription = null) },
                    title = "相容性可能不同",
                    description = "部分 App 的登入、通知或系統功能可能受限；加入前會顯示目前狀態。",
                )
            }
        },
        confirmButton = {
            TextButton(
                modifier = Modifier.testTag("complete-onboarding"),
                onClick = onContinue,
            ) {
                Text("開始建立空間")
            }
        },
    )
}

@Composable
private fun OnboardingPoint(
    icon: @Composable () -> Unit,
    title: String,
    description: String,
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalAlignment = Alignment.Top,
    ) {
        icon()
        Column {
            Text(title, fontWeight = FontWeight.Bold)
            Text(
                description,
                modifier = Modifier.padding(top = 3.dp),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
