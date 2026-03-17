connect 'jdbc:derby:E:/FSD/ai-ofbiz/ofbiz-framework/runtime/data/derby/ofbiz;create=false';
set schema OFBIZ;

CALL SYSCS_UTIL.SYSCS_EXPORT_QUERY(
  'SELECT oh.order_id, oh.order_date, oi.product_id, oi.quantity, oisg.facility_id
     FROM ORDER_HEADER oh
     JOIN ORDER_ITEM oi ON oi.order_id = oh.order_id
     LEFT JOIN ORDER_ITEM_SHIP_GROUP_ASSOC assoc
       ON assoc.order_id = oi.order_id AND assoc.order_item_seq_id = oi.order_item_seq_id
     LEFT JOIN ORDER_ITEM_SHIP_GROUP oisg
       ON oisg.order_id = assoc.order_id AND oisg.ship_group_seq_id = assoc.ship_group_seq_id',
  'runtime/data/export/order_lines.csv',
  null, null, null);

exit;