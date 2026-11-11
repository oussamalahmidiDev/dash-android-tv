package tv.afterglow

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import java.util.Locale

private val qualityPattern = Regex("""\b(4k|2160p|1440p|1080p|720p|480p|360p)\b""", RegexOption.IGNORE_CASE)
private val seedPattern = Regex(
    """(?:\b(?:seeds?|seeders?)\s*[:=]?\s*|👤\s*)([0-9]+(?:,[0-9]{3})*)|\b([0-9]+(?:,[0-9]{3})*)\s+(?:seeds?|seeders?)\b""",
    RegexOption.IGNORE_CASE
)

internal fun sourceQuality(source: Source): String? {
    val value = qualityPattern.find(source.label + " " + source.description)?.value?.lowercase(Locale.ROOT)
    return if (value == "2160p" || value == "4k") "4K" else value
}

internal fun Source.seedCount(): Int? = seeds?.takeIf { it >= 0 }
    ?: seedPattern.find("$label $description")?.groupValues?.drop(1)
        ?.firstOrNull(String::isNotEmpty)?.replace(",", "")?.toIntOrNull()

@Composable
internal fun SourceHealthDot(source: Source, focused: Boolean) {
    val count = remember(source) { source.seedCount() }
    val (label, tint) = when {
        source.url != null -> "Direct stream" to if (focused) Color(0xFF536170) else Fog
        count == null -> "Seed count unavailable" to if (focused) Color(0xFF536170) else Fog
        count >= 50 -> "Very good seed availability" to if (focused) Color(0xFF197744) else Color(0xFF6BD99A)
        count >= 10 -> "Medium seed availability" to if (focused) Color(0xFFA74C08) else Color(0xFFFFAB66)
        else -> "Low seed availability" to if (focused) Color(0xFFBC3046) else Color(0xFFFF7888)
    }
    Box(Modifier.size(8.dp).background(tint, CircleShape).semantics { contentDescription = label })
}

@Composable
internal fun SourceDetails(source: Source, close: () -> Unit) {
    val memory = remember { FocusMemory() }
    Dialog(onDismissRequest = close, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        FocusScope(memory, "source/details") {
            Column(Modifier.widthIn(max = 480.dp).fillMaxWidth().padding(horizontal = 12.dp)
                .background(Slate, RoundedCornerShape(19.dp)).padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Heading("Source details", size = 23)
                Column(Modifier.heightIn(max = 230.dp).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Heading(source.label, size = 16)
                    Muted("Provider · ${source.provider}")
                    Muted("Quality · ${sourceQuality(source) ?: "Unspecified"}")
                    if (source.url != null) Muted("Direct stream")
                    else Muted("Torrent · ${source.seedCount()?.let { "$it seeds" } ?: "Seed count unavailable"}")
                    if (source.description.isNotBlank() && source.description != source.label) Muted(source.description)
                }
                Action("Close", TvGlyph.CLOSE, "source/details/close", preferred = true, onClick = close)
            }
        }
    }
}
