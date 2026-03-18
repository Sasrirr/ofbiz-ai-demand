from __future__ import annotations

import logging
import os
import pathlib
import time
import uuid
from collections import defaultdict, deque
from typing import Deque, List

from fastapi import Depends, FastAPI, Header, HTTPException, Request, Response
from fastapi.responses import PlainTextResponse
from pydantic import BaseModel, Field, conint
from prometheus_client import CONTENT_TYPE_LATEST, Counter, Histogram, generate_latest

from model import DemandModel, load_model

BASE_DIR = pathlib.Path(__file__).resolve().parent.parent
DATA_BASE = pathlib.Path(os.getenv("AI_OFBIZ_BASE_DIR", BASE_DIR / "ofbiz-framework"))
MODEL_VERSION = os.getenv("AI_MODEL_VERSION", "0.1.0")
WINDOW_DAYS = int(os.getenv("AI_WINDOW_DAYS", "30"))
API_KEY = os.getenv("AI_API_KEY", "")
MAX_HORIZON_DAYS = int(os.getenv("AI_MAX_HORIZON_DAYS", "365"))
LOG_LEVEL = os.getenv("LOG_LEVEL", "INFO").upper()
RATE_LIMIT_PER_MIN = int(os.getenv("AI_RATE_LIMIT_PER_MIN", "0"))

logging.basicConfig(
    level=LOG_LEVEL,
    format="%(asctime)s %(levelname)s %(message)s",
)
logger = logging.getLogger("ai-demand")

REQUEST_COUNT = Counter("ai_requests_total", "Total API requests", ["path", "method", "status"])
REQUEST_LATENCY = Histogram("ai_request_latency_seconds", "Request latency", ["path", "method"])
RATE_BUCKETS: dict[str, Deque[float]] = defaultdict(deque)


class PredictRequest(BaseModel):
    product_id: str = Field(..., alias="product_id", min_length=1, max_length=50)
    horizon_days: conint(ge=1, le=MAX_HORIZON_DAYS) = 14


class PredictBatchRequest(BaseModel):
    product_ids: List[str] = Field(..., alias="product_ids", min_items=1)
    horizon_days: conint(ge=1, le=MAX_HORIZON_DAYS) = 14


class PredictResponse(BaseModel):
    product_id: str
    avg_daily: float
    total: float
    horizon_days: int
    interval_low: float
    interval_high: float
    history_days: int
    model_version: str
    data_start: str
    data_end: str
    on_hand: float
    available_to_promise: float
    min_stock: float
    reorder_qty: float
    stock_gap: float
    forecast_method: str


class PredictBatchResponse(BaseModel):
    results: List[PredictResponse]


class MetadataResponse(BaseModel):
    model_version: str
    data_version: str
    data_start: str
    data_end: str
    order_rows: int
    product_count: int
    window_days: int
    created_at: str


class EvaluationResponse(BaseModel):
    mae: float
    mape: float
    samples: int


app = FastAPI(title="AI Demand Service", version=MODEL_VERSION)


def require_api_key(x_api_key: str | None = Header(default=None)) -> None:
    if not API_KEY:
        return
    if not x_api_key or x_api_key != API_KEY:
        raise HTTPException(status_code=401, detail="Invalid API key")


@app.middleware("http")
async def request_context(request: Request, call_next):
    request_id = request.headers.get("X-Request-Id", str(uuid.uuid4()))
    start = time.time()
    if RATE_LIMIT_PER_MIN > 0:
        now = time.time()
        window_start = now - 60
        ip = request.client.host if request.client else "unknown"
        bucket = RATE_BUCKETS[ip]
        while bucket and bucket[0] < window_start:
            bucket.popleft()
        if len(bucket) >= RATE_LIMIT_PER_MIN:
            logger.warning("Rate limit exceeded ip=%s", ip)
            return PlainTextResponse("Rate limit exceeded", status_code=429)
        bucket.append(now)
    try:
        response = await call_next(request)
    finally:
        elapsed = time.time() - start
        REQUEST_LATENCY.labels(request.url.path, request.method).observe(elapsed)
    response.headers["X-Request-Id"] = request_id
    REQUEST_COUNT.labels(request.url.path, request.method, str(response.status_code)).inc()
    logger.info(
        "request_id=%s method=%s path=%s status=%s duration=%.3fs",
        request_id,
        request.method,
        request.url.path,
        response.status_code,
        elapsed,
    )
    return response


@app.on_event("startup")
def startup():
    global MODEL
    logger.info("Loading demand model from %s", DATA_BASE)
    try:
        MODEL = load_model(DATA_BASE, window=WINDOW_DAYS, model_version=MODEL_VERSION)
        logger.info("Model loaded. Products=%s", MODEL.metadata.product_count if MODEL.metadata else "unknown")
    except Exception as exc:
        logger.exception("Failed to load model: %s", exc)
        MODEL = None


@app.post("/predict-demand", response_model=PredictResponse, dependencies=[Depends(require_api_key)])
def predict(req: PredictRequest):
    if MODEL is None:
        raise HTTPException(status_code=503, detail="Model not available")
    result = MODEL.predict(req.product_id, req.horizon_days)
    return PredictResponse(**result)


@app.post("/predict-demand/batch", response_model=PredictBatchResponse, dependencies=[Depends(require_api_key)])
def predict_batch(req: PredictBatchRequest):
    if MODEL is None:
        raise HTTPException(status_code=503, detail="Model not available")
    results = MODEL.predict_batch(req.product_ids, req.horizon_days)
    return PredictBatchResponse(results=[PredictResponse(**item) for item in results])


@app.get("/metadata", response_model=MetadataResponse, dependencies=[Depends(require_api_key)])
def metadata():
    if MODEL is None or not MODEL.metadata:
        raise HTTPException(status_code=503, detail="Model metadata unavailable")
    return MetadataResponse(**MODEL.metadata.__dict__)


@app.post("/reload", dependencies=[Depends(require_api_key)])
def reload_model():
    global MODEL
    MODEL = load_model(DATA_BASE, window=WINDOW_DAYS, model_version=MODEL_VERSION)
    return {"status": "reloaded"}


@app.get("/evaluation", response_model=EvaluationResponse, dependencies=[Depends(require_api_key)])
def evaluation(eval_days: int = 30):
    if MODEL is None:
        raise HTTPException(status_code=503, detail="Model not available")
    result = MODEL.evaluate(eval_days=eval_days)
    return EvaluationResponse(**result)


@app.get("/health")
def health():
    healthy = MODEL is not None and MODEL.metadata is not None
    return {
        "status": "ok" if healthy else "degraded",
        "model_loaded": healthy,
    }


@app.get("/metrics")
def metrics():
    data = generate_latest()
    return Response(content=data, media_type=CONTENT_TYPE_LATEST)
