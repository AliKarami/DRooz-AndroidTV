package com.alikarami.drooz.ui.debug

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.runtime.CompositionLocalProvider
import androidx.tv.material3.Button
import androidx.tv.material3.Text
import com.alikarami.drooz.site.model.TitleDetail

private val Bg = Color(0xFF0B0B0F)

/**
 * Throwaway login/title screens proving login → browse → play on the device. Replaced in Plan 2.
 * The logged-in home is the real [home] screen; it is kept in a saveable-state holder so its scroll
 * positions survive a trip to the debug title screen.
 */
@Composable
fun DebugApp(vm: DebugViewModel, home: @Composable () -> Unit) {
    val state by vm.state.collectAsState()
    val holder = rememberSaveableStateHolder()
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
        if (state == DebugState.Home) holder.SaveableStateProvider("home") { home() }
        else Box(Modifier.fillMaxSize().background(Bg).padding(32.dp)) {
            when (val s = state) {
                DebugState.Loading -> Text("در حال بارگذاری…", color = Color.White, fontSize = 24.sp)
                is DebugState.Login -> LoginScreen(s, vm::login)
                DebugState.Home -> Unit
                is DebugState.Title -> {
                    BackHandler { vm.backToHome() }
                    TitleScreen(s.detail, onPlay = { vm.play(it) })
                }
                is DebugState.Error -> Column {
                    BackHandler { vm.backToHome() }
                    Text("خطا: ${s.message}", color = Color(0xFFFF6B6B), fontSize = 22.sp)
                    Button(onClick = vm::backToHome) { Text("تلاش دوباره") }
                }
            }
        }
    }
}

@Composable
private fun Field(label: String, value: String, password: Boolean = false, type: KeyboardType = KeyboardType.Email, onChange: (String) -> Unit) {
    val focus = LocalFocusManager.current
    Column(Modifier.padding(vertical = 6.dp)) {
        Text(label, color = Color.LightGray, fontSize = 16.sp)
        BasicTextField(
            value = value,
            onValueChange = onChange,
            singleLine = true,
            textStyle = TextStyle(color = Color.White, fontSize = 22.sp),
            visualTransformation = if (password) PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
            keyboardOptions = KeyboardOptions(
                keyboardType = if (password) KeyboardType.Password else type,
                autoCorrectEnabled = false,
                imeAction = ImeAction.Next,
            ),
            keyboardActions = KeyboardActions(onNext = { focus.moveFocus(FocusDirection.Down) }),
            // Text fields swallow D-pad up/down; on a TV remote they must move between fields.
            modifier = Modifier.width(420.dp).border(1.dp, Color.Gray).padding(10.dp).onPreviewKeyEvent { e ->
                if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when (e.key) {
                    Key.DirectionDown -> focus.moveFocus(FocusDirection.Down)
                    Key.DirectionUp -> focus.moveFocus(FocusDirection.Up)
                    else -> false
                }
            },
        )
    }
}

@Composable
private fun LoginScreen(s: DebugState.Login, onLogin: (String, String, String?) -> Unit) {
    var user by remember { mutableStateOf("") }
    var pass by remember { mutableStateOf("") }
    var mobile by remember { mutableStateOf("") }
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        Text("ورود به دی‌ال‌روز", color = Color.White, fontSize = 30.sp)
        Field("نام کاربری یا ایمیل", user) { user = it }
        Field("رمز عبور", pass, password = true) { pass = it }
        if (s.mobileHint != null) {
            Text("لطفا شماره موبایل خود را کامل کنید (${s.mobileHint})", color = Color(0xFFF5C518), fontSize = 18.sp)
            Field("شماره موبایل", mobile, type = KeyboardType.Phone) { mobile = it }
        }
        s.error?.let { Text(it, color = Color(0xFFFF6B6B), fontSize = 18.sp) }
        Button(onClick = { onLogin(user.trim(), pass, mobile.trim().ifEmpty { null }) }, modifier = Modifier.padding(top = 12.dp)) {
            Text("ورود")
        }
    }
}

@Composable
private fun TitleScreen(d: TitleDetail, onPlay: (com.alikarami.drooz.site.model.Episode?) -> Unit) {
    LazyColumn {
        item {
            Text("${d.card.title} (${d.card.year ?: ""})", color = Color.White, fontSize = 30.sp)
            Text(d.card.synopsis.orEmpty(), color = Color.LightGray, fontSize = 18.sp)
            Row(Modifier.padding(vertical = 12.dp)) { Button(onClick = { onPlay(null) }) { Text("پخش") } }
        }
        items(d.catalog.main) { season ->
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(vertical = 6.dp)) {
                items(season.episodes) { ep ->
                    Button(onClick = { onPlay(ep) }) {
                        Text("فصل ${season.number} · قسمت ${ep.number}" + if (ep.played) " ✓" else "")
                    }
                }
            }
        }
    }
}
