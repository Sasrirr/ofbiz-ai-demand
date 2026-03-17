/**
 * Export minimal order line data for demand forecasting.
 *
 * Output columns (CSV): orderId,orderDate,productId,quantity,facilityId
 *
 * How to run (headless, recommended):
 *   gradlew "ofbiz --script=runtime/script/exportOrders.groovy"
 *
 * Result file: runtime/data/export/order_lines.csv
 *
 * The script queries completed orders; remove the status filter to export all.
 */

import org.apache.ofbiz.entity.util.EntityQuery
import java.time.format.DateTimeFormatter

def delegator = request?.delegator ?: delegator   // works in webtools or CLI
def fmt = DateTimeFormatter.ISO_LOCAL_DATE

def headers = EntityQuery.use(delegator)
        .from("OrderHeader")
        .where("statusId", "ORDER_COMPLETED") // change/remove to widen scope
        .orderBy("orderDate")
        .queryList()

StringBuilder out = new StringBuilder("orderId,orderDate,productId,quantity,facilityId\n")

headers.each { oh ->
    EntityQuery.use(delegator)
            .from("OrderItem")
            .where("orderId", oh.orderId)
            .queryList()
            .each { oi ->
                // Attempt to pull facility from the associated ship group
                def facilityId = ""
                def shipGrp = EntityQuery.use(delegator)
                        .from("OrderItemShipGroup")
                        .where("orderId", oi.orderId, "shipGroupSeqId", oi.shipGroupSeqId)
                        .queryFirst()
                if (shipGrp) {
                    facilityId = shipGrp.facilityId ?: ""
                }
                out.append("${oi.orderId},${oh.orderDate.format(fmt)},${oi.productId},${oi.quantity},${facilityId}\n")
            }
}

def outFile = new File("runtime/data/export/order_lines.csv")
outFile.parentFile?.mkdirs()
outFile.text = out.toString()

println "Exported ${headers.size()} orders to ${outFile.absolutePath}"