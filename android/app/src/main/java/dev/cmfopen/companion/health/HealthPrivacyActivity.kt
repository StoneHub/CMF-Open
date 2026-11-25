package dev.cmfopen.companion.health

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.cmfopen.companion.ui.CompanionTheme

/**
 * Health Connect requires an app to explain how it uses health data before it will grant
 * permissions (ACTION_SHOW_PERMISSIONS_RATIONALE, and VIEW_PERMISSION_USAGE on Android 14+).
 */
class HealthPrivacyActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            CompanionTheme {
                Surface(Modifier.fillMaxSize()) {
                    Column(Modifier.safeDrawingPadding().padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text("How CMF Companion uses health data", style = MaterialTheme.typography.titleLarge)
                        Text(
                            "CMF Companion copies the steps, heart rate, blood oxygen, sleep and workouts your CMF watch " +
                                "records into Health Connect, so apps you choose (such as Google Health) can show them.",
                        )
                        Text("It only writes data. It never reads your other health data, and it sends nothing to any server.")
                        Text("You can turn this off in the app's Health tab, and remove the data at any time in Health Connect.")
                    }
                }
            }
        }
    }
}
