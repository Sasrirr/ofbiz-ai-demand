AI Demand Service (FastAPI)
===========================

Purpose
- Standalone microservice to forecast demand from OFBiz exports (CSV), keeping AI decoupled from the ERP.
- Endpoint: `/predict-demand` returns a forecast for a product.
 - Production endpoints: `/predict-demand`, `/predict-demand/batch`, `/metadata`, `/metrics`.

Data inputs (expected paths)
- `../ofbiz-framework/runtime/data/export/order_lines.csv`
- `../ofbiz-framework/runtime/data/export/products.csv`
- `../ofbiz-framework/runtime/data/export/product_facility.csv`
- `../ofbiz-framework/runtime/data/export/inventory_items.csv`

Quick start
1) Create/activate a venv (Python 3.10+ recommended).
2) `pip install -r requirements.txt`
3) `uvicorn main:app --reload --port 8000`

Usage example (after server is running)
```
curl -X POST http://localhost:8000/predict-demand \
  -H "Content-Type: application/json" \
  -H "X-API-Key: <your-key>" \
  -d '{"product_id": "WG-1111", "horizon_days": 14}'
```

Batch usage
```
curl -X POST http://localhost:8000/predict-demand/batch \
  -H "Content-Type: application/json" \
  -H "X-API-Key: <your-key>" \
  -d '{"product_ids": ["WG-1111", "WG-1112"], "horizon_days": 14}'
```

Configuration (env vars)
- `AI_OFBIZ_BASE_DIR` (default: `../ofbiz-framework`)
- `AI_WINDOW_DAYS` (default: 30)
- `AI_MAX_HORIZON_DAYS` (default: 365)
- `AI_MODEL_VERSION` (default: 0.1.0)
- `AI_API_KEY` (optional; if set, required via `X-API-Key`)
- `AI_RATE_LIMIT_PER_MIN` (optional; if set > 0, rate limit per IP)

Evaluation
```
curl -X GET "http://localhost:8000/evaluation?eval_days=30" \
  -H "X-API-Key: <your-key>"
```

Model approach (simple, transparent)
- Aggregate daily demand from order_lines by product_id.
- Baseline forecast: rolling mean of the last N days (default 30), or a linear regression over time if enough history.
- Returns: predicted average daily demand and total expected over the horizon.

Next steps (future)
- Add facility-aware forecasts using product_facility/inventory.
- Persist model artifacts and expose model metadata endpoint.
- Add confidence intervals and anomaly checks.
