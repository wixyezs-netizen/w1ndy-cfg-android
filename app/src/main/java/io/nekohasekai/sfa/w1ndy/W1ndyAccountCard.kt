package io.nekohasekai.sfa.w1ndy

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private val Green = Color(0xFF34C759)
private val Muted = Color(0xFF8E8E93)

private fun open(context: Context, url: String) {
    runCatching {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}

private fun daysWord(n: Int): String {
    val m10 = n % 10
    val m100 = n % 100
    return when {
        m10 == 1 && m100 != 11 -> "день"
        m10 in 2..4 && m100 !in 12..14 -> "дня"
        else -> "дней"
    }
}

@Composable
fun W1ndyAccountCard() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var account by remember { mutableStateOf(W1ndy.load(context)) }
    var busy by remember { mutableStateOf(false) }
    var waitingCode by remember { mutableStateOf<String?>(null) }
    var loginUrl by remember { mutableStateOf<String?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    var messageOk by remember { mutableStateOf(false) }
    var showLinkDialog by remember { mutableStateOf(false) }

    fun say(text: String?, ok: Boolean = false) {
        message = text
        messageOk = ok
    }

    // Обновляем данные подписки при открытии экрана
    LaunchedEffect(Unit) {
        if (account.loggedIn) {
            runCatching { W1ndy.refresh(context) }
                .onSuccess { account = it }
                .onFailure { if (it is W1ndy.ApiException && it.code == 401) account = W1ndy.load(context) }
        }
    }

    // Ждём подтверждения входа в Telegram
    LaunchedEffect(waitingCode) {
        val code = waitingCode ?: return@LaunchedEffect
        val deadline = System.currentTimeMillis() + 10 * 60 * 1000L
        while (System.currentTimeMillis() < deadline) {
            delay(2000)
            val done = runCatching { W1ndy.pollLogin(context, code) }.getOrDefault(false)
            if (done) {
                busy = true
                say("Вход выполнен. Подключаем ключ…", true)
                runCatching { W1ndy.refresh(context, sync = true) }
                    .onSuccess {
                        account = it
                        say(
                            if (it.hasLink) "Готово! Ключ подключён — нажмите кнопку запуска внизу." else "Вход выполнен. Активной подписки пока нет.",
                            true,
                        )
                    }
                    .onFailure {
                        account = W1ndy.load(context)
                        say(it.message ?: "Не удалось загрузить ключ")
                    }
                busy = false
                waitingCode = null
                return@LaunchedEffect
            }
        }
        waitingCode = null
        say("Время ожидания истекло. Попробуйте войти ещё раз.")
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(),
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            when {
                waitingCode != null -> {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.size(12.dp))
                        Text("Ждём подтверждения в Telegram", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    }
                    Text(
                        "Нажмите «Подтвердить вход» в боте и вернитесь сюда — ключ подключится сам.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = Muted,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { loginUrl?.let { open(context, it) } }) { Text("Открыть Telegram") }
                        TextButton(onClick = { waitingCode = null; say(null) }) { Text("Отмена") }
                    }
                }

                account.loggedIn -> {
                    Text(
                        if (account.name.isNotEmpty()) "Привет, ${account.name}" else "Аккаунт",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                    )
                    if (account.active) {
                        Text(
                            "Подписка активна до ${account.expireDate} · осталось ${account.daysLeft} ${daysWord(account.daysLeft)}",
                            style = MaterialTheme.typography.bodyMedium,
                            color = Green,
                        )
                    } else {
                        Text(
                            if (account.status == "EXPIRED") "Срок подписки закончился" else "Активной подписки нет",
                            style = MaterialTheme.typography.bodyMedium,
                            color = Muted,
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                        Button(onClick = { open(context, W1ndy.BOT_URL) }, modifier = Modifier.weight(1f)) {
                            Text(if (account.active) "Продлить" else "Оформить")
                        }
                        OutlinedButton(
                            enabled = !busy,
                            modifier = Modifier.weight(1f),
                            onClick = {
                                busy = true
                                say(null)
                                scope.launch {
                                    runCatching { W1ndy.refresh(context, sync = true) }
                                        .onSuccess {
                                            account = it
                                            say(if (it.hasLink) "Ключ обновлён" else "Активной подписки пока нет", it.hasLink)
                                        }
                                        .onFailure {
                                            account = W1ndy.load(context)
                                            say(it.message ?: "Нет связи с сервером")
                                        }
                                    busy = false
                                }
                            },
                        ) { Text(if (busy) "Обновляем…" else "Обновить ключ") }
                    }
                    TextButton(onClick = {
                        W1ndy.logout(context)
                        account = W1ndy.load(context)
                        say("Вы вышли из аккаунта. Ключ остался в приложении.")
                    }) { Text("Выйти", color = Muted) }
                }

                else -> {
                    Text("Подключение в один тап", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Text(
                        if (account.hasLink) {
                            "Ключ подключён по ссылке. Войдите через Telegram, чтобы видеть срок подписки и продлевать её."
                        } else {
                            "Войдите через Telegram — ключ подключится автоматически. Или вставьте ссылку подписки из личного кабинета."
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = Muted,
                    )
                    Button(
                        enabled = !busy,
                        modifier = Modifier.fillMaxWidth().height(50.dp),
                        onClick = {
                            busy = true
                            say(null)
                            scope.launch {
                                runCatching { W1ndy.requestLogin() }
                                    .onSuccess { (code, url) ->
                                        loginUrl = url
                                        waitingCode = code
                                        open(context, url)
                                    }
                                    .onFailure { say(it.message ?: "Нет связи с сервером") }
                                busy = false
                            }
                        },
                    ) { Text("Войти через Telegram", fontWeight = FontWeight.SemiBold) }
                    OutlinedButton(
                        enabled = !busy,
                        modifier = Modifier.fillMaxWidth().height(50.dp),
                        onClick = { showLinkDialog = true },
                    ) { Text(if (account.hasLink) "Заменить ссылку" else "У меня есть ссылка") }
                }
            }

            message?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = if (messageOk) Green else MaterialTheme.colorScheme.error)
            }
        }
    }

    if (showLinkDialog) {
        var text by remember { mutableStateOf("") }
        var error by remember { mutableStateOf<String?>(null) }
        var saving by remember { mutableStateOf(false) }
        AlertDialog(
            onDismissRequest = { if (!saving) showLinkDialog = false },
            title = { Text("Ссылка подписки") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Скопируйте ссылку в личном кабинете или в боте и вставьте сюда.", color = Muted)
                    OutlinedTextField(
                        value = text,
                        onValueChange = { text = it; error = null },
                        singleLine = true,
                        placeholder = { Text("https://…") },
                        isError = error != null,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                }
            },
            confirmButton = {
                Button(enabled = !saving, onClick = {
                    val url = W1ndy.normalizeLink(text)
                    if (url == null) {
                        error = "Это не похоже на ссылку подписки"
                        return@Button
                    }
                    saving = true
                    scope.launch {
                        runCatching { W1ndy.syncProfile(context, url) }
                            .onSuccess {
                                account = W1ndy.load(context)
                                showLinkDialog = false
                                say("Ключ подключён — нажмите кнопку запуска внизу.", true)
                            }
                            .onFailure { error = "Не удалось загрузить ключ: ${it.message ?: "ошибка"}" }
                        saving = false
                    }
                }) { Text(if (saving) "Подключаем…" else "Подключить") }
            },
            dismissButton = {
                TextButton(enabled = !saving, onClick = { showLinkDialog = false }) { Text("Отмена") }
            },
        )
    }
}
