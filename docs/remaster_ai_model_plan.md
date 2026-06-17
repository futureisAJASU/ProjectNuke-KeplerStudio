# Kepler Studio Remaster AI Model Plan

이 문서는 Kepler Studio 리마스터 기능에 사용할 온디바이스 모델 후보와 적용 전략을 기록한다. GitHub에는 코드와 계획만 저장하고, 실제 `.tflite`, `.task`, `.onnx` 모델 바이너리는 로컬 `app/src/main/assets/models/`에만 둔다.

## First three models to implement

1. `edge_masker.task`
   - Slot: Edge Masker
   - Runtime: MediaPipe Task
   - Purpose: 피사체·하늘·배경 등 선택 보정용 마스크 생성
   - Strategy: 바로 연결 후보. MediaPipe Image Segmenter / Interactive Segmenter 계열을 우선 검토한다.
   - Why first: AI 리마스터의 기반은 영역별 보정이므로, 마스크가 가장 먼저 필요하다.

2. `universal_balancer.tflite`
   - Slot: Universal Balancer
   - Runtime: LiteRT / TFLite
   - Purpose: 사진 전체의 노출, 대비, WB, 채도, LUT 강도, 디테일 강도 자동 추정
   - Strategy: 외부 모델 그대로 사용하지 말고 Kepler Studio 전용 student를 직접 학습한다.
   - Teacher/source: 현재 histogram 기반 자동 보정, 프리셋 추출, curve-matched LUT 추정, 사람이 고른 결과.

3. `flare_guard.tflite`
   - Slot: Flare Guard
   - Runtime: LiteRT / Native Tile
   - Purpose: 렌즈 플레어, 강한 광원 번짐, 야간 조명 halo/streak 완화
   - Strategy: Flare7K++ 계열 연구/데이터를 teacher 또는 비교 기준으로 쓰고, 앱용 경량 student로 증류한다.
   - Why first: 기본 보정 앱이 잘 못하는 차별화 기능이다.

## Model strategy groups

### A. Ready or near-ready to integrate

| Slot | Candidate | Use | Notes |
|---|---|---|---|
| Edge Masker | MediaPipe Image Segmenter | 바로 연결 | `.task` asset 기반. category/confidence mask를 받아 영역별 보정에 사용. |
| Interactive Masker | MediaPipe Interactive Segmenter | 바로 연결 후보 | 터치 기반 선택 보정/지우개 영역 지정에 적합. |
| Face/Portrait helper | MediaPipe Face Detection / Face Landmark / Selfie Segmentation | 바로 연결 후보 | 인물 보호, 피부톤 보호, 얼굴 영역 과보정 방지. |

### B. Build as Kepler-specific small models

| Slot | Candidate architecture | Use | Training target |
|---|---|---|---|
| Universal Auto Router | MobileNetV3-small / EfficientNet-Lite0 classifier | 장면 문제 감지 및 모델 선택 | normal, low_light, backlight, flare, reflection, portrait, landscape, jpeg_artifact 등 |
| Universal Balancer | MobileNet/EfficientNet-lite encoder + regression head | 자동 보정 파라미터 추정 | EditParams + LUT strength |
| Look Reasoner | Small image embedding + preset regressor | 색감/룩 추천 | warm/cool, filmic, high contrast, low saturation, portrait safe 등 |
| Specular Highlight Tamer | Small mask + inpaint-lite | 빛반사/번들거림 완화 | highlight mask + 주변 색/텍스처 복원 |

### C. Use big models as teacher, distill for mobile

| Slot | Teacher candidates | Mobile strategy | Notes |
|---|---|---|---|
| Flare Guard | Flare7K++ / flare removal research models | NAFNet-lite or U-Net-lite student, tile inference | 야간 광원 플레어 전용. 광원 자체는 보존하고 flare만 줄여야 함. |
| Glass Reflection Remover | IBCLN / reflection removal models | 고품질 export 전용부터, tile inference | 유리창 반사 제거는 실패 확률이 높으므로 수동 특수 기능으로 둔다. |
| Object & Dust Eraser | LaMa / MAT / ZITS++ | 작은 결함은 경량 모델, 큰 물체는 export 전용 | 먼지·작은 물체 제거부터 시작. |
| Detail Restorer Lite | NAFNet-lite / SwinIR-mini | INT8/FP16 student | 미리보기에서도 쓸 수 있는 가벼운 복원. |
| Detail Restorer HQ | Real-ESRGAN / SwinIR / Restormer | 느린 고품질 export 전용 | 레포에는 넣지 않고 로컬 모델로 관리. |
| JPEG Artifact Cleaner | SwinIR JPEG / FBCNN | 작은 residual CNN student | 메신저/웹 저장본 복원. |

## Full app slots

### Universal

- `universal_auto_router.tflite`: 장면 분석·모델 선택.
- `universal_balancer.tflite`: 전체 자동 리마스터.

### Masking

- `edge_masker.task`: 피사체·하늘·배경 분리.
- `interactive_masker.task`: 터치 기반 영역 선택.

### People and scenes

- `portrait_guard.tflite`: 인물·피부 보호 리마스터.
- `sky_balancer.tflite`: 하늘·구름·노을 보정.
- `low_light_cleaner.tflite`: 저조도 노이즈·톤 복원.
- `dehaze_reasoner.tflite`: 안개·흐림·역광 보정.
- `shadow_lifter.tflite`: 강한 그림자 완화.

### Removal and reflection

- `flare_guard.tflite`: 렌즈 플레어·강한 광원 번짐 제거.
- `glass_reflection_remover.tflite`: 유리창 반사 제거.
- `specular_highlight_tamer.tflite`: 빛반사·번들거림 완화.
- `object_dust_eraser.tflite`: 먼지·작은 물체 제거.

### Restoration and look

- `detail_restorer_lite.tflite`: 가벼운 디테일 복원.
- `detail_restorer_hq.tflite`: 고품질 디테일 복원·업스케일.
- `jpeg_artifact_cleaner.tflite`: 압축 노이즈 제거.
- `look_reasoner.tflite`: 색감·프리셋 추천.

## Runtime/OOM policy

- 앱에는 모델 후보를 많이 등록한다.
- 런타임에서는 active model을 하나만 유지한다.
- 새 모델을 선택하면 기존 모델 세션을 먼저 닫는다.
- 대형 모델은 preview가 아니라 export 전용으로 둔다.
- 대형 모델은 tile 기반 inference로 처리한다.
- 실제 모델 파일은 GitHub에 커밋하지 않는다.

## Implementation order

1. Add MediaPipe dependency and connect `edge_masker.task`.
2. Convert mask output to Bitmap/FloatArray mask.
3. Apply subject/background/sky-aware local EditParams or LUT.
4. Train or prototype `universal_balancer.tflite` from current algorithmic auto enhance and preset extraction outputs.
5. Build `flare_guard.tflite` as a small student from flare-removal teacher outputs.
6. Add actual close/unload logic for each model runtime when interpreter/task objects are introduced.
