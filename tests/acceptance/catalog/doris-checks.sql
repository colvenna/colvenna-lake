-- Read-only Doris checks on the tables the Flink and Spark flows wrote through colvenna-catalog.
-- run-doris-flow.sh replaces @DB@ with the flow database. Each check prints one line:
-- CHECK <name> <expected> <actual>; an expected value of >0 means any positive count.
SELECT 'CHECK orders-count', '98', CAST(count(*) AS STRING) FROM colvenna.@DB@.orders;
SELECT 'CHECK orders-evolved-column', '1', CAST(count(*) AS STRING)
  FROM colvenna.@DB@.orders WHERE note = 'evolved';
SELECT 'CHECK orders-spark-write', '1', CAST(count(*) AS STRING)
  FROM colvenna.@DB@.orders WHERE id = 500 AND note = 'spark';
SELECT 'CHECK orders-snapshot-1', '100', CAST(count(*) AS STRING)
  FROM colvenna.@DB@.orders FOR VERSION AS OF 1;
SELECT 'CHECK orders-tag', '96', CAST(count(*) AS STRING)
  FROM colvenna.@DB@.orders FOR VERSION AS OF 'flow_tag';
SELECT 'CHECK orders-branch', '97', CAST(count(*) AS STRING)
  FROM colvenna.@DB@.orders@branch('name'='flow_branch');
SELECT 'CHECK orders-snapshots', '>0', CAST(count(*) AS STRING) FROM colvenna.@DB@.orders$snapshots;
SELECT 'CHECK orders-tags', '1', CAST(count(*) AS STRING)
  FROM colvenna.@DB@.orders$tags WHERE tag_name = 'flow_tag';
SELECT 'CHECK events-count', '3000', CAST(count(*) AS STRING) FROM colvenna.@DB@.events;
SELECT 'CHECK events-partitions', '3', CAST(count(DISTINCT dt) AS STRING) FROM colvenna.@DB@.events;
SELECT 'CHECK spark-sales-count', '4', CAST(count(*) AS STRING) FROM colvenna.@DB@.spark_sales;
SELECT 'CHECK spark-sales-amount', '401', CAST(sum(amount) AS STRING) FROM colvenna.@DB@.spark_sales;
SELECT 'CHECK spark-sales-evolved', '1', CAST(count(*) AS STRING)
  FROM colvenna.@DB@.spark_sales WHERE channel = 'web';
-- The same reads through the JNI reader, which uses the Paimon SDK and its RESTTokenFileIO.
SET force_jni_scanner = true;
SELECT 'CHECK jni-orders-count', '98', CAST(count(*) AS STRING) FROM colvenna.@DB@.orders;
SELECT 'CHECK jni-events-count', '3000', CAST(count(*) AS STRING) FROM colvenna.@DB@.events;
SET force_jni_scanner = false;
