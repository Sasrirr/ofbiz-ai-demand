import groovy.json.JsonSlurper
import org.apache.ofbiz.base.util.Debug
import org.apache.ofbiz.base.util.UtilDateTime
import org.apache.ofbiz.entity.condition.EntityCondition
import org.apache.ofbiz.entity.condition.EntityOperator
import org.apache.ofbiz.entity.util.EntityQuery
import org.apache.ofbiz.service.DispatchContext

import java.net.HttpURLConnection

Map getDemandForecastDashboard(DispatchContext dctx, Map context) {
    if (!dctx) return error("No DispatchContext provided")
    def delegator = dctx.delegator
    int evalDays = (context.evalDays ?: 30) as Integer

    def now = UtilDateTime.nowTimestamp()
    def since = UtilDateTime.addDaysToTimestamp(now, -30)

    long forecastCount = EntityQuery.use(delegator).from("DemandForecast").queryCount()
    def recent = EntityQuery.use(delegator)
            .from("DemandForecast")
            .where(EntityCondition.makeCondition("runDate", EntityOperator.GREATER_THAN_EQUAL_TO, since))
            .queryList()
    def last = EntityQuery.use(delegator).from("DemandForecast").orderBy("-runDate").queryFirst()

    double avgStockGap = 0
    double maxStockGap = 0
    if (recent && !recent.isEmpty()) {
        def gaps = recent.collect { (it.stockGap ?: 0) as Double }
        avgStockGap = gaps.sum() / gaps.size()
        maxStockGap = gaps.max()
    }

    def topGaps = EntityQuery.use(delegator)
            .from("DemandForecast")
            .orderBy("-stockGap")
            .queryList()
            .take(10)

    def evaluation = fetchEvaluation(evalDays)

    return [
            responseMessage: "success",
            forecastCount: forecastCount,
            lastRunDate: last?.runDate,
            avgStockGap: avgStockGap,
            maxStockGap: maxStockGap,
            evalDays: evalDays,
            evaluation: evaluation,
            topGaps: topGaps
    ]
}

private Map fetchEvaluation(int evalDays) {
    String baseUrl = System.getProperty("ai.demand.url", "http://localhost:8000")
    String apiKey = System.getProperty("ai.demand.apiKey", "")
    Integer timeoutMs = Integer.getInteger("ai.demand.timeoutMs", 5000)

    try {
        def url = new URL(baseUrl + "/evaluation?eval_days=" + evalDays)
        HttpURLConnection conn = (HttpURLConnection) url.openConnection()
        conn.setRequestMethod("GET")
        conn.setConnectTimeout(timeoutMs)
        conn.setReadTimeout(timeoutMs)
        if (apiKey) {
            conn.setRequestProperty("X-API-Key", apiKey)
        }
        int code = conn.responseCode
        if (code >= 300) {
            return [mae: 0, mape: 0, samples: 0, error: "AI service HTTP ${code}"]
        }
        def text = conn.inputStream.text
        def data = new JsonSlurper().parseText(text)
        return data
    } catch (Throwable t) {
        Debug.logWarning("Failed to fetch evaluation: ${t.message}", "AI_DEMAND")
        return [mae: 0, mape: 0, samples: 0, error: "unavailable"]
    }
}

private Map error(String msg) {
    return [responseMessage: "error", errorMessage: msg]
}
