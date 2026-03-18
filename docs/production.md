# Production Checklist

This document summarizes how to run the OFBiz + AI demand stack in production.

## AI Service
1. Build and run:
   - `docker-compose up --build`
2. Set `AI_API_KEY` in `.env` and pass it to OFBiz.
3. Ensure `AI_OFBIZ_BASE_DIR` points to the OFBiz export directory.
4. Optional: set `AI_RATE_LIMIT_PER_MIN` to protect the API.

## Render
Use Render to deploy the AI service as a Docker web service with a persistent disk.

1. Connect the repository in Render and deploy the Blueprint from [`render.yaml`](../render.yaml).
2. Provide `AI_API_KEY` when Render prompts for unsynced environment variables.
3. Keep the disk mounted at `/data/ofbiz`, which means OFBiz exports must be uploaded to:
   - `/data/ofbiz/runtime/data/export/order_lines.csv`
   - `/data/ofbiz/runtime/data/export/products.csv`
   - `/data/ofbiz/runtime/data/export/product_facility.csv`
   - `/data/ofbiz/runtime/data/export/inventory_items.csv`
4. Set OFBiz JVM properties to the public Render URL:
   - `-Dai.demand.url=https://<service-name>.onrender.com`
   - `-Dai.demand.apiKey=<your-key>`
5. After refreshing export CSVs on the mounted disk, call `POST /reload` with `X-API-Key` to refresh the model.

Notes:
- Persistent disks require a paid Render web service.
- The health endpoint returns `degraded` until export data is present, which is expected on first deploy.

## OFBiz Configuration
Set JVM properties (examples):
- `-Dai.demand.url=http://ai-demand:8000`
- `-Dai.demand.apiKey=<your-key>`
- `-Dai.demand.timeoutMs=8000`

## Scheduled Jobs
The following are scheduled in seed-initial data:
- `exportDemandDataDelta` (daily midnight, default daysBack=1)
- `predictDemandForAllProducts` (daily 00:30)
Optional async entrypoint:
- `enqueueDemandForecasts` (use this to queue forecast runs without blocking)
- `enqueueDemandForecastsPersistent` (creates JobSandbox entry; survives restart)

## UI
The Catalog app includes a new menu entry: `Demand Forecasts` (route: `/catalog/control/demandForecasts`), listing recent forecasts.

## Alerting
Set JVM properties to enable webhooks:
- `-Dai.demand.alertWebhook=https://your-webhook`
- `-Dai.demand.alertStockGap=1`
- `-Dai.demand.alertTotalThreshold=0`
- `-Dai.demand.alertIntervalHighThreshold=0`

Webhook payload includes `productId`, `total`, `stockGap`, `intervalHigh`, `modelVersion`, `horizonDays`.

## Migration
Run the migration service after applying schema updates:
- Service: `upgradeDemandForecastDefaults`
- Script: `gradlew "ofbiz --script=runtime/script/upgradeDemandForecast.groovy"`

## Tuning
JVM properties:
- `-Dai.demand.batchSize=50` (batch size for API calls)

## External Requirements (Not In Repo)
1. Database schema migrations for new `DemandForecast` fields across your DB engine.
2. Secrets management for `AI_API_KEY` and webhook URLs.
3. TLS/mTLS between OFBiz and the AI service.
4. HA deployment strategy (load balancers, scaling, backups).
5. Monitoring/alerting infrastructure for log aggregation and metrics scraping.

## Data Export Outputs
Files written to `runtime/data/export`:
- `order_lines.csv`
- `products.csv`
- `product_facility.csv`
- `inventory_items.csv`

## Observability
- AI service metrics: `GET /metrics`
- Health: `GET /health`
- Metadata: `GET /metadata`
- Evaluation: `GET /evaluation?eval_days=30`

## Notes
1. Apply schema updates for new `DemandForecast` fields.
2. Consider configuring `maxProducts` when running batch forecasts.
