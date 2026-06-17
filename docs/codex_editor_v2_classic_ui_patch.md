# Codex patch prompt: restore classic editor UI in EditorScreenV2

## Goal

Kepler Studio currently uses `EditorScreenV2` from `MainActivity`. V2 introduced the correct export behavior: the top `저장` button opens an export settings dialog instead of showing file format/resolution controls permanently in the edit panel.

Keep that export-dialog behavior, but make V2 feel like the previous classic editor UI again.

## Required changes

### 1. Keep export settings as a save-time dialog

Do not re-add always-visible export format/resolution controls to the bottom adjustment panel.

Expected flow:

- User taps top `저장` button.
- Show `내보내기 설정` dialog.
- Dialog contains `파일 형식` options from `ExportFormat.values()`.
- Dialog contains `해상도` options from `ExportResolution.values()`.
- Dialog `저장` button calls `viewModel.exportPreview()`.
- Dialog `취소` closes the dialog.

### 2. Restore classic tool rail coverage

`EditorScreenV2` should expose the classic tool list, not only the shortened list.

Use this order:

- 자동
- 리마스터
- 프로필
- 프리셋
- 자르기
- 마스크
- 제거
- 조명
- 색상
- 효과
- 디테일
- 옵틱
- 기하
- 블러
- 모델

For tools whose full implementation is not connected in V2, show the same style of placeholder card used by the old `EditorScreen.kt`.

Suggested placeholder text:

- 프로필: `프로필 브라우저와 강도 조절은 다음 단계에서 연결됩니다`
- 프리셋: `프리셋 목록과 저장 기능은 다음 단계에서 연결됩니다`
- 자르기: `비율, 회전, 수평계 기반 자르기 도구를 준비 중입니다`
- 제거: `지우개, 반사 제거, 센서 먼지 제거 엔진을 연결할 예정입니다`
- 옵틱: `색수차 제거와 렌즈 프로필 보정을 준비 중입니다`
- 기하: `왜곡, 수직, 수평, 원근 보정을 준비 중입니다`
- 블러: `렌즈 블러와 초점 영역 편집을 준비 중입니다`
- 모델: `자동 마스크, 노이즈 억제, 디테일 복원 보조를 준비 중입니다`

### 3. Restore zoomable preview feel

`EditorScreenV2` should not use a plain static image preview only.

Add a local `V2ZoomablePreview` composable or equivalent:

- Pinch zoom via `detectTransformGestures`.
- Pan while zoomed.
- Double tap toggles between 1x and about 2.5x.
- Long press temporarily shows the original image.
- Show a small top-left badge: `원본` while long-press compare is active, otherwise `편집본`.

This does not need to be pixel-perfect versus the old `ZoomablePreview`, but the interaction feel should match the classic UI.

### 4. Fix accidental duplicate exposure slider

In the current `EditorScreenV2.kt`, remove the bogus duplicate exposure slider line:

```kotlin
ParamSlider2("노출", params.exposure, -1f, 1f) { onChange { it.copy(exposure = it.exposure + (it.exposure - it.exposure) + 0f) } }
```

Only keep the proper one:

```kotlin
ParamSlider2("노출", params.exposure, -1f, 1f) { value -> onChange { it.copy(exposure = value) } }
```

### 5. Keep V2 connected from MainActivity

`MainActivity.kt` should continue using:

```kotlin
EditorScreenV2(viewModel = vm)
```

Do not switch back to old `EditorScreen` unless necessary.

### 6. Keep mask/remaster features connected

Do not remove:

- `RemasterToolPanel(onQuickAutoEnhance = onAutoEnhance)`
- `MaskingToolPanel()`
- export dialog behavior
- current AI/model session hooks

### 7. Build check

After patching, run:

```powershell
.\gradlew.bat assembleDebug
```

Fix any compile errors caused by missing imports.

Likely imports needed for zoom preview:

```kotlin
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
```

## Commit message

Use:

```text
Restore classic editor layout in V2
```
