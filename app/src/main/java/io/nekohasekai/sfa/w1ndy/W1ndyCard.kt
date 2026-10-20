package io.nekohasekai.sfa.w1ndy

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

private val Green = Color(0xFF34C759)
private val Red = Color(0xFFFF453A)
private val Muted = Color(0xFF8E8E93)

@Composable
fun W1ndyCard() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var state by remember { mutableStateOf(W1ndy.load(context)) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var messageOk by remember { mutableStateOf(false) }
    var code by remember { mutableStateOf("") }
    var customDns by remember {
        mutableStateOf(if (W1ndy.DNS_PRESETS.any { it.address == state.dnsServer }) "" else state.dnsServer)
    }
    var dnsChoice by remember {
        mutableStateOf(if (W1ndy.DNS_PRESETS.any { it.address == state.dnsServer }) state.dnsServer else "custom")
    }
    var confirmReset by remember { mutableStateOf(false) }

    fun say(text: String?, ok: Boolean = false) {
        message = text
        messageOk = ok
    }

    fun run(okText: String?, block: suspend () -> W1ndy.State) {
        if (busy) return
        busy = true
        say(null)
        scope.launch {
            try {
                state = block()
                if (okText != null) say(okText, ok = true)
            } catch (e: Exception) {
                state = W1ndy.load(context)
                say(e.message ?: "Ошибка")
            } finally {
                busy = false
            }
        }
    }

    LaunchedEffect(Unit) {
        if (state.activated) {
            try {
                state = W1ndy.refresh(context)
            } catch (e: W1ndy.ApiException) {
                state = W1ndy.load(context)
                if (e.code == 403) say(e.message)
            } catch (_: Exception) {
                // нет сети — покажем сохранённое состояние
            }
        }
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("W1NDY CFG", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                val vpn = state.mode == W1ndy.MODE_VPN
                ModeButton("VPN", vpn, Modifier.weight(1f)) {
                    if (!vpn) run(null) { W1ndy.setMode(context, W1ndy.MODE_VPN) }
                }
                ModeButton("Только DNS", !vpn, Modifier.weight(1f)) {
                    if (vpn) run("DNS-режим выбран. Нажмите кнопку запуска.") { W1ndy.setMode(context, W1ndy.MODE_DNS) }
                }
            }

            if (state.mode == W1ndy.MODE_VPN) {
                if (!state.activated) {
                    Text(
                        "Введите код доступа, который вы получили от администратора. Подключение настроится автоматически.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = Muted,
                    )
                    OutlinedTextField(
                        value = code,
                        onValueChange = { code = it.uppercase().take(12) },
                        label = { Text("Код доступа") },
                        placeholder = { Text("XXXX-XXXX") },
                        singleLine = true,
                        enabled = !busy,
                        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Button(
                        onClick = { run("Готово! Нажмите кнопку запуска, чтобы подключиться.") { W1ndy.activate(context, code) } },
                        enabled = !busy && code.count { it.isLetterOrDigit() } >= 8,
                        modifier = Modifier.fillMaxWidth().height(52.dp),
                        shape = RoundedCornerShape(16.dp),
                    ) {
                        if (busy) {
                            CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                        } else {
                            Text("Активировать", fontWeight = FontWeight.SemiBold)
                        }
                    }
                } else {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("●", color = Green)
                        Spacer(Modifier.size(8.dp))
                        Text(
                            "Подключение: " + state.confName.ifEmpty { "WireGuard" },
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.Medium,
                        )
                    }
                    Text(
                        "Конфигурация выбрана. Нажмите кнопку запуска, чтобы включить VPN.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = Muted,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                        OutlinedButton(
                            onClick = { run("Конфигурация обновлена") { W1ndy.updateVpn(context) } },
                            enabled = !busy,
                            modifier = Modifier.weight(1f),
                        ) { Text("Обновить") }
                        TextButton(onClick = { confirmReset = true }, enabled = !busy, modifier = Modifier.weight(1f)) {
                            Text("Другой код")
                        }
                    }
                }
            } else {
                Text(
                    "DNS-запросы телефона пойдут через выбранный сервер, остальной трафик — напрямую.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Muted,
                )
                Column {
                    W1ndy.DNS_PRESETS.forEach { p ->
                        DnsRow("${p.title} · ${p.address}", dnsChoice == p.address) { dnsChoice = p.address }
                    }
                    DnsRow("Свой адрес", dnsChoice == "custom") { dnsChoice = "custom" }
                }
                if (dnsChoice == "custom") {
                    OutlinedTextField(
                        value = customDns,
                        onValueChange = { customDns = it.trim().take(45) },
                        label = { Text("IP-адрес DNS") },
                        placeholder = { Text("например 9.9.9.9") },
                        singleLine = true,
                        enabled = !busy,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                val target = if (dnsChoice == "custom") customDns else dnsChoice
                Button(
                    onClick = { run("DNS $target применён. Нажмите кнопку запуска.") { W1ndy.applyDns(context, target) } },
                    enabled = !busy && target.isNotBlank(),
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                    shape = RoundedCornerShape(16.dp),
                ) {
                    if (busy) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                    } else {
                        Text("Применить", fontWeight = FontWeight.SemiBold)
                    }
                }
                Text("Сейчас: ${state.dnsServer}", style = MaterialTheme.typography.bodySmall, color = Muted)
            }

            message?.let {
                Text(it, style = MaterialTheme.typography.bodyMedium, color = if (messageOk) Green else Red)
            }
        }
    }

    if (confirmReset) {
        AlertDialog(
            onDismissRequest = { confirmReset = false },
            title = { Text("Ввести другой код?") },
            text = { Text("Текущее подключение будет удалено с этого телефона.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmReset = false
                    run(null) {
                        W1ndy.reset(context)
                        W1ndy.load(context)
                    }
                }) { Text("Удалить") }
            },
            dismissButton = { TextButton(onClick = { confirmReset = false }) { Text("Отмена") } },
        )
    }
}

@Composable
private fun ModeButton(text: String, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    if (selected) {
        Button(onClick = onClick, modifier = modifier.height(44.dp), shape = RoundedCornerShape(14.dp)) {
            Text(text, fontWeight = FontWeight.SemiBold)
        }
    } else {
        OutlinedButton(onClick = onClick, modifier = modifier.height(44.dp), shape = RoundedCornerShape(14.dp)) {
            Text(text)
        }
    }
}

@Composable
private fun DnsRow(text: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 2.dp),
    ) {
        RadioButton(selected = selected, onClick = onClick)
        Text(text, style = MaterialTheme.typography.bodyLarge)
    }
}
