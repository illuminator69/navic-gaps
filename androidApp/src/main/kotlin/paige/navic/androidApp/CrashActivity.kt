package paige.navic.androidApp

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.SoundEffectConstants
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.expressiveLightColorScheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.sp
import paige.navic.util.redactLines

class CrashActivity : ComponentActivity() {
	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)
		enableEdgeToEdge()

		// Already redacted by crashReportText; run through the rule again here so what this screen
		// shows, copies and shares is redacted whoever started it (Worbus P4 N3).
		val stackTrace = redactLines(intent.getStringExtra("stacktrace") ?: "no stacktrace")

		setContent {
			val view = LocalView.current
			MaterialTheme(
				colorScheme = if (Build.VERSION.SDK_INT >= 31)
					if (isSystemInDarkTheme())
						dynamicDarkColorScheme(LocalContext.current)
					else dynamicLightColorScheme(LocalContext.current)
				else
					if (isSystemInDarkTheme())
						darkColorScheme()
					else expressiveLightColorScheme()
			) {
				Surface(Modifier.fillMaxSize()) {
					Column(
						modifier = Modifier
							.fillMaxSize()
							.padding(16.dp)
							.statusBarsPadding()
							.navigationBarsPadding()
					) {
						Column(
							modifier = Modifier
								.weight(1f)
								.fillMaxWidth()
								.verticalScroll(rememberScrollState())
						) {
							Text(
								text = "Something went wrong",
								style = MaterialTheme.typography.headlineMedium
							)
							Text("Navic has encountered an unknown error and needs to close.")
							Spacer(modifier = Modifier.height(16.dp))
							SelectionContainer(
								Modifier
									.background(
										MaterialTheme.colorScheme.surfaceContainer,
										MaterialTheme.shapes.extraSmall
									)
									.padding(8.dp)
									.horizontalScroll(rememberScrollState())
							) {
								Text(
									stackTrace,
									fontFamily = FontFamily.Monospace,
									fontSize = 10.sp,
									lineHeight = 10.sp,
									color = MaterialTheme.colorScheme.onSurface,
									modifier = Modifier.padding(end = 48.dp)
								)
							}
						}
						Column(Modifier.fillMaxWidth()) {
							// Worbus P4 N3: the report can leave the phone. Labels are this module's
							// own strings: this screen runs in the :crash process, where Compose
							// resources have no context.
							Row(
								modifier = Modifier.fillMaxWidth(),
								horizontalArrangement = Arrangement.spacedBy(8.dp)
							) {
								OutlinedButton(
									modifier = Modifier.weight(1f),
									onClick = {
										view.playSoundEffect(SoundEffectConstants.CLICK)
										copyReport(stackTrace)
									},
									content = { Text(stringResource(R.string.crash_copy)) }
								)
								OutlinedButton(
									modifier = Modifier.weight(1f),
									onClick = {
										view.playSoundEffect(SoundEffectConstants.CLICK)
										shareReport(stackTrace)
									},
									content = { Text(stringResource(R.string.crash_share)) }
								)
							}
							Button(
								modifier = Modifier.fillMaxWidth(),
								onClick = {
									view.playSoundEffect(SoundEffectConstants.CLICK)
									finish()
								},
								content = { Text("OK") }
							)
						}
					}
				}
			}
		}
	}

	private fun copyReport(report: String) {
		val clipboard = getSystemService(CLIPBOARD_SERVICE) as? ClipboardManager ?: return
		clipboard.setPrimaryClip(ClipData.newPlainText("Navic crash report", report))
	}

	private fun shareReport(report: String) {
		val intent = Intent(Intent.ACTION_SEND).apply {
			type = "text/plain"
			putExtra(Intent.EXTRA_TEXT, report)
			putExtra(Intent.EXTRA_SUBJECT, "Navic crash report")
		}
		runCatching { startActivity(Intent.createChooser(intent, null)) }
	}
}
