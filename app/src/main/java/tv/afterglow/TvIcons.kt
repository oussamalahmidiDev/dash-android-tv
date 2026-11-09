package tv.afterglow

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

internal enum class TvGlyph(val data: String, val filled: Boolean = false, val numeral: String? = null) {
    HOME("M3 10.5 12 3 21 10.5V21H15V14H9V21H3Z"),
    SEARCH("M20.5 20.5 16 16M18 10.5A7.5 7.5 0 1 1 3 10.5A7.5 7.5 0 1 1 18 10.5Z"),
    MOVIES("M3 7H21V20H3ZM3 7 6 3H21L18 7M9 3 6 7M15 3 12 7M3 11H21"),
    SERIES("M5 5H19M7 2H17M3 8H21V21H3ZM10 11 16 14.5 10 18Z"),
    LIVE("M3 7H21V19H3ZM8 22H16M8 2 12 7 16 2"),
    SETTINGS("M10 3H14L14.5 6 17 7.5 20 6.5 22 10 19.5 12 19.5 14 22 16 20 19.5 17 18.5 14.5 20 14 22H10L9.5 20 7 18.5 4 19.5 2 16 4.5 14V12L2 10 4 6.5 7 7.5 9.5 6ZM15 12.5A3 3 0 1 1 9 12.5A3 3 0 1 1 15 12.5Z"),
    BACK("M19 12H5M11 6 5 12 11 18"),
    NEXT("M9 5 16 12 9 19"),
    PLAY("M7 4.5 20 12 7 19.5Z", filled = true),
    PAUSE("M6 5H10V19H6ZM14 5H18V19H14Z", filled = true),
    REWIND("M8 3 4 7 8 11M4 7H13A7 7 0 1 1 7.5 18.3", numeral = "10"),
    FORWARD("M16 3 20 7 16 11M20 7H11A7 7 0 1 0 16.5 18.3", numeral = "30"),
    AUDIO("M3 10H7L12 6V18L7 14H3ZM15 9A4.2 4.2 0 0 1 15 15M18 6A8 8 0 0 1 18 18"),
    MINIMIZE("M3 5H21V19H3ZM12 11H18V16H12Z"),
    EXPAND("M9 4H4V9M15 4H20V9M4 15V20H9M20 15V20H15"),
    SUBTITLES("M3 5H21V16H11L7 20V16H3ZM6.5 9H10M13 9H17.5M6.5 12.5H17.5"),
    RETRY("M20 10A8 8 0 1 0 20 15M20 4V10H14"),
    CHECK("M5 12 10 17 19 7"),
    CLOSE("M6 6 18 18M18 6 6 18"),
    ADD("M12 5V19M5 12H19"),
    GRID("M4 4H10V10H4ZM14 4H20V10H14ZM4 14H10V20H4ZM14 14H20V20H14Z"),
    FOLDER("M3 6H9L11 8H21V19H3Z"),
    STAR("M12 3 14.7 8.7 21 9.6 16.5 14 17.5 20.5 12 17.4 6.5 20.5 7.5 14 3 9.6 9.3 8.7Z"),
    STAR_FILLED("M12 3 14.7 8.7 21 9.6 16.5 14 17.5 20.5 12 17.4 6.5 20.5 7.5 14 3 9.6 9.3 8.7Z", filled = true),
    DOWNLOAD("M12 3V15M7 10 12 15 17 10M4 15V20H20V15"),
    TRASH("M4 6H20M9 6V3H15V6M6 6L7 21H17L18 6M10 10V17M14 10V17"),
    EYE("M2 12Q12 1 22 12Q12 23 2 12ZM15 12A3 3 0 1 1 9 12A3 3 0 1 1 15 12Z"),
    EYE_OFF("M3 3 21 21M10 6Q16 4 22 12Q20 14 18 15.5M6 8Q4 9 2 12Q8 19 15 17.5"),
    LOCK("M5 10H19V21H5ZM8 10V7A4 4 0 0 1 16 7V10M12 14V17"),
    UNLOCK("M5 10H19V21H5ZM8 10V7A4 4 0 0 1 16 7M12 14V17"),
    EDIT("M4 16 16 4 20 8 8 20H4ZM14 6 18 10"),
    LINK("M10 8 13 5A4 4 0 0 1 19 11L16 14M14 16 11 19A4 4 0 0 1 5 13L8 10M8 16 16 8"),
    RADIO("M19 12A7 7 0 1 1 5 12A7 7 0 1 1 19 12Z"),
    CLOCK("M21 12A9 9 0 1 1 3 12A9 9 0 1 1 21 12ZM12 7V12L15.5 14")
}

@Composable
internal fun Glyph(glyph: TvGlyph, color: Color, modifier: Modifier = Modifier.size(24.dp)) {
    val path = remember(glyph) { PathParser().parsePathString(glyph.data).toPath() }
    Box(modifier, contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            scale(size.minDimension / 24f, pivot = Offset.Zero) {
                if (glyph.filled) drawPath(path, color)
                else drawPath(path, color, style = Stroke(1.7f, cap = StrokeCap.Round, join = StrokeJoin.Round))
            }
        }
        glyph.numeral?.let {
            Text(it, color = color, fontSize = 7.sp, lineHeight = 8.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.align(Alignment.Center).offset(y = 2.dp).clearAndSetSemantics { })
        }
    }
}

@Composable
internal fun IconAction(
    icon: TvGlyph, label: String, id: String, modifier: Modifier = Modifier,
    preferred: Boolean = false, primary: Boolean = false, enabled: Boolean = true,
    onClick: () -> Unit
) {
    val size = if (primary) 52.dp else 44.dp
    RemoteButton(id, modifier.size(size).semantics { contentDescription = label },
        preferred = preferred, enabled = enabled, filled = primary, radius = size.value.toInt() / 2, onClick = onClick) { focused ->
        Box(Modifier.fillMaxSize().background(if (primary || focused) Porcelain else Slate),
            contentAlignment = Alignment.Center) {
            Glyph(icon, if (primary || focused) Ink else Porcelain, Modifier.size(if (primary) 27.dp else 23.dp))
        }
    }
}

internal val Kind.glyph get() = when (this) {
    Kind.LIVE -> TvGlyph.LIVE
    Kind.MOVIE -> TvGlyph.MOVIES
    Kind.SERIES -> TvGlyph.SERIES
    Kind.HIDDEN -> TvGlyph.EYE_OFF
}
