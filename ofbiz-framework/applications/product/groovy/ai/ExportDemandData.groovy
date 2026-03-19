import org.apache.ofbiz.entity.util.EntityQuery
import org.apache.ofbiz.entity.condition.EntityCondition
import org.apache.ofbiz.entity.condition.EntityOperator
import org.apache.ofbiz.service.DispatchContext
import org.apache.ofbiz.base.util.UtilDateTime
import org.apache.ofbiz.base.util.Debug

import java.time.format.DateTimeFormatter
import java.sql.Timestamp

Map exportDemandDataBundle() {
    return exportDemandDataBundle(resolveDispatchContext(), resolveServiceContext())
}

Map exportDemandDataBundle(DispatchContext dctx, Map context) {
    def res1 = exportOrderLinesForDemand(dctx, context)
    if (res1.responseMessage != "success") return res1
    def res2 = exportProductsForDemand(dctx, context)
    if (res2.responseMessage != "success") return res2
    def res3 = exportProductFacilityForDemand(dctx, context)
    if (res3.responseMessage != "success") return res3
    def res4 = exportInventoryItemsForDemand(dctx, context)
    if (res4.responseMessage != "success") return res4
    return [responseMessage: "success"]
}

Map exportDemandDataDelta() {
    return exportDemandDataDelta(resolveDispatchContext(), resolveServiceContext())
}

Map exportDemandDataDelta(DispatchContext dctx, Map context) {
    Integer daysBack = (context.daysBack ?: 1) as Integer
    def ctx = [daysBack: daysBack]
    return exportDemandDataBundle(dctx, ctx)
}

Map exportOrderLinesForDemand() {
    return exportOrderLinesForDemand(resolveDispatchContext(), resolveServiceContext())
}

Map exportOrderLinesForDemand(DispatchContext dctx, Map context) {
    def delegator = dctx?.delegator
    if (!delegator) return [responseMessage: "error", errorMessage: "No delegator available"]

    def statusId = context.statusId
    Timestamp fromDate = parseDate(context.fromDate)
    Timestamp toDate = parseDate(context.toDate)
    Integer daysBack = (context.daysBack ?: 0) as Integer
    if (!fromDate && daysBack > 0) {
        fromDate = UtilDateTime.addDaysToTimestamp(UtilDateTime.nowTimestamp(), -daysBack)
    }
    List conditions = []
    if (statusId) {
        conditions << EntityCondition.makeCondition("statusId", EntityOperator.EQUALS, statusId)
    }
    if (fromDate) {
        conditions << EntityCondition.makeCondition("orderDate", EntityOperator.GREATER_THAN_EQUAL_TO, fromDate)
    }
    if (toDate) {
        conditions << EntityCondition.makeCondition("orderDate", EntityOperator.LESS_THAN_EQUAL_TO, toDate)
    }

    def query = EntityQuery.use(delegator)
            .from("OrderHeader")
            .orderBy("orderDate")
    if (!conditions.isEmpty()) {
        query = query.where(conditions)
    }

    def headers = query.queryList()

    List<List<String>> rows = []
    headers.each { oh ->
        EntityQuery.use(delegator)
                .from("OrderItem")
                .where("orderId", oh.orderId)
                .queryList()
                .each { oi ->
                    def facilityId = ""
                    def shipGrpAssoc = EntityQuery.use(delegator)
                            .from("OrderItemShipGroupAssoc")
                            .where("orderId", oi.orderId, "orderItemSeqId", oi.orderItemSeqId)
                            .queryFirst()
                    if (shipGrpAssoc) {
                        def shipGrp = EntityQuery.use(delegator)
                                .from("OrderItemShipGroup")
                                .where("orderId", oi.orderId, "shipGroupSeqId", shipGrpAssoc.shipGroupSeqId)
                                .queryFirst()
                        if (shipGrp) {
                            facilityId = shipGrp.facilityId ?: ""
                        }
                    }
                    rows << [
                            oi.orderId,
                            formatDate(oh.orderDate),
                            oi.productId ?: "",
                            sanitizeQuantity(oi.quantity),
                            facilityId
                    ]
                }
    }

    writeCsv("order_lines.csv", ["orderId", "orderDate", "productId", "quantity", "facilityId"], rows)
    Debug.logInfo("Exported ${rows.size()} order lines for demand data", "AI_DEMAND")
    return [responseMessage: "success", exported: rows.size()]
}

Map exportProductsForDemand() {
    return exportProductsForDemand(resolveDispatchContext(), resolveServiceContext())
}

