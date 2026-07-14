package au.com.chrismckechnie.hermesmobile

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

class BubbleActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val title = intent.getStringExtra(HermesNotificationCoordinator.EXTRA_SESSION_TITLE) ?: "Hermes session"
        val hostId = intent.getStringExtra(HermesNotificationCoordinator.EXTRA_HOST_ID).orEmpty()
        val sessionId = intent.getStringExtra(HermesNotificationCoordinator.EXTRA_SESSION_ID).orEmpty()
        setContent {
            MaterialTheme {
                Surface(Modifier.fillMaxSize()) {
                    Column(
                        modifier = Modifier.fillMaxSize().padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                    ) {
                        Text(title, style = MaterialTheme.typography.titleMedium)
                        Text("Active on your Hermes host", modifier = Modifier.padding(top = 8.dp, bottom = 20.dp))
                        Button(onClick = {
                            startActivity(HermesNotificationCoordinator.sessionIntent(this@BubbleActivity, hostId, sessionId))
                            finish()
                        }) { Text("Open full chat") }
                    }
                }
            }
        }
    }
}
