package ph.edu.bsit.tcc.ojtdtr.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.colorResource
import ph.edu.bsit.tcc.ojtdtr.R

@Composable
fun OjtDtrTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val crimson = colorResource(R.color.brand_crimson)
    val gold = colorResource(R.color.brand_gold)
    val offWhite = colorResource(R.color.brand_off_white)
    val ink = colorResource(R.color.brand_ink)
    val colors = if (darkTheme) {
        darkColorScheme(
            primary = colorResource(R.color.brand_light_crimson),
            onPrimary = crimson,
            secondary = gold,
            onSecondary = ink,
            secondaryContainer = gold,
            onSecondaryContainer = ink,
            background = colorResource(R.color.brand_dark_surface),
            onBackground = offWhite,
            surface = colorResource(R.color.brand_dark_surface),
            onSurface = offWhite,
        )
    } else {
        lightColorScheme(
            primary = crimson,
            onPrimary = offWhite,
            secondary = gold,
            onSecondary = ink,
            secondaryContainer = gold,
            onSecondaryContainer = ink,
            background = offWhite,
            onBackground = ink,
            surface = offWhite,
            onSurface = ink,
        )
    }
    MaterialTheme(colorScheme = colors, content = content)
}
