package com.projectnuke.keplerstudio

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.lifecycle.viewmodel.compose.viewModel
import com.projectnuke.keplerstudio.editor.EditorViewModel
import com.projectnuke.keplerstudio.ui.EditorScreen

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val vm: EditorViewModel = viewModel()
            EditorScreen(viewModel = vm)
        }
    }
}
