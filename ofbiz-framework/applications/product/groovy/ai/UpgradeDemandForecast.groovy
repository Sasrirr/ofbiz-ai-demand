import org.apache.ofbiz.entity.util.EntityQuery
import org.apache.ofbiz.service.DispatchContext

Map upgradeDemandForecastDefaults(DispatchContext dctx, Map context) {
    def delegator = dctx?.delegator
    if (!delegator) return [responseMessage: "error", errorMessage: "No delegator available"]

    def forecasts = EntityQuery.use(delegator).from("DemandForecast").queryList()
    int updated = 0
    forecasts.each { df ->
        boolean changed = false
        if (df.intervalLow == null) { df.intervalLow = 0; changed = true }
        if (df.intervalHigh == null) { df.intervalHigh = 0; changed = true }
        if (df.historyDays == null) { df.historyDays = 0; changed = true }
        if (df.modelVersion == null) { df.modelVersion = "unknown"; changed = true }
        if (df.onHand == null) { df.onHand = 0; changed = true }
        if (df.availableToPromise == null) { df.availableToPromise = 0; changed = true }
        if (df.minStock == null) { df.minStock = 0; changed = true }
        if (df.reorderQty == null) { df.reorderQty = 0; changed = true }
        if (df.stockGap == null) { df.stockGap = 0; changed = true }
        if (df.forecastMethod == null) { df.forecastMethod = "rolling_mean"; changed = true }
        if (changed) {
            df.store()
            updated++
        }
    }
    return [responseMessage: "success", updated: updated]
}
