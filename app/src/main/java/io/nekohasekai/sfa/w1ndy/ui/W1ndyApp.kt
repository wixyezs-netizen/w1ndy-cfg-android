package io.nekohasekai.sfa.w1ndy.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.Campaign
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Dns
import androidx.compose.material.icons.rounded.PowerSettingsNew
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.SystemUpdate
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.nekohasekai.sfa.BuildConfig
import io.nekohasekai.sfa.R
import io.nekohasekai.sfa.constant.Status
import io.nekohasekai.sfa.update.UpdateState
import io.nekohasekai.sfa.w1ndy.W1ndy
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

// Палитра Mini App LitEnergy
private val Bg = Color(0xFF000000)
private val Surface = Color(0xFF161616)
private val Raised = Color(0xFF1C1C1E)
private val Pill = Color(0xFF222222)
private val Border = Color(0x14FFFFFF)
private val BorderHover = Color(0x2EFFFFFF)
private val TextMain = Color(0xFFFFFFFF)
private val Muted = Color(0xFF8E8E93)
private val Dim = Color(0xFF636366)
private val Green = Color(0xFF34C759)
private val Red = Color(0xFFFF453A)

private fun open(context: Context, url: String) {
    runCatching {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}

private enum class Step { Mode, Key, Dns, Telegram }

/** Корневой экран W1NDY CFG: вход (выбор режима → ключ/DNS → «Старт» в боте) или профиль с кнопкой подключения. */
@Composable
fun W1ndyApp(status: Status, onConnect: () -> Unit, onDisconnect: () -> Unit) {
    val context = LocalContext.current
    var state by remember { mutableStateOf(W1ndy.load(context)) }
    var startStep by remember { mutableStateOf(Step.Mode) }
    Box(Modifier.fillMaxSize().background(Bg)) {
        if (!state.onboarded) {
            Onboarding(initial = startStep, onDone = {
                state = W1ndy.load(context)
            })
        } else {
            Home(
                state = state,
                status = status,
                onConnect = onConnect,
                onDisconnect = onDisconnect,
                onStateChange = { state = it },
                onRestart = { step ->
                    if (status == Status.Started || status == Status.Starting) onDisconnect()
                    startStep = step
                    W1ndy.setOnboarded(context, false)
                    state = W1ndy.load(context)
                },
            )
        }
    }
}

// ---------------------------------------------------------------- вход

@Composable
private fun Onboarding(initial: Step, onDone: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var step by remember { mutableStateOf(initial) }
    var mode by remember { mutableStateOf(if (initial == Step.Dns) W1ndy.MODE_DNS else W1ndy.MODE_VPN) }
    var key by remember { mutableStateOf("") }
    val saved = remember { W1ndy.load(context) }
    var dnsChoice by remember { mutableStateOf(if (W1ndy.DNS_PRESETS.any { it.address == saved.dnsServer }) saved.dnsServer else "custom") }
    var customDns by remember { mutableStateOf(if (W1ndy.DNS_PRESETS.any { it.address == saved.dnsServer }) "" else saved.dnsServer) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var botOpened by remember { mutableStateOf(false) }

    val dnsTarget = if (dnsChoice == "custom") customDns else dnsChoice

    // Завершение: включаем выбранный режим и открываем профиль
    suspend fun finish(): Boolean {
        return try {
            if (mode == W1ndy.MODE_VPN) {
                if (!W1ndy.load(context).activated) W1ndy.activate(context, key) else W1ndy.setMode(context, W1ndy.MODE_VPN)
            } else {
                W1ndy.applyDns(context, dnsTarget)
            }
            W1ndy.setOnboarded(context, true)
            onDone()
            true
        } catch (e: W1ndy.ApiException) {
            if (e.code == 428) {
                step = Step.Telegram
            } else {
                error = e.message
                if (e.code == 404 && step == Step.Telegram) step = Step.Key
            }
            false
        } catch (e: Exception) {
            error = e.message ?: "Ошибка подключения"
            false
        }
    }

    fun next() {
        if (busy) return
        error = null
        busy = true
        scope.launch {
            try {
                if (mode == W1ndy.MODE_DNS && W1ndy.normalizeDns(dnsTarget) == null) {
                    error = "Введите IP-адрес DNS, например 8.8.8.8"
                    return@launch
                }
                val linked = W1ndy.load(context).tgLinked || runCatching { W1ndy.telegramLinked(context) }.getOrDefault(false)
                if (!linked) {
                    if (mode == W1ndy.MODE_VPN && !W1ndy.load(context).activated) {
                        finish() // проверит ключ на сервере; верный ключ без «Старт» → шаг Telegram
                    } else {
                        step = Step.Telegram
                    }
                } else {
                    finish()
                }
            } finally {
                busy = false
            }
        }
    }

    // Ожидание нажатия «Старт» в боте
    LaunchedEffect(step, botOpened) {
        if (step != Step.Telegram) return@LaunchedEffect
        while (true) {
            val linked = runCatching { W1ndy.telegramLinked(context) }.getOrDefault(false)
            if (linked) {
                busy = true
                finish()
                busy = false
                break
            }
            delay(2000)
        }
    }

    BackHandler(enabled = step != Step.Mode) {
        error = null
        step = when (step) {
            Step.Telegram -> if (mode == W1ndy.MODE_VPN) Step.Key else Step.Dns
            else -> Step.Mode
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 16.dp),
    ) {
        if (step != Step.Mode) {
            IconButton(onClick = {
                error = null
                step = if (step == Step.Telegram) (if (mode == W1ndy.MODE_VPN) Step.Key else Step.Dns) else Step.Mode
            }) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Назад", tint = TextMain) }
        } else {
            Spacer(Modifier.height(24.dp))
        }

        when (step) {
            Step.Mode -> {
                Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                    Image(
                        painter = painterResource(R.drawable.w1ndy_brand),
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.size(150.dp).clip(RoundedCornerShape(34.dp)).border(1.dp, Border, RoundedCornerShape(34.dp)),
                    )
                    Spacer(Modifier.height(24.dp))
                    Text("W1NDY CFG", color = TextMain, fontSize = 28.sp, fontWeight = FontWeight.ExtraBold)
                    Spacer(Modifier.height(8.dp))
                    Text("Выберите, как подключиться", color = Muted, fontSize = 15.sp)
                }
                Spacer(Modifier.height(32.dp))
                ChoiceCard(Icons.Rounded.Shield, "VPN", "Защищённое подключение по ключу доступа") {
                    mode = W1ndy.MODE_VPN
                    error = null
                    if (W1ndy.load(context).activated) next() else step = Step.Key
                }
                Spacer(Modifier.height(12.dp))
                ChoiceCard(Icons.Rounded.Dns, "Только DNS", "Свой DNS-сервер, например 8.8.8.8. Остальной трафик — напрямую") {
                    mode = W1ndy.MODE_DNS
                    error = null
                    step = Step.Dns
                }
                if (busy) {
                    Spacer(Modifier.height(20.dp))
                    CircularProgressIndicator(color = TextMain, modifier = Modifier.align(Alignment.CenterHorizontally).size(28.dp), strokeWidth = 2.dp)
                }
            }

            Step.Key -> {
                StepHeader("Ключ доступа", "Вставьте ключ, который вы получили от администратора. Он выглядит так: ABCD-EFGH")
                Spacer(Modifier.height(24.dp))
                val clipboard = LocalClipboardManager.current
                OutlinedTextField(
                    value = key,
                    onValueChange = { key = it.uppercase().take(16) },
                    placeholder = { Text("XXXX-XXXX", color = Dim) },
                    singleLine = true,
                    enabled = !busy,
                    textStyle = androidx.compose.ui.text.TextStyle(fontSize = 22.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 2.sp, color = TextMain),
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters),
                    shape = RoundedCornerShape(16.dp),
                    colors = fieldColors(),
                    trailingIcon = {
                        TextButton(onClick = { clipboard.getText()?.text?.let { key = it.trim().uppercase().take(16) } }) {
                            Text("Вставить", color = TextMain)
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
                ErrorText(error)
                Spacer(Modifier.height(20.dp))
                PrimaryButton("Продолжить", busy, enabled = key.count { it.isLetterOrDigit() } >= 8) { next() }
            }

            Step.Dns -> {
                StepHeader("DNS-сервер", "DNS-запросы телефона пойдут через выбранный сервер, остальной трафик — напрямую.")
                Spacer(Modifier.height(20.dp))
                Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(Surface).border(1.dp, Border, RoundedCornerShape(20.dp)).padding(vertical = 6.dp)) {
                    W1ndy.DNS_PRESETS.forEach { p ->
                        DnsRow(p.title, p.address, dnsChoice == p.address) { dnsChoice = p.address }
                    }
                    DnsRow("Свой адрес", "", dnsChoice == "custom") { dnsChoice = "custom" }
                }
                if (dnsChoice == "custom") {
                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(
                        value = customDns,
                        onValueChange = { customDns = it.trim().take(45) },
                        placeholder = { Text("например 9.9.9.9", color = Dim) },
                        singleLine = true,
                        enabled = !busy,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                        shape = RoundedCornerShape(16.dp),
                        colors = fieldColors(),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                ErrorText(error)
                Spacer(Modifier.height(20.dp))
                PrimaryButton("Продолжить", busy, enabled = dnsTarget.isNotBlank()) { next() }
            }

            Step.Telegram -> {
                Spacer(Modifier.height(16.dp))
                Box(
                    Modifier.size(76.dp).clip(RoundedCornerShape(22.dp)).background(Surface).border(1.dp, Border, RoundedCornerShape(22.dp)).align(Alignment.CenterHorizontally),
                    contentAlignment = Alignment.Center,
                ) { Icon(Icons.AutoMirrored.Rounded.Send, contentDescription = null, tint = TextMain, modifier = Modifier.size(34.dp)) }
                Spacer(Modifier.height(20.dp))
                Text("Последний шаг", color = TextMain, fontSize = 24.sp, fontWeight = FontWeight.ExtraBold, modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
                Spacer(Modifier.height(10.dp))
                Text(
                    "Откройте бота LitEnergy VPN и нажмите «Старт». После этого приложение запустится автоматически.",
                    color = Muted, fontSize = 15.sp, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(28.dp))
                PrimaryButton("Открыть бота", busy && !botOpened) {
                    error = null
                    scope.launch {
                        try {
                            open(context, W1ndy.telegramLink(context))
                            botOpened = true
                        } catch (e: Exception) {
                            error = e.message ?: "Нет соединения с сервером"
                        }
                    }
                }
                ErrorText(error)
                if (botOpened) {
                    Spacer(Modifier.height(20.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(color = Muted, modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(10.dp))
                        Text("Ждём нажатия «Старт»…", color = Muted, fontSize = 14.sp)
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------- профиль

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun Home(
    state: W1ndy.State,
    status: Status,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
    onStateChange: (W1ndy.State) -> Unit,
    onRestart: (Step) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var showSettings by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val hasUpdate by UpdateState.hasUpdate
    val updateInfo by UpdateState.updateInfo

    // Проверка доступа при открытии (администратор мог отключить устройство)
    LaunchedEffect(Unit) {
        if (state.mode == W1ndy.MODE_VPN && state.activated) {
            try {
                onStateChange(W1ndy.refresh(context))
            } catch (e: W1ndy.ApiException) {
                if (e.code == 403) onRestart(Step.Key) // устройство отключено — снова ввод ключа
            } catch (_: Exception) {
            }
        }
    }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val minH = maxHeight
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            Column(
                Modifier.fillMaxWidth().heightIn(min = minH).statusBarsPadding().navigationBarsPadding().padding(horizontal = 20.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.SpaceBetween,
            ) {
                Column {
                    // верхняя панель
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Image(
                            painter = painterResource(R.drawable.w1ndy_brand),
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.size(38.dp).clip(RoundedCornerShape(11.dp)),
                        )
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text("W1NDY CFG", color = TextMain, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                            if (state.tgName.isNotEmpty()) Text("Привет, ${state.tgName}", color = Muted, fontSize = 13.sp)
                        }
                        IconButton(onClick = { showSettings = true }) {
                            Icon(Icons.Outlined.Settings, contentDescription = "Настройки", tint = Muted)
                        }
                    }

                    if (hasUpdate && updateInfo != null) {
                        Spacer(Modifier.height(14.dp))
                        LinkCard(Icons.Rounded.SystemUpdate, "Доступна версия ${updateInfo?.versionName}", "Нажмите, чтобы скачать обновление") {
                            open(context, updateInfo?.downloadUrl?.ifEmpty { null } ?: W1ndy.APK_URL)
                        }
                    }

                    Spacer(Modifier.height(28.dp))
                    // профиль
                    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(if (state.mode == W1ndy.MODE_DNS) "РЕЖИМ DNS" else "ПРОФИЛЬ", color = Dim, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.5.sp)
                        Spacer(Modifier.height(6.dp))
                        Text(state.title, color = TextMain, fontSize = 26.sp, fontWeight = FontWeight.ExtraBold, textAlign = TextAlign.Center)
                    }
                    Spacer(Modifier.height(32.dp))
                    ConnectButton(status, modifier = Modifier.align(Alignment.CenterHorizontally)) {
                        message = null
                        when (status) {
                            Status.Stopped -> onConnect()
                            Status.Started, Status.Starting -> onDisconnect()
                            else -> {}
                        }
                    }
                    Spacer(Modifier.height(22.dp))
                    StatusLine(status, modifier = Modifier.align(Alignment.CenterHorizontally))
                    message?.let {
                        Spacer(Modifier.height(12.dp))
                        Text(it, color = Red, fontSize = 14.sp, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
                    }
                }

                // ссылки внизу
                Column(Modifier.padding(top = 28.dp)) {
                    LinkCard(Icons.Rounded.Bolt, "LitEnergy VPN", "Быстрый и стабильный VPN · от 100 ₽ в месяц") { open(context, W1ndy.BOT_URL) }
                    Spacer(Modifier.height(10.dp))
                    LinkCard(Icons.Rounded.Campaign, "Наш канал", "Новости, акции и обновления") { open(context, W1ndy.CHANNEL_URL) }
                }
            }
        }
    }

    if (showSettings) {
        ModalBottomSheet(onDismissRequest = { showSettings = false }, containerColor = Raised) {
            Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 28.dp)) {
                Text("Настройки", color = TextMain, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(16.dp))
                SheetItem("Сменить режим", "Сейчас: " + if (state.mode == W1ndy.MODE_DNS) "только DNS" else "VPN") {
                    showSettings = false
                    onRestart(Step.Mode)
                }
                if (state.mode == W1ndy.MODE_VPN) {
                    SheetItem("Обновить конфигурацию", "Скачать актуальные настройки с сервера") {
                        if (!busy) {
                            busy = true
                            scope.launch {
                                try {
                                    onStateChange(W1ndy.updateVpn(context))
                                    message = null
                                } catch (e: Exception) {
                                    message = e.message
                                    onStateChange(W1ndy.load(context))
                                } finally {
                                    busy = false
                                    showSettings = false
                                }
                            }
                        }
                    }
                    SheetItem("Ввести другой ключ", "Текущее подключение будет удалено с телефона") {
                        showSettings = false
                        scope.launch {
                            if (status == Status.Started || status == Status.Starting) onDisconnect()
                            W1ndy.reset(context)
                            onRestart(Step.Key)
                        }
                    }
                } else {
                    SheetItem("Выбрать другой DNS", "Сейчас: ${state.dnsServer}") {
                        showSettings = false
                        onRestart(Step.Dns)
                    }
                }
                Spacer(Modifier.height(12.dp))
                Text("Версия ${BuildConfig.VERSION_NAME}", color = Dim, fontSize = 13.sp)
            }
        }
    }
}

@Composable
private fun ConnectButton(status: Status, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val transition = rememberInfiniteTransition(label = "pulse")
    val pulse by transition.animateFloat(
        initialValue = 0.92f, targetValue = 1.08f,
        animationSpec = infiniteRepeatable(tween(1100), RepeatMode.Reverse), label = "scale",
    )
    val glow by transition.animateFloat(
        initialValue = 0.25f, targetValue = 0.6f,
        animationSpec = infiniteRepeatable(tween(1400), RepeatMode.Reverse), label = "glow",
    )
    val on = status == Status.Started
    val busy = status == Status.Starting || status == Status.Stopping
    val accent = if (on) Green else TextMain
    Box(modifier.size(236.dp), contentAlignment = Alignment.Center) {
        // ореол
        Box(
            Modifier
                .size(236.dp)
                .scale(if (busy) pulse else 1f)
                .alpha(if (on) glow else if (busy) 0.35f else 0.0f)
                .clip(CircleShape)
                .background(if (on) Green.copy(alpha = 0.18f) else TextMain.copy(alpha = 0.06f)),
        )
        Box(
            Modifier
                .size(196.dp)
                .clip(CircleShape)
                .background(if (on) Color(0xFF0E2A16) else Surface)
                .border(BorderStroke(if (on) 2.dp else 1.dp, if (on) Green else BorderHover), CircleShape)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    enabled = status != Status.Stopping,
                    onClick = onClick,
                ),
            contentAlignment = Alignment.Center,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(Icons.Rounded.PowerSettingsNew, contentDescription = "Подключить", tint = accent, modifier = Modifier.size(72.dp))
                Spacer(Modifier.height(6.dp))
                Text(
                    when (status) {
                        Status.Stopped -> "Подключить"
                        Status.Starting -> "Подключение"
                        Status.Started -> "Отключить"
                        Status.Stopping -> "Отключение"
                    },
                    color = if (on) Green else Muted, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                )
            }
        }
    }
}

@Composable
private fun StatusLine(status: Status, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val connected = status == Status.Started
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    val since = remember(connected) { W1ndy.connectedAt(context, connected) }
    LaunchedEffect(connected) {
        while (connected) {
            now = System.currentTimeMillis()
            delay(1000)
        }
    }
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(8.dp).clip(CircleShape).background(if (connected) Green else if (status == Status.Stopped) Dim else TextMain))
            Spacer(Modifier.width(8.dp))
            Text(
                when (status) {
                    Status.Stopped -> "Не подключено"
                    Status.Starting -> "Подключаемся…"
                    Status.Started -> "Подключено"
                    Status.Stopping -> "Отключаемся…"
                },
                color = if (connected) Green else Muted, fontSize = 16.sp, fontWeight = FontWeight.SemiBold,
            )
        }
        if (connected && since > 0) {
            val s = ((now - since) / 1000).coerceAtLeast(0)
            Spacer(Modifier.height(4.dp))
            Text("%02d:%02d:%02d".format(s / 3600, (s / 60) % 60, s % 60), color = Muted, fontSize = 15.sp)
        }
    }
}

// ---------------------------------------------------------------- элементы

@Composable
private fun fieldColors() = OutlinedTextFieldDefaults.colors(
    focusedBorderColor = TextMain,
    unfocusedBorderColor = BorderHover,
    focusedContainerColor = Surface,
    unfocusedContainerColor = Surface,
    disabledContainerColor = Surface,
    cursorColor = TextMain,
    focusedTextColor = TextMain,
    unfocusedTextColor = TextMain,
)

@Composable
private fun StepHeader(title: String, subtitle: String) {
    Spacer(Modifier.height(8.dp))
    Text(title, color = TextMain, fontSize = 28.sp, fontWeight = FontWeight.ExtraBold)
    Spacer(Modifier.height(8.dp))
    Text(subtitle, color = Muted, fontSize = 15.sp)
}

@Composable
private fun ErrorText(error: String?) {
    if (error != null) {
        Spacer(Modifier.height(12.dp))
        Text(error, color = Red, fontSize = 14.sp)
    }
}

@Composable
private fun PrimaryButton(text: String, busy: Boolean, enabled: Boolean = true, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        enabled = enabled && !busy,
        shape = RoundedCornerShape(16.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = TextMain, contentColor = Color.Black,
            disabledContainerColor = Pill, disabledContentColor = Dim,
        ),
        modifier = Modifier.fillMaxWidth().height(56.dp),
    ) {
        if (busy) {
            CircularProgressIndicator(color = Color.Black, modifier = Modifier.size(22.dp), strokeWidth = 2.dp)
        } else {
            Text(text, fontSize = 16.sp, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun ChoiceCard(icon: ImageVector, title: String, subtitle: String, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(Surface)
            .border(1.dp, Border, RoundedCornerShape(20.dp))
            .clickable(onClick = onClick)
            .padding(18.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(48.dp).clip(RoundedCornerShape(14.dp)).background(Pill), contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription = null, tint = TextMain, modifier = Modifier.size(26.dp))
        }
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(title, color = TextMain, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(2.dp))
            Text(subtitle, color = Muted, fontSize = 14.sp)
        }
        Icon(Icons.Rounded.ChevronRight, contentDescription = null, tint = Dim)
    }
}

@Composable
private fun LinkCard(icon: ImageVector, title: String, subtitle: String, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(Surface)
            .border(1.dp, Border, RoundedCornerShape(20.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(42.dp).clip(RoundedCornerShape(13.dp)).background(Pill), contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription = null, tint = TextMain, modifier = Modifier.size(22.dp))
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(title, color = TextMain, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            Text(subtitle, color = Muted, fontSize = 13.sp)
        }
        Icon(Icons.Rounded.ChevronRight, contentDescription = null, tint = Dim)
    }
}

@Composable
private fun DnsRow(title: String, address: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 8.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = onClick, colors = RadioButtonDefaults.colors(selectedColor = TextMain, unselectedColor = Dim))
        Text(title, color = TextMain, fontSize = 16.sp, modifier = Modifier.weight(1f))
        if (address.isNotEmpty()) Text(address, color = Muted, fontSize = 15.sp, modifier = Modifier.padding(end = 12.dp))
    }
}

@Composable
private fun SheetItem(title: String, subtitle: String, onClick: () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(Surface)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
    ) {
        Text(title, color = TextMain, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
        Text(subtitle, color = Muted, fontSize = 13.sp)
    }
}
