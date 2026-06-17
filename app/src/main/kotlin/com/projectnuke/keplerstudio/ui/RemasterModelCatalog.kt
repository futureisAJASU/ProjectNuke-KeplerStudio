package com.projectnuke.keplerstudio.ui

data class RemasterModelCandidate(
    val id: String,
    val title: String,
    val role: String,
    val personality: String,
    val strengths: String,
    val cost: String,
    val status: String,
    val runtime: String,
    val assetPath: String,
    val memoryTier: String
)

val OnDeviceRemasterModels = listOf(
    RemasterModelCandidate(
        id = "edge_masker",
        title = "Edge Masker",
        role = "피사체·하늘·배경 분리",
        personality = "빠르고 안정적인 마스크 생성",
        strengths = "선택 보정의 기반 마스크를 만듭니다",
        cost = "낮음",
        status = "연결 예정",
        runtime = "MediaPipe Task",
        assetPath = "models/edge_masker.task",
        memoryTier = "S"
    ),
    RemasterModelCandidate(
        id = "interactive_masker",
        title = "Interactive Masker",
        role = "터치 기반 영역 선택",
        personality = "사용자가 찍은 지점을 중심으로 정교하게 선택",
        strengths = "복잡한 피사체를 수동 보정하기 좋습니다",
        cost = "낮음~중간",
        status = "후보",
        runtime = "MediaPipe Task",
        assetPath = "models/interactive_masker.task",
        memoryTier = "S"
    ),
    RemasterModelCandidate(
        id = "portrait_guard",
        title = "Portrait Guard",
        role = "인물·피부 보호 리마스터",
        personality = "피부톤을 과하게 망가뜨리지 않는 보수적 보정",
        strengths = "얼굴/피부 영역을 보호하고 배경만 따로 조정합니다",
        cost = "낮음~중간",
        status = "후보",
        runtime = "LiteRT",
        assetPath = "models/portrait_guard.tflite",
        memoryTier = "M"
    ),
    RemasterModelCandidate(
        id = "sky_balancer",
        title = "Sky Balancer",
        role = "하늘·구름·노을 보정",
        personality = "하늘은 살리고 피사체는 과장하지 않는 보정",
        strengths = "하늘 마스크와 톤 보정을 결합합니다",
        cost = "낮음~중간",
        status = "후보",
        runtime = "LiteRT",
        assetPath = "models/sky_balancer.tflite",
        memoryTier = "M"
    ),
    RemasterModelCandidate(
        id = "low_light_cleaner",
        title = "Low Light Cleaner",
        role = "저조도 노이즈·톤 복원",
        personality = "어두운 사진을 자연스럽게 살리는 야간 보정",
        strengths = "노출, 노이즈 감소, 로컬 대비를 함께 추정합니다",
        cost = "중간",
        status = "후보",
        runtime = "LiteRT",
        assetPath = "models/low_light_cleaner.tflite",
        memoryTier = "M"
    ),
    RemasterModelCandidate(
        id = "dehaze_reasoner",
        title = "Dehaze Reasoner",
        role = "안개·흐림·역광 보정",
        personality = "뿌연 사진의 깊이감을 되살리는 보정",
        strengths = "디헤이즈와 로컬 대비를 장면별로 추천합니다",
        cost = "중간",
        status = "후보",
        runtime = "LiteRT",
        assetPath = "models/dehaze_reasoner.tflite",
        memoryTier = "M"
    ),
    RemasterModelCandidate(
        id = "detail_restorer_light",
        title = "Detail Restorer Lite",
        role = "가벼운 디테일 복원",
        personality = "미리보기에서도 쓸 수 있는 경량 복원",
        strengths = "타일 기반 저강도 디테일 복원에 적합합니다",
        cost = "중간",
        status = "실험 후보",
        runtime = "LiteRT",
        assetPath = "models/detail_restorer_lite.tflite",
        memoryTier = "M"
    ),
    RemasterModelCandidate(
        id = "detail_restorer_hq",
        title = "Detail Restorer HQ",
        role = "고품질 디테일 복원·업스케일",
        personality = "느리지만 고품질 내보내기용",
        strengths = "SwinIR/Real-ESRGAN 계열 teacher 결과를 목표로 합니다",
        cost = "높음",
        status = "실험 후보",
        runtime = "LiteRT / Native",
        assetPath = "models/detail_restorer_hq.tflite",
        memoryTier = "L"
    ),
    RemasterModelCandidate(
        id = "jpeg_artifact_cleaner",
        title = "JPEG Artifact Cleaner",
        role = "압축 노이즈 제거",
        personality = "뭉개진 JPEG를 덜 지저분하게 정리",
        strengths = "메신저/웹 저장본 복원에 유리합니다",
        cost = "중간",
        status = "후보",
        runtime = "LiteRT",
        assetPath = "models/jpeg_artifact_cleaner.tflite",
        memoryTier = "M"
    ),
    RemasterModelCandidate(
        id = "look_reasoner",
        title = "Look Reasoner",
        role = "색감·프리셋 추천",
        personality = "사진 분위기를 읽고 자연스러운 룩을 제안",
        strengths = "이미지 임베딩과 통계 기반 프리셋 추정을 결합합니다",
        cost = "중간",
        status = "설계 중",
        runtime = "LiteRT",
        assetPath = "models/look_reasoner.tflite",
        memoryTier = "M"
    ),
    RemasterModelCandidate(
        id = "scene_quality_ranker",
        title = "Scene Quality Ranker",
        role = "사진 품질·문제 감지",
        personality = "흔들림, 노출 실패, 과채도, 저대비를 먼저 판단",
        strengths = "어떤 보정 엔진을 쓸지 선택하는 라우터 역할입니다",
        cost = "낮음",
        status = "후보",
        runtime = "LiteRT",
        assetPath = "models/scene_quality_ranker.tflite",
        memoryTier = "S"
    )
)
