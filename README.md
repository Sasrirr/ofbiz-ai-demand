# OFBiz AI Demand Forecasting

This repo integrates Apache OFBiz with a production‑ready AI demand forecasting service. It includes:

- OFBiz export services to generate CSVs from order, product, facility, and inventory data
- A FastAPI microservice that forecasts demand with confidence intervals and inventory context
- OFBiz services that call the AI service and store results in `DemandForecast`
- Scheduled jobs, UI screens, dashboards, and alerting hooks

## Architecture
1. OFBiz exports data to `ofbiz-framework/runtime/data/export/*.csv`
2. AI service loads exports and serves forecasts via HTTP
3. OFBiz calls the AI service, stores forecasts, and exposes UI + dashboard

## Quick Start (Dev)
1. Start OFBiz (see OFBiz README for full setup).
2. Export data:
   - `gradlew "ofbiz --script=runtime/script/exportDemandData.groovy"`
3. Run AI service:
   - `cd ai-service`
   - `pip install -r requirements.txt`
   - `uvicorn main:app --reload --port 8000`
4. Forecast:
   - `gradlew "ofbiz --script=runPredictDemand.groovy" -DproductId=WG-1111 -DhorizonDays=14`

## Production Quick Start
1. Copy `.env.example` to `.env` and set `AI_API_KEY`.
2. Run:
   - `docker-compose up --build`
3. Configure OFBiz JVM properties:
   - `-Dai.demand.url=http://ai-demand:8000`
   - `-Dai.demand.apiKey=<your-key>`
   - `-Dai.demand.timeoutMs=8000`

## Render Deployment
Use Render for the FastAPI `ai-service`, not the full OFBiz runtime.

1. Push this repo to GitHub/GitLab.
2. In Render, create a Blueprint from the repo so Render reads [`render.yaml`](./render.yaml).
3. When prompted, set `AI_API_KEY`.
4. After the service is created, open the attached disk shell path and make sure these files exist under `/data/ofbiz/runtime/data/export/`:
   - `order_lines.csv`
   - `products.csv`
   - `product_facility.csv`
   - `inventory_items.csv`
5. Point OFBiz at Render:
   - `-Dai.demand.url=https://<your-render-service>.onrender.com`
   - `-Dai.demand.apiKey=<your-key>`
   - `-Dai.demand.timeoutMs=8000`

Important notes:
- Render persistent disks are only available on paid instances, so the Blueprint uses `starter`.
- The service starts even before data is uploaded, but `/predict-demand` returns `503` until exports are present and the model is loaded.
- After uploading fresh CSV exports, call `POST /reload` with `X-API-Key` so the service reloads the model without a redeploy.

## UI
Catalog app menu:
- `Demand Forecasts`: `/catalog/control/demandForecasts`
- `Forecast Dashboard`: `/catalog/control/demandForecastDashboard`

## Scheduled Jobs
Seed‑initial jobs:
- `exportDemandDataDelta` (daily)
- `predictDemandForAllProducts` (daily)

## Docs
See `docs/production.md` for full production guidance and migration steps.

## Complete Usage + Deployment Guide

This section is a full, end‑to‑end guide to make the project work in dev or production.

### 1) Prerequisites
- JDK 17 (for OFBiz)
- Python 3.11+ (for AI service)
- Docker + Docker Compose (production or local container run)
- A database supported by OFBiz (PostgreSQL/MySQL/etc.)

### 2) OFBiz Setup (First‑Time)
Follow the official OFBiz setup steps from `ofbiz-framework/README.adoc`. At minimum:
1. Initialize Gradle wrapper:
   - Windows: `init-gradle-wrapper`
2. Load seed data:
   - `gradlew cleanAll loadAll`
3. Start OFBiz:
   - `gradlew ofbiz`

### 3) Export Data from OFBiz
Exports land in `ofbiz-framework/runtime/data/export/`.

Full export:
```
gradlew "ofbiz --script=runtime/script/exportDemandData.groovy"
```

Delta export (default `daysBack=1`):
```
gradlew "ofbiz --script=runtime/script/exportDemandData.groovy" -DdaysBack=1
```

### 4) Run AI Service (Dev)
```
cd ai-service
pip install -r requirements.txt
uvicorn main:app --reload --port 8000
```

### 5) Configure OFBiz → AI Service
Set JVM properties when starting OFBiz:
- `-Dai.demand.url=http://localhost:8000`
- `-Dai.demand.apiKey=<your-key>`
- `-Dai.demand.timeoutMs=8000`
- `-Dai.demand.batchSize=50`

### 6) Run Forecasts
Single product:
```
gradlew "ofbiz --script=runPredictDemand.groovy" -DproductId=WG-1111 -DhorizonDays=14
```

Batch (all products, queued):
```
gradlew "ofbiz --script=runtime/script/upgradeDemandForecast.groovy"
```

Or from UI:
- Catalog app → `Demand Forecasts`
- Click `Queue Forecasts`

### 7) View Results
Catalog UI routes:
- Forecasts: `/catalog/control/demandForecasts`
- Dashboard: `/catalog/control/demandForecastDashboard`

### 8) Production Deployment (Recommended)
1. Create `.env` from `.env.example` and set `AI_API_KEY`.
2. Run AI service:
```
docker-compose up --build
```
3. Configure OFBiz JVM properties:
   - `-Dai.demand.url=http://ai-demand:8000`
   - `-Dai.demand.apiKey=<your-key>`
   - `-Dai.demand.timeoutMs=8000`
4. Enable HTTPS/mTLS between OFBiz and AI service in your infrastructure.

### 9) Database Migration
Apply schema updates for new `DemandForecast` fields. Then run:
```
gradlew "ofbiz --script=runtime/script/upgradeDemandForecast.groovy"
```

### 10) Observability & Health
AI service:
- `/health`
- `/metadata`
- `/metrics`
- `/evaluation?eval_days=30`

OFBiz logs:
- Export + forecast actions log under `AI_DEMAND` category.

### 11) Scheduled Jobs
Seeded jobs run nightly:
- `exportDemandDataDelta` (midnight)
- `predictDemandForAllProducts` (00:30)

### 12) Alerting
Enable webhook alerts via JVM properties:
- `-Dai.demand.alertWebhook=https://your-webhook`
- `-Dai.demand.alertStockGap=1`
- `-Dai.demand.alertTotalThreshold=0`
- `-Dai.demand.alertIntervalHighThreshold=0`

### 13) Common Failure Points
- Missing schema migration → forecast writes fail.
- AI service down → OFBiz falls back to cached forecasts.
- Wrong API key → 401 errors.
- No exports → AI service loads empty data.