Map exportProductsForDemand(DispatchContext dctx, Map context) {
    def delegator = dctx?.delegator
    if (!delegator) return [responseMessage: "error", errorMessage: "No delegator available"]
    def products = EntityQuery.use(delegator).from("Product").queryList()

    List<List<String>> rows = []
    products.each { p ->
        rows << [
                p.productId ?: "",
                p.internalName ?: "",
                p.productTypeId ?: "",
                formatDate(p.introductionDate),
                formatDate(p.salesDiscontinuationDate)
        ]
    }
    writeCsv("products.csv", ["productId", "internalName", "productTypeId", "introductionDate", "salesDiscontinuationDate"], rows)
    Debug.logInfo("Exported ${rows.size()} products for demand data", "AI_DEMAND")
    return [responseMessage: "success", exported: rows.size()]
}

Map exportProductFacilityForDemand() {
    return exportProductFacilityForDemand(resolveDispatchContext(), resolveServiceContext())
}

Map exportProductFacilityForDemand(DispatchContext dctx, Map context) {
    def delegator = dctx?.delegator
    if (!delegator) return [responseMessage: "error", errorMessage: "No delegator available"]
    def rows = []
    EntityQuery.use(delegator).from("ProductFacility").queryList().each { pf ->
        rows << [
                pf.productId ?: "",
                pf.facilityId ?: "",
                pf.minimumStock ?: "",
                pf.reorderQuantity ?: "",
                ""
        ]
    }
    writeCsv("product_facility.csv", ["productId", "facilityId", "minimumStock", "reorderQuantity", "lastInventoryCountDate"], rows)
    Debug.logInfo("Exported ${rows.size()} product facility rows for demand data", "AI_DEMAND")
    return [responseMessage: "success", exported: rows.size()]
}

Map exportInventoryItemsForDemand() {
    return exportInventoryItemsForDemand(resolveDispatchContext(), resolveServiceContext())
}

Map exportInventoryItemsForDemand(DispatchContext dctx, Map context) {
    def delegator = dctx?.delegator
    if (!delegator) return [responseMessage: "error", errorMessage: "No delegator available"]
    def rows = []
    EntityQuery.use(delegator).from("InventoryItem").queryList().each { ii ->
        rows << [
                ii.inventoryItemId ?: "",
                ii.productId ?: "",
                ii.facilityId ?: "",
                ii.quantityOnHandTotal ?: "",
                ii.availableToPromiseTotal ?: ""
        ]
    }
    writeCsv("inventory_items.csv", ["inventoryItemId", "productId", "facilityId", "quantityOnHandTotal", "availableToPromiseTotal"], rows)
    Debug.logInfo("Exported ${rows.size()} inventory items for demand data", "AI_DEMAND")
    return [responseMessage: "success", exported: rows.size()]
}

private void writeCsv(String filename, List<String> headers, List<List<String>> rows) {
    def exportDir = new File("runtime/data/export")
    exportDir.mkdirs()
    def outFile = new File(exportDir, filename)
    StringBuilder out = new StringBuilder(headers.join(",") + "\n")
    rows.each { cols ->
        out.append(cols.collect { sanitize(it) }.join(",")).append("\n")
    }
    outFile.text = out.toString()
}

private String sanitize(Object value) {
    if (value == null) return ""
    def v = value.toString().replaceAll(/\r?\n/, " ")
    if (v.contains(",") || v.contains("\"")) {
        v = '"' + v.replace("\"", "\"\"") + '"'
    }
    return v
}

private String formatDate(Object value) {
    if (!value) return ""
    try {
        def ts = value instanceof java.sql.Timestamp ? value : UtilDateTime.toTimestamp(value)
        return ts.toLocalDateTime().toLocalDate().toString()
    } catch (Throwable ignored) {
        return value.toString()
    }
}

private Timestamp parseDate(Object value) {
    if (!value) return null
    try {
        def ts = value instanceof Timestamp ? value : UtilDateTime.toTimestamp(value.toString())
        return ts
    } catch (Throwable ignored) {
        return null
    }
}

private String sanitizeQuantity(Object quantity) {
    def q = 0
    try {
        q = quantity ? quantity.toString().toBigDecimal() : 0
    } catch (Throwable ignored) {
        q = 0
    }
    if (q < 0) q = 0
    return q.toString()
}

private DispatchContext resolveDispatchContext() {
    if (binding?.hasVariable("dctx")) {
        return binding.getVariable("dctx") as DispatchContext
    }
    if (binding?.hasVariable("dispatcher")) {
        return binding.getVariable("dispatcher")?.dispatchContext as DispatchContext
    }
    if (binding?.hasVariable("context")) {
        def ctx = binding.getVariable("context") as Map
        return (ctx?.dispatcher?.dispatchContext ?: ctx?.dctx) as DispatchContext
    }
    return null
}

private Map resolveServiceContext() {
    if (binding?.hasVariable("context")) {
        return (binding.getVariable("context") as Map) ?: [:]
    }
    return [:]
}
