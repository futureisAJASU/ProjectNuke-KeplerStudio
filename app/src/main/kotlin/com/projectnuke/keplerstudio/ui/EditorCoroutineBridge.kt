package com.projectnuke.keplerstudio.ui

import androidx.lifecycle.viewModelScope as lifecycleJobScope
import com.projectnuke.keplerstudio.editor.EditorViewModel

val EditorViewModel.viewModelScope
    get() = this.lifecycleJobScope
