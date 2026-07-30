package com.projectnuke.keplerstudio.editor

enum class NoiseEngine(
    val label: String,
    val nativeId: Int
) {
    FastEdgeAware("빠름", 0),
    GuidedFilter("엣지 보존", 1),
    NonLocalMeansLite("비로컬 평균 Lite · 실험", 2),
    ModelDenoise("모델 기반 준비 중", 3)
}

enum class DetailEngine(
    val label: String,
    val nativeId: Int
) {
    MaskedUnsharp("자연스러운 샤픈", 0),
    LocalLaplacian("로컬 라플라시안 준비 중", 1),
    MultiLayerLaplacian("멀티스케일 라플라시안 · 실험", 2),
    DiffuseSharpen("Diffuse Sharpen 준비 중", 3)
}

enum class ToneEngine(
    val label: String,
    val nativeId: Int
) {
    HistogramAuto("히스토그램", 0),
    Clahe("CLAHE", 1),
    Filmic("필믹 톤", 2),
    Sigmoid("시그모이드 톤", 3),
    AgxLike("AgX 유사 준비 중", 4),
    LocalToneMap("로컬 톤맵 준비 중", 5)
}

enum class DehazeEngine(
    val label: String,
    val nativeId: Int
) {
    FastContrast("빠른 디헤이즈", 0),
    DarkChannelPrior("다크 채널 사전(DCP) · 실험", 1),
    PyramidFusionDcp("다중 스케일 DCP · 실험", 2),
    ModelDehaze("모델 기반 준비 중", 3)
}
