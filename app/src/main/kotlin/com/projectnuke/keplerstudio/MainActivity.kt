package com.projectnuke.keplerstudio

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.lifecycle.viewmodel.compose.viewModel
import com.projectnuke.keplerstudio.editor.EditorViewModel
import com.projectnuke.keplerstudio.ui.EditorScreenV2

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val vm: EditorViewModel = viewModel()
            EditorScreenV2(viewModel = vm)
        }
    }
}
