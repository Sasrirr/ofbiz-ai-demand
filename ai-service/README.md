AI Demand Service (FastAPI)
===========================

Purpose
- Standalone microservice to forecast demand from OFBiz exports (CSV), keeping AI decoupled from the ERP.
- Endpoint: `/predict-demand` returns a forecast for a product.

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
  -d '{"product_id": "WG-1111", "horizon_days": 14}'
```

Model approach (simple, transparent)
- Aggregate daily demand from order_lines by product_id.
- Baseline forecast: rolling mean of the last N days (default 30), or a linear regression over time if enough history.
- Returns: predicted average daily demand and total expected over the horizon.

Next steps (future)
- Add facility-aware forecasts using product_facility/inventory.
- Persist model artifacts and expose model metadata endpoint.
- Add confidence intervals and anomaly checks.