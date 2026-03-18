import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import org.apache.ofbiz.entity.Delegator
import org.apache.ofbiz.service.DispatchContext
import org.apache.ofbiz.base.util.UtilDateTime
import org.apache.ofbiz.base.util.Debug
import java.net.HttpURLConnection
import java.sql.Date

// Overloads to be resilient when dispatcher passes different arg lists
Map predictDemandForProduct(DispatchContext dctx, Map context) {
    return doPredict(dctx, context)
}

Map predictDemandForProduct(Map context) {
    DispatchContext dctx = context?.dispatcher?.dispatchContext ?: context?.dctx
    return doPredict(dctx, context)
}

Map predictDemandForProducts(DispatchContext dctx, Map context) {
    List productIds = context.productIds ?: []
    Integer horizonDays = (context.horizonDays ?: 14) as Integer
    if (productIds.isEmpty()) {
        return [responseMessage: "error", errorMessage: "No productIds provided"]
    }

    Integer batchSize = Integer.getInteger("ai.demand.batchSize", 50)
    List results = []
    productIds.collate(batchSize).each { chunk ->
        def payload = [product_ids: chunk, horizon_days: horizonDays]
        def apiRes = callAiApi("/predict-demand/batch", payload)
        if (!apiRes.success) {
            Debug.logWarning("AI batch failed, falling back to cached forecasts: ${apiRes.error}", "AI_DEMAND")
            chunk.each { pid ->
                def cached = getLatestForecast(dctx, pid, horizonDays)
                if (cached) {
                    results << cached + [cached: true]
                } else {
                    results << [productId: pid, error: apiRes.error]
                }
            }
            return
        }
        apiRes.data?.results?.each { item ->
            results << persistForecast(dctx, item)
        }
    }
    return [responseMessage: "success", results: results]
}

Map predictDemandForAllProducts(DispatchContext dctx, Map context) {
    Integer horizonDays = (context.horizonDays ?: 14) as Integer
    Integer maxProducts = (context.maxProducts ?: 500) as Integer
    if (!dctx) return error("No DispatchContext provided")
    Delegator delegator = dctx.delegator
    def now = UtilDateTime.nowTimestamp()

    def products = org.apache.ofbiz.entity.util.EntityQuery.use(delegator)
            .from("Product")
            .where("isVirtual", "N")
            .queryList()
            .findAll { p ->
                !p.salesDiscontinuationDate || p.salesDiscontinuationDate.after(now)
            }
            .take(maxProducts)
            .collect { it.productId }

    if (products.isEmpty()) {
        return error("No products found to forecast")
    }
    return predictDemandForProducts(dctx, [productIds: products, horizonDays: horizonDays])
}

Map enqueueDemandForecasts(DispatchContext dctx, Map context) {
    if (!dctx) return error("No DispatchContext provided")
    def dispatcher = dctx.dispatcher
    Integer horizonDays = (context.horizonDays ?: 14) as Integer
    Integer maxProducts = (context.maxProducts ?: 500) as Integer
    Map payload = [horizonDays: horizonDays, maxProducts: maxProducts, userLogin: context.userLogin]
    dispatcher.runAsync("predictDemandForAllProducts", payload)
    return [responseMessage: "success", message: "Forecast job queued"]
}

Map enqueueDemandForecastsPersistent(DispatchContext dctx, Map context) {
    if (!dctx) return error("No DispatchContext provided")
    def dispatcher = dctx.dispatcher
    def userLogin = context.userLogin
    if (!userLogin) return error("Missing userLogin")

    Integer horizonDays = (context.horizonDays ?: 14) as Integer
    Integer maxProducts = (context.maxProducts ?: 500) as Integer
    Map payload = [horizonDays: horizonDays, maxProducts: maxProducts, userLogin: userLogin]
    def res = dispatcher.runAsync("predictDemandForAllProducts", payload, true)
    return [responseMessage: "success", jobId: res?.jobId]
}

private Map doPredict(DispatchContext dctx, Map context) {
    if (!dctx) return error("No DispatchContext provided")
    Delegator delegator = dctx.delegator
    String productId = context.productId
    Integer horizonDays = (context.horizonDays ?: 14) as Integer
    if (!productId) return error("Missing productId")

    def payload = [product_id: productId, horizon_days: horizonDays]
    def apiRes = callAiApi("/predict-demand", payload)
    if (!apiRes.success) {
        Debug.logWarning("AI request failed, falling back to cached forecast: ${apiRes.error}", "AI_DEMAND")
        def cached = getLatestForecast(dctx, productId, horizonDays)
        if (cached) {
            return [responseMessage: "success", cached: true] + cached
        }
        return error(apiRes.error)
    }
    def result = persistForecast(dctx, apiRes.data)
    result.horizonDays = horizonDays
    return [responseMessage: "success"] + result
}

private Map error(String msg) {
    return [responseMessage: "error", errorMessage: msg]
}

