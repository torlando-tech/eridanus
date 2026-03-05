package tech.torlando.ara

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import tech.torlando.ara.data.DarkModeOption
import tech.torlando.ara.ui.navigation.AppNavigation
import tech.torlando.ara.ui.theme.AraTheme
import tech.torlando.ara.viewmodel.AraViewModel

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val viewModel: AraViewModel = viewModel()
            val theme by viewModel.theme.collectAsState()
            val darkModeOption by viewModel.darkMode.collectAsState()

            val isDarkTheme = when (darkModeOption) {
                DarkModeOption.SYSTEM -> isSystemInDarkTheme()
                DarkModeOption.LIGHT -> false
                DarkModeOption.DARK -> true
            }

            AraTheme(
                darkTheme = isDarkTheme,
                selectedTheme = theme,
            ) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    AppNavigation(viewModel = viewModel)
                }
            }
        }
    }
}
