# Kepler Studio MVP 0.1 Skeleton

목표:
- Kotlin/Compose UI
- 갤러리 이미지 import
- 2048px 저해상도 preview 생성
- C++/NDK PhotoCore에서 preview 픽셀 보정
- zoom/pan viewport 상태 수집
- v0.2에서 ROI tile renderer로 확장

주의:
- 현재 v0.1은 native ROI/full-res tile renderer 전 단계의 골격이다.
- Kotlin에서 preview bitmap을 들고 있지만, full-res 처리는 아직 하지 않는다.
- 실제 RAW/HEIF/ROI 처리는 native decoder + tile cache로 분리해야 한다.

다음 단계:
1. EditSession을 native에 실제로 연결
2. TileKey/TileCache 추가
3. ViewportState -> 원본 이미지 좌표계 변환
4. ROI render request queue 추가
5. ExportJob 추가


빌드 참고:
- AGP 9.x는 built-in Kotlin을 사용하므로 `org.jetbrains.kotlin.android` 플러그인을 적용하지 않는다.
- Compose 모듈에는 `org.jetbrains.kotlin.plugin.compose`만 별도로 적용한다.
