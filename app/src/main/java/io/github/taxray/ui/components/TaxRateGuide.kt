package io.github.taxray.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp

/** Explanatory examples only. Opening a category never changes the receipt's selected rate. */
@Composable
fun TaxRateGuide(modifier: Modifier = Modifier) {
    var selectedRate by rememberSaveable { mutableStateOf<String?>(null) }
    Column(modifier.fillMaxWidth()) {
        Text("税率参考", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(4.dp))
        Text("点击类别查看典型代表", style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(8.dp))
        RateGuideEntries.forEach { entry ->
            Row(
                Modifier.fillMaxWidth().heightIn(min = 56.dp)
                    .testTag("taxGuide${entry.rate}")
                    .clickable(role = Role.Button, onClickLabel = "查看典型代表与适用条件") { selectedRate = entry.rate }
                    .padding(vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("${entry.rate}%", Modifier.width(52.dp), style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary)
                Text(entry.title, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                Icon(Icons.Outlined.ChevronRight, null, Modifier.padding(start = 8.dp).size(20.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        }
        Text("0% 数值拆分不等于免税认定\n按票据核对 自定义支持 0–100%",
            Modifier.padding(top = 12.dp), style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }

    RateGuideEntries.find { it.rate == selectedRate }?.let { entry ->
        AlertDialog(
            onDismissRequest = { selectedRate = null },
            title = { Text("${entry.rate}% ${entry.title}") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()).testTag("taxGuideDetails"),
                    verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    GuideDetail("适用范围", entry.scope)
                    GuideDetail("典型代表", entry.examples)
                    GuideDetail("适用条件", entry.conditions)
                    Text("类别用于理解常见一般计税范围\n实际以票据和交易条件为准",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            },
            confirmButton = { TextButton(onClick = { selectedRate = null }) { Text("知道了") } }
        )
    }
}

@Composable
private fun GuideDetail(title: String, text: String) {
    Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Text(title, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurface)
        Text(text, style = MaterialTheme.typography.bodyMedium)
    }
}

private data class RateGuideEntry(
    val rate: String,
    val title: String,
    val scope: String,
    val examples: String,
    val conditions: String,
)

// Source mapping and verification date are maintained in docs/TAX_RATE_GUIDE.md.
private val RateGuideEntries = listOf(
    RateGuideEntry(
        rate = "13",
        title = "大多数工业制成品",
        scope = "一般货物 加工修理修配\n有形动产租赁",
        examples = "手机 电脑 机械设备\n服装 化妆品 汽车 家电",
        conditions = "适用于一般计税且没有其他税率或优惠的交易\n不能只凭商品名称确定商户实际适用税率",
    ),
    RateGuideEntry(
        rate = "9",
        title = "民生货物与部分服务",
        scope = "部分农产品和民生货物\n交通运输 邮政 基础电信 建筑\n不动产租赁 销售不动产 土地使用权转让",
        examples = "粮食 食用植物油 自来水 暖气 天然气\n图书 报纸 饲料 化肥\n初级肉蛋菜属于农产品范围 另需核对免税条件",
        conditions = "农业生产者销售自产农产品可依法免税\n蔬菜和部分鲜活肉蛋的流通环节另有条件性免税\n不能把所有肉蛋菜都按9%处理\n熟食和罐头不能直接套用初级农产品范围",
    ),
    RateGuideEntry(
        rate = "6",
        title = "现代服务等",
        scope = "未适用其他税率的服务和无形资产\n包括现代服务及部分生活服务和金融服务",
        examples = "研发与技术服务 信息技术服务\n咨询服务 设计服务",
        conditions = "适用于一般计税且没有其他税率或优惠的交易\n交通运输一般为9% 有形动产租赁一般为13%\n不能把所有服务都按6%处理",
    ),
    RateGuideEntry(
        rate = "0",
        title = "出口与特定跨境业务",
        scope = "法定范围内的出口货物\n符合规定的跨境服务与无形资产",
        examples = "实际报关离境并销售给境外客户的货物\n符合条件的跨境研发或软件服务",
        conditions = "出口仍有例外 跨境还须满足法定范围和条件\n仅向境外客户销售不一定适用0%\n零税率与免税不同\n本应用0%只作零税额拆分 不认定退免税资格",
    ),
)
