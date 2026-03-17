from __future__ import annotations

import pathlib
from fastapi import FastAPI, HTTPException
from pydantic import BaseModel, Field

from model import load_model, DemandModel

BASE_DIR = pathlib.Path(__file__).resolve().parent.parent  # points to e:\FSD\ai-ofbiz
DATA_BASE = BASE_DIR / "ofbiz-framework"

class PredictRequest(BaseModel):
    product_id: str = Field(..., alias="product_id")
    horizon_days: int = 14

class PredictResponse(BaseModel):
    product_id: str
    avg_daily: float
    total: float
    horizon_days: int

app = FastAPI(title="AI Demand Service", version="0.1.0")

@app.on_event("startup")
def startup():
    global MODEL
    MODEL = load_model(DATA_BASE, window=30)

@app.post("/predict-demand", response_model=PredictResponse)
def predict(req: PredictRequest):
    result = MODEL.predict(req.product_id, req.horizon_days)
    return PredictResponse(**result)

@app.get("/health")
def health():
    return {"status": "ok"}