private Map getLatestForecast(DispatchContext dctx, String productId, Integer horizonDays) {
    def delegator = dctx.delegator
    def list = org.apache.ofbiz.entity.util.EntityQuery.use(delegator)
            .from("DemandForecast")
            .where("productId", productId)
            .orderBy("-runDate")
            .queryList()
    if (!list || list.isEmpty()) return null
    def df = list.get(0)
    return [
            forecastId: df.forecastId,
            productId: df.productId,
            avgDaily: df.avgDaily,
            total: df.total,
            runDate: df.runDate,
            horizonDays: horizonDays
    ]
}

private void maybeSendAlertWebhook(Map payload) {
    String webhook = System.getProperty("ai.demand.alertWebhook", "")
    if (!webhook) return
    Double stockGapThreshold = Double.valueOf(System.getProperty("ai.demand.alertStockGap", "1"))
    Double totalThreshold = Double.valueOf(System.getProperty("ai.demand.alertTotalThreshold", "0"))
    Double intervalHighThreshold = Double.valueOf(System.getProperty("ai.demand.alertIntervalHighThreshold", "0"))

    boolean shouldAlert = false
    if (payload.stockGap != null && payload.stockGap >= stockGapThreshold) shouldAlert = true
    if (totalThreshold > 0 && payload.total >= totalThreshold) shouldAlert = true
    if (intervalHighThreshold > 0 && payload.intervalHigh >= intervalHighThreshold) shouldAlert = true
    if (!shouldAlert) return

    try {
        def url = new URL(webhook)
        HttpURLConnection conn = (HttpURLConnection) url.openConnection()
        conn.setRequestMethod("POST")
        conn.setDoOutput(true)
        conn.setConnectTimeout(4000)
        conn.setReadTimeout(4000)
        conn.setRequestProperty("Content-Type", "application/json")
        conn.outputStream.withWriter("UTF-8") { it << JsonOutput.toJson(payload) }
        conn.responseCode
    } catch (Throwable ignored) {
        // best-effort webhook
    }
}

private Map callAiApi(String path, Map payload) {
    String baseUrl = System.getProperty("ai.demand.url", "http://localhost:8000")
    String apiKey = System.getProperty("ai.demand.apiKey", "")
    Integer timeoutMs = Integer.getInteger("ai.demand.timeoutMs", 5000)

    def url = new URL(baseUrl + path)
    HttpURLConnection conn = (HttpURLConnection) url.openConnection()
    conn.setRequestMethod("POST")
    conn.setDoOutput(true)
    conn.setConnectTimeout(timeoutMs)
    conn.setReadTimeout(timeoutMs)
    conn.setRequestProperty("Content-Type", "application/json")
    if (apiKey) {
        conn.setRequestProperty("X-API-Key", apiKey)
    }
    conn.outputStream.withWriter("UTF-8") { it << JsonOutput.toJson(payload) }

    int code = conn.responseCode
    if (code >= 300) {
        return [success: false, error: "AI service HTTP ${code}"]
    }
    def text = conn.inputStream.text
    def data = new JsonSlurper().parseText(text)
    return [success: true, data: data]
}

private Map persistForecast(DispatchContext dctx, Map data) {
    Delegator delegator = dctx.delegator
    def forecastId = delegator.getNextSeqId("DemandForecast")
    def runDate = UtilDateTime.nowTimestamp()
    def dataStart = parseDate(data.data_start)
    def dataEnd = parseDate(data.data_end)

    delegator.create("DemandForecast", [
            forecastId   : forecastId,
            productId    : data.product_id,
            horizonDays  : data.horizon_days,
            avgDaily     : data.avg_daily,
            total        : data.total,
            intervalLow  : data.interval_low,
            intervalHigh : data.interval_high,
            historyDays  : data.history_days,
            modelVersion : data.model_version,
            dataStart    : dataStart,
            dataEnd      : dataEnd,
            onHand       : data.on_hand,
            availableToPromise: data.available_to_promise,
            minStock     : data.min_stock,
            reorderQty   : data.reorder_qty,
            stockGap     : data.stock_gap,
            forecastMethod: data.forecast_method,
            runDate      : runDate,
            source       : "AI"
    ])

    Debug.logInfo("Stored demand forecast for ${data.product_id} horizon=${data.horizon_days}", "AI_DEMAND")

    maybeSendAlertWebhook([
            productId: data.product_id,
            total: data.total,
            stockGap: data.stock_gap,
            intervalHigh: data.interval_high,
            modelVersion: data.model_version,
            horizonDays: data.horizon_days
    ])

    return [
            forecastId: forecastId,
            avgDaily  : data.avg_daily,
            total     : data.total,
            runDate   : runDate
    ]
}

private static java.sql.Timestamp parseDate(Object value) {
    if (!value) return null
    try {
        def d = Date.valueOf(value.toString())
        return UtilDateTime.toTimestamp(d)
    } catch (Throwable ignored) {
        return null
    }
}
