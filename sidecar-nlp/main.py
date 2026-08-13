"""KR-FinBERT 감성 스코어링 사이드카 (Phase 7에서 모델 탑재).

계약: POST /score {"texts": ["..."]} -> {"scores": [-1.0 ~ 1.0]}
Java 본체(newsintel 모듈)는 이 HTTP 계약만 의존한다.
"""
from fastapi import FastAPI
from pydantic import BaseModel

app = FastAPI(title="autostock-nlp")


class ScoreRequest(BaseModel):
    texts: list[str]


class ScoreResponse(BaseModel):
    scores: list[float]


@app.get("/health")
def health() -> dict:
    return {"status": "ok"}


@app.post("/score", response_model=ScoreResponse)
def score(req: ScoreRequest) -> ScoreResponse:
    # TODO Phase 7: KR-FinBERT (snunlp/KR-FinBert-SC) 로드 후 실제 스코어링
    return ScoreResponse(scores=[0.0 for _ in req.texts])
