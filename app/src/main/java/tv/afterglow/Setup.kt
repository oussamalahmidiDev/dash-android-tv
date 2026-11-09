package tv.afterglow

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

internal const val STARTER_IPTV = "https://iptv-org.github.io/iptv/index.m3u"
internal const val STARTER_TORRENTIO = "https://torrentio.strem.fun/providers=yts,1337x,rarbg,thepiratebay|sort=seeders/manifest.json"

@Composable
internal fun DashSplash() {
    Box(Modifier.fillMaxSize().background(Ink), contentAlignment = Alignment.Center) {
        Image(painterResource(R.drawable.dash_mark), contentDescription = "DASH", modifier = Modifier.size(96.dp))
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun WelcomeSetup(model: AfterglowViewModel) {
    var iptv by rememberSaveable { mutableStateOf(false) }
    var torrentio by rememberSaveable { mutableStateOf(false) }
    BoxWithConstraints(Modifier.fillMaxSize().background(Ink), contentAlignment = Alignment.Center) {
        val wide = maxWidth >= 820.dp
        val intro: @Composable () -> Unit = {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Brand()
                Heading("Welcome to DASH", size = if (wide) 42 else 32)
                Muted("Choose what you’d like to watch. You can add more sources later in Settings.", size = 15)
            }
        }
        val sources: @Composable () -> Unit = {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Column(
                    Modifier.fillMaxWidth().background(Color(0xFF1E2229), RoundedCornerShape(20.dp)).padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(9.dp)
                ) {
                    Heading("Your sources", size = 22)
                    SetupSource("Cinemeta", "Browse and search for movies and series.", TvGlyph.MOVIES, "Included")
                    HorizontalDivider(color = Slate)
                    SetupSource("IPTV.org", "Watch public live TV channels.", TvGlyph.LIVE,
                        if (iptv) "Selected" else "Optional", "setup/iptv", !model.busy, preferred = true) { iptv = !iptv }
                    HorizontalDivider(color = Slate)
                    SetupSource("Torrentio", "Find more sources for movies and series.", TvGlyph.LINK,
                        if (torrentio) "Selected" else "Optional", "setup/torrentio", !model.busy) { torrentio = !torrentio }
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Action("Continue", TvGlyph.NEXT, "setup/continue", enabled = !model.busy, primary = true) {
                        model.finishSetup(iptv, torrentio)
                    }
                    Action("Skip optional sources", TvGlyph.NEXT, "setup/skip", enabled = !model.busy) {
                        model.finishSetup(false, false)
                    }
                }
                if (model.busy) Muted("Setting up your sources…")
                model.setupErrors.forEach { Muted(it) }
                model.notice?.let { Muted(it) }
            }
        }
        val frame = Modifier.widthIn(max = if (wide) 990.dp else 570.dp).fillMaxWidth()
            .verticalScroll(rememberScrollState()).padding(horizontal = if (wide) Rail else 24.dp, vertical = 30.dp)
        if (wide) {
            Row(frame, verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(42.dp)) {
                Box(Modifier.weight(.9f)) { intro() }
                Box(Modifier.weight(1.25f)) { sources() }
            }
        } else {
            Column(frame, verticalArrangement = Arrangement.spacedBy(22.dp)) {
                intro()
                sources()
            }
        }
    }
}

@Composable
private fun SetupSource(
    name: String, description: String, icon: TvGlyph, status: String, id: String? = null,
    enabled: Boolean = true, preferred: Boolean = false, onClick: () -> Unit = {}
) {
    val row: @Composable (Boolean) -> Unit = { focused ->
        val tint = if (focused) Ink else Porcelain
        val hint = if (focused) Ink.copy(alpha = .76f) else Fog
        Row(Modifier.fillMaxWidth().background(if (focused) Porcelain else Color.Transparent)
            .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Glyph(icon, tint, Modifier.size(24.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(name, color = tint, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                Text(description, color = hint, fontSize = 13.sp, lineHeight = 18.sp)
            }
            Text(status, color = if (status == "Selected" && !focused) Glacier else hint,
                fontSize = 12.sp, fontWeight = FontWeight.Medium)
            Glyph(if (status == "Optional") TvGlyph.ADD else TvGlyph.CHECK, tint, Modifier.size(17.dp))
        }
    }
    if (id == null) row(false)
    else RemoteButton(id, Modifier.fillMaxWidth().semantics {
        role = Role.Button
        stateDescription = status
    }, preferred = preferred, enabled = enabled, radius = 12, onClick = onClick, content = row)
}

@Composable
internal fun ConfirmDashAction(
    title: String, explanation: String, keepLabel: String, confirmLabel: String,
    onDismiss: () -> Unit, onConfirm: () -> Unit
) {
    val memory = remember { FocusMemory() }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        FocusScope(memory, "confirm") {
            Column(Modifier.widthIn(max = 460.dp)
                .background(Slate, androidx.compose.foundation.shape.RoundedCornerShape(19.dp)).padding(23.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Heading(title, size = 23)
                Muted(explanation)
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Action(keepLabel, TvGlyph.BACK, preferred = true, primary = true, onClick = onDismiss)
                    Action(confirmLabel, TvGlyph.TRASH, onClick = onConfirm)
                }
            }
        }
    }
}
