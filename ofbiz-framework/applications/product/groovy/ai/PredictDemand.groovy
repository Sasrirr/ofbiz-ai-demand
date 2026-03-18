import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import org.apache.ofbiz.entity.Delegator
import org.apache.ofbiz.service.DispatchContext
import org.apache.ofbiz.base.util.UtilDateTime

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
    List results = []
    productIds.each { pid ->
        results << doPredict(dctx, [productId: pid, horizonDays: horizonDays])
    }
    return [responseMessage: "success", results: results]
}

private Map doPredict(DispatchContext dctx, Map context) {
    if (!dctx) return error("No DispatchContext provided")
    Delegator delegator = dctx.delegator
    String productId = context.productId
    Integer horizonDays = (context.horizonDays ?: 14) as Integer
    if (!productId) return error("Missing productId")

    def payload = [product_id: productId, horizon_days: horizonDays]
    def url = new URL("http://localhost:8000/predict-demand")
    def conn = url.openConnection()
    conn.setRequestMethod("POST")
    conn.setDoOutput(true)
    conn.setRequestProperty("Content-Type", "application/json")
    conn.outputStream.withWriter("UTF-8") { it << JsonOutput.toJson(payload) }

    if (conn.responseCode >= 300) {
        return error("AI service HTTP ${conn.responseCode}")
    }
    def text = conn.inputStream.text
    def data = new JsonSlurper().parseText(text)

    def forecastId = delegator.getNextSeqId("DemandForecast")
    def runDate = UtilDateTime.nowTimestamp()
    delegator.create("DemandForecast", [
            forecastId: forecastId,
            productId : productId,
            horizonDays: horizonDays,
            avgDaily  : data.avg_daily,
            total     : data.total,
            runDate   : runDate,
            source    : "AI"
    ])

    return [responseMessage: "success", forecastId: forecastId, avgDaily: data.avg_daily, total: data.total, runDate: runDate]
}

private Map error(String msg) {
    return [responseMessage: "error", errorMessage: msg]
}