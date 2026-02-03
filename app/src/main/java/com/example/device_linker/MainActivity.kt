package com.example.device_linker

import android.annotation.SuppressLint
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.tooling.preview.Preview
import com.example.device_linker.ui.theme.DeviceLinkerTheme
import java.security.MessageDigest

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            DeviceLinkerTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    DeviceLinkerApp(
                        modifier = Modifier.padding(innerPadding)
                    )
                }
            }
        }
    }
}

@SuppressLint("HardwareIds")
@Composable
fun DeviceLinkerApp(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val androidId = Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
    val derivedAddress = deriveAddress(androidId)

    Column(
        modifier = modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(text = "Hardware ID:")
        Text(text = androidId)
        Text(text = "Wallet Address:")
        Text(text = derivedAddress)
    }
}

private fun deriveAddress(seed: String): String {
    val salt = "D-Linker-Salt" // Or a more secure, randomly generated salt
    val saltedSeed = seed + salt
    val digest = MessageDigest.getInstance("SHA-256")
    val hash = digest.digest(saltedSeed.toByteArray(Charsets.UTF_8))
    // Take the first 20 bytes for an Ethereum-like address
    val addressBytes = hash.take(20)
    return "0x" + addressBytes.joinToString("") { "%02x".format(it) }
}


@Preview(showBackground = true)
@Composable
fun GreetingPreview() {
    DeviceLinkerTheme {
        DeviceLinkerApp()
    }
}
