package com.projectnuke.keplerstudio.editor

enum class NoiseEngine(
    val label: String,
    val nativeId: Int
) {
    FastEdgeAware("빠름", 0),
    GuidedFilter("엣지 보존", 1),
    NonLocalMeansLite("고품질 실험", 2),
    ModelDenoise("모델 기반 준비 중", 3)
}

enum class DetailEngine(
    val label: String,
    val nativeId: Int
) {
    MaskedUnsharp("자연스러운 샤픈", 0),
    LocalLaplacian("로컬 라플라시안 준비 중", 1),
    MultiLayerLaplacian("멀티 레이어 준비 중", 2),
    DiffuseSharpen("Diffuse Sharpen 준비 중", 3)
}

enum class ToneEngine(
    val label: String,
    val nativeId: Int
) {
    HistogramAuto("히스토그램", 0),
    Clahe("CLAHE", 1),
    Filmic("필믹 준비 중", 2),
    Sigmoid("시그모이드 준비 중", 3),
    AgxLike("AgX 유사 준비 중", 4),
    LocalToneMap("로컬 톤맵 준비 중", 5)
}

enum class DehazeEngine(
    val label: String,
    val nativeId: Int
) {
    FastContrast("빠른 디헤이즈", 0),
    DarkChannelPrior("DCP 준비 중", 1),
    PyramidFusionDcp("다중 스케일 DCP 준비 중", 2),
    ModelDehaze("모델 기반 준비 중", 3)
}
