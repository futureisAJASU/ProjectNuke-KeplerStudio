package com.projectnuke.keplerstudio.ui

data class RemasterModelCandidate(
    val title: String,
    val role: String,
    val personality: String,
    val strengths: String,
    val cost: String,
    val status: String
)

val OnDeviceRemasterModels = listOf(
    RemasterModelCandidate(
        title = "Edge Masker",
        role = "피사체·하늘·배경 분리",
        personality = "빠르고 안정적인 마스크 생성",
        strengths = "MediaPipe 계열 세그멘테이션으로 선택 보정의 기반을 만듭니다",
        cost = "낮음",
        status = "연결 예정"
    ),
    RemasterModelCandidate(
        title = "Portrait Guard",
        role = "인물·피부 보호 리마스터",
        personality = "인물 피부톤을 과하게 망가뜨리지 않는 보수적 보정",
        strengths = "얼굴/피부 영역을 보호하고 배경 대비·디테일만 별도로 조정합니다",
        cost = "낮음~중간",
        status = "후보"
    ),
    RemasterModelCandidate(
        title = "Low Light Cleaner",
        role = "저조도 노이즈·톤 복원",
        personality = "어두운 사진을 자연스럽게 살리는 야간 보정",
        strengths = "노출, 노이즈 감소, 로컬 대비를 함께 추정합니다",
        cost = "중간",
        status = "후보"
    ),
    RemasterModelCandidate(
        title = "Detail Restorer",
        role = "디테일 복원·업스케일",
        personality = "느리지만 고품질 내보내기용",
        strengths = "SwinIR/Real-ESRGAN 계열을 teacher 또는 고품질 모드로 활용합니다",
        cost = "높음",
        status = "실험 후보"
    ),
    RemasterModelCandidate(
        title = "Look Reasoner",
        role = "색감·프리셋 추천",
        personality = "사진 분위기를 읽고 자연스러운 룩을 제안",
        strengths = "이미지 임베딩과 통계 기반 프리셋 추정을 결합합니다",
        cost = "중간",
        status = "설계 중"
    )
)
