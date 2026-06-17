# Kepler Studio Remaster Models

이 폴더는 온디바이스 리마스터 모델 슬롯입니다.

앱은 여러 모델 후보를 표시하지만, 런타임에서는 하나의 모델 슬롯만 active 상태로 유지하도록 구성되어 있습니다.
새 모델을 선택하면 이전 슬롯을 먼저 해제한 뒤 선택한 슬롯을 확인합니다.

## Expected files

### Universal

- `universal_auto_router.tflite`
- `universal_balancer.tflite`

### Masking

- `edge_masker.task`
- `interactive_masker.task`

### People and scenes

- `portrait_guard.tflite`
- `sky_balancer.tflite`
- `low_light_cleaner.tflite`
- `dehaze_reasoner.tflite`
- `shadow_lifter.tflite`

### Removal and reflection

- `flare_guard.tflite`
- `glass_reflection_remover.tflite`
- `specular_highlight_tamer.tflite`
- `object_dust_eraser.tflite`

### Restoration and look

- `detail_restorer_lite.tflite`
- `detail_restorer_hq.tflite`
- `jpeg_artifact_cleaner.tflite`
- `look_reasoner.tflite`

대형 모델 파일은 Git LFS 또는 release asset으로 관리하는 것을 권장합니다.
