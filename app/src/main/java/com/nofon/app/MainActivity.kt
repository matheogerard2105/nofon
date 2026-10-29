package com.nofon.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import com.nofon.app.navigation.NofonNavHost
import com.nofon.core.designsystem.NofonTheme
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            NofonTheme {
                Surface(Modifier.fillMaxSize()) {
                    NofonNavHost()
                }
            }
        }
    }
}
