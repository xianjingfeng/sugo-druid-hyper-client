package io.druid.hyper.client.imports;

import com.google.common.base.Joiner;
import com.google.common.base.Preconditions;
import com.google.common.base.Splitter;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.util.concurrent.ThreadFactoryBuilder;
import io.druid.hyper.client.imports.datasource.DataSourceSpecLoader;
import io.druid.hyper.client.imports.input.BatchRecord;
import io.druid.hyper.client.imports.input.HyperAddRecord;
import io.druid.hyper.client.imports.input.HyperDeleteRecord;
import io.druid.hyper.client.imports.input.HyperUpdateRecord;
import io.druid.hyper.client.util.PartitionUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Closeable;
import java.io.IOException;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class DataSender implements Closeable {

    private static final Logger log = LoggerFactory.getLogger(DataSender.class);
    private static final int DEFAULT_SEND_THRESHOLD = 100;
    private static final int CACHE_FLUSH_PERIOD = 3; // seconds
    private static final int MAX_CACHE_FLUSH_DURATION = 1000; // million seconds

    private final Map<CacheKey, List<String>> dataCache = Maps.newConcurrentMap();
    private final DataSendWorker sendWorker;
    private final DataSourceSpecLoader dataSourceSpecLoader;

    private ScheduledExecutorService cacheFlusher;
    private String dataSource;
    private int updateThreshold;
    private int addThreshold = DEFAULT_SEND_THRESHOLD;

    private DataSender(String hmaster, String dataSource) {
        this(hmaster, dataSource, DEFAULT_SEND_THRESHOLD);
    }

    private DataSender(String hmaster, String dataSource, int threshold) {
        this.dataSource = dataSource;
        this.sendWorker = new DataSendWorker(hmaster, dataSource);
        this.dataSourceSpecLoader = new DataSourceSpecLoader(hmaster, dataSource);
        this.updateThreshold = threshold;
        initializeFlusher();
    }

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private static Map<Builder, DataSender> senderCache = Maps.newConcurrentMap();
        private String dataSource;
        private String server;

        public Builder ofDataSource(String dataSource) {
            this.dataSource = dataSource;
            return this;
        }

        public Builder toServer(String server) {
            this.server = server;
            return this;
        }

        public DataSender build() {
            Preconditions.checkNotNull(server, "server can not be null.");
            Preconditions.checkNotNull(dataSource, "data source can not be null.");

            DataSender sender = senderCache.get(this);
            if (sender == null) {
                sender = new DataSender(server, dataSource);
                senderCache.putIfAbsent(this, sender);
                sender = senderCache.get(this); // Get again to make sure sender is not null.
            }
            return sender;
        }

        public String getDataSource() {
            return dataSource;
        }

        public String getServer() {
            return server;
        }

        @Override
        public boolean equals(Object that) {
            if (this == that) {
                return true;
            }
            if (that == null || getClass() != that.getClass()) {
                return false;
            }

            Builder builder = (Builder) that;
            if (server != null ? !server.equals(builder.getServer()) : builder.getServer() != null) {
                return false;
            }
            if (dataSource != null ? !dataSource.equals(builder.getDataSource()) : builder.getDataSource() != null) {
                return false;
            }
            return true;
        }

        @Override
        public int hashCode() {
            int result = server != null ? server.hashCode() : 0;
            result = 31 * result + (dataSource != null ? dataSource.hashCode() : 0);
            return result;
        }
    }

    /**
     * A single row that to be added to UIndex, the column values are separated with delimiter
     * which is passed to UIndex when you create the data source.
     * Note that: the order of the column values in the row must be same with the dimensions
     * of the data source.
     * @param row the row to be added. eg: 1001|Nicolas|male|18, the delimiter is '|'.
     * @throws Exception
     */
    public void add(String row) throws Exception {
        Preconditions.checkNotNull(row, "row can not be null.");
        int partition = sendWorker.getPartitions();
        String delimiter = dataSourceSpecLoader.getDelimiter();
        int primaryIndex = dataSourceSpecLoader.getPrimaryIndex();
        List<String> columns = dataSourceSpecLoader.getColumns();
        Iterable<String> valuesIter = Splitter.on(delimiter).split(row);
        List<String> values = Lists.newArrayList(valuesIter);
        if (columns.size() > values.size()) {
            throw new IllegalArgumentException("Column values size not matched, expected: " +
                    columns.size() + ", but actually: " + values.size());
        }

        String primaryValue = values.get(primaryIndex);
        int partitionNum = PartitionUtil.getPartitionNum(primaryValue, partition);
        CacheKey cacheKey = new CacheKey(BatchRecord.RECORD_ACTION_ADD, partitionNum);
        addToCache(cacheKey, row);
    }

    /**
     * Add a single row to UIndex, which the column values are in the form of a list.
     * Note that: the order of the column values in the list must be same with the dimensions
     * of the data source.
     * @param columnValues a single row's values. eg: ["1001", "Nicolas", "male", 18]
     * @throws Exception
     */
    public void add(List<Object> columnValues) throws Exception {
        Preconditions.checkState(columnValues!= null && columnValues.size() >= 1, "column values can not be null.");
        int partition = sendWorker.getPartitions();
        String delimiter = dataSourceSpecLoader.getDelimiter();
        int primaryIndex = dataSourceSpecLoader.getPrimaryIndex();
        List<String> columns = dataSourceSpecLoader.getColumns();
        if (columns.size() > columnValues.size()) {
            throw new IllegalArgumentException("Column values size not matched, expected: " +
                    columns.size() + ", but actually: " + columnValues.size());
        }

        String row = Joiner.on(delimiter).useForNull("").join(columnValues);
        Object primaryValue = columnValues.get(primaryIndex);
        int partitionNum = PartitionUtil.getPartitionNum(primaryValue, partition);
        CacheKey cacheKey = new CacheKey(BatchRecord.RECORD_ACTION_ADD, partitionNum);
        addToCache(cacheKey, row);
    }

    /**
     * Update an existing row in UIndex.
     * Note that: the row must specified the primary column.
     * @param row the row to be updated. eg: {"id":"1001", "name":"Nicolas", "age":20}
     * @throws Exception
     */
    public void update(Map<String, Object> row) throws Exception {
        Preconditions.checkState(row!= null && row.size() >= 1, "row can not be null.");
        String primaryColumn = dataSourceSpecLoader.getPrimaryColumn();
        Set<String> columns = row.keySet();
        if (!columns.contains(primaryColumn)) {
            throw new IllegalArgumentException("row must be contain primary column: " + primaryColumn);
        }
        Object primaryValue = row.get(primaryColumn);
        Preconditions.checkNotNull(primaryValue, "primary value can not be null.");

        // Sorted the key to make sure the number of CacheKey as less as possible
        Map<String, Object> sortMap = Maps.newTreeMap();
        sortMap.putAll(row);

        int partition = sendWorker.getPartitions();
        int partitionNum = PartitionUtil.getPartitionNum(primaryValue, partition);
        String delimiter = dataSourceSpecLoader.getDelimiter();
        String columnsStr = Joiner.on(",").join(sortMap.keySet());
        String valuesStr = Joiner.on(delimiter).useForNull("").join(sortMap.values());
        CacheKey cacheKey = new CacheKey(BatchRecord.RECORD_ACTION_UPDATE, columnsStr, partitionNum);
        addToCache(cacheKey, valuesStr);
    }

    /**
     * Update an existing row in UIndex.
     * @param columns the columns to be updated.   eg: ["id", "name", "age"]
     * @param values the new value of each column. eg: ["1001", "Nicolas", 18]
     * @throws Exception
     */
    public void update(List<String> columns, List<Object> values) throws Exception {
        Preconditions.checkState(columns!= null && columns.size() >= 1, "columns can not be null.");
        Preconditions.checkState(values!= null && values.size() >= 1, "values can not be null.");
        Preconditions.checkState(columns.size() == values.size(), "columns and values size not matched.");

        int partition = sendWorker.getPartitions();
        String delimiter = dataSourceSpecLoader.getDelimiter();
        String primaryColumn = dataSourceSpecLoader.getPrimaryColumn();
        if (!columns.contains(primaryColumn)) {
            throw new IllegalArgumentException("columns must be contain primary column: " + primaryColumn);
        }

        Object primaryValue = values.get(columns.indexOf(primaryColumn));
        int partitionNum = PartitionUtil.getPartitionNum(primaryValue, partition);
        String columnsStr = Joiner.on(",").join(columns);
        String valuesStr = Joiner.on(delimiter).useForNull("").join(values);
        CacheKey cacheKey = new CacheKey(BatchRecord.RECORD_ACTION_UPDATE, columnsStr, partitionNum);
        addToCache(cacheKey, valuesStr);
    }

    /**
     * Delete rows in UIndex.
     * @param primaryValues the primary values of rows to be deleted. eg: ["1001", "1002", "1006"]
     * @throws Exception
     */
    public void delete(List<String> primaryValues) throws Exception {
        Preconditions.checkState(primaryValues!= null && primaryValues.size() >= 1, "columns can not be null.");
        for (String primaryValue : primaryValues) {
            delete(primaryValue);
        }
    }

    /**
     * Delete a single row in UIndex.
     * @param primaryValue the primary value of the row to be deleted.
     * @throws Exception
     */
    public void delete(String primaryValue) throws Exception {
        Preconditions.checkNotNull(primaryValue, "primary value can not be null.");
        int partition = sendWorker.getPartitions();
        int partitionNum = PartitionUtil.getPartitionNum(primaryValue, partition);
        CacheKey cacheKey = new CacheKey(BatchRecord.RECORD_ACTION_DELETE, partitionNum);
        addToCache(cacheKey, primaryValue);
    }

    private void addToCache(CacheKey cacheKey, String values) throws Exception {
        List<String> valuesList = dataCache.get(cacheKey);
        if (valuesList == null) {
            valuesList = Lists.newArrayList();
            dataCache.put(cacheKey, valuesList);
        }
        synchronized (valuesList) {
            valuesList.add(values);
            if (reachThreshold(cacheKey.getAction(), valuesList.size())) {
                StringBuffer sb = new StringBuffer("Client main thread send a batch of data, action [")
                        .append(cacheKey.getAction()).append("], size [")
                        .append(valuesList.size()).append("], partitionNum [")
                        .append(cacheKey.getPartitionNum()).append("].");
                log.info(sb.toString());
                sendData(cacheKey, valuesList);
            }
        }
    }

    private void initializeFlusher() {
        cacheFlusher = Executors.newSingleThreadScheduledExecutor(new ThreadFactoryBuilder()
                        .setNameFormat("cache-flush-thread").build());

        cacheFlusher.scheduleAtFixedRate(
                new Runnable() {
                    @Override
                    public void run() {
                        Iterator<Map.Entry<CacheKey, List<String>>> it = dataCache.entrySet().iterator();
                        while (it.hasNext()) {
                            Map.Entry<CacheKey, List<String>> entry = it.next();
                            CacheKey cacheKey = entry.getKey();
                            long now = System.currentTimeMillis();
                            if (now - cacheKey.getLastSendTime() >= MAX_CACHE_FLUSH_DURATION) {
                                List<String> valuesList = entry.getValue();
                                if (!valuesList.isEmpty()) {
                                    synchronized (valuesList) {
                                        if (!valuesList.isEmpty()) {
                                            try {
                                                StringBuffer sb = new StringBuffer("Cache flush thread send a batch of data, action [")
                                                        .append(cacheKey.getAction()).append("], size [")
                                                        .append(valuesList.size()).append("], partitionNum [")
                                                        .append(cacheKey.getPartitionNum()).append("].");
                                                log.info(sb.toString());
                                                sendData(cacheKey, valuesList);
                                            } catch (Exception e) {
                                                log.error("Cache flush thread send data error: ", e);
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                },
                1,
                CACHE_FLUSH_PERIOD,
                TimeUnit.SECONDS);
    }

    private void sendData(CacheKey cacheKey, List<String> valuesList) throws Exception {
        BatchRecord batchRecord = makeBatchRecord(cacheKey, valuesList);
        sendWorker.send(batchRecord);
        cacheKey.setLastSendTime(System.currentTimeMillis());
        valuesList.clear();
    }

    private BatchRecord makeBatchRecord(CacheKey cacheKey, List<String> valuesList) {
        String action = cacheKey.getAction();
        switch (action) {
            case BatchRecord.RECORD_ACTION_ADD:
                return new HyperAddRecord(dataSource, cacheKey.getPartitionNum(), valuesList);
            case BatchRecord.RECORD_ACTION_UPDATE:
                Iterable<String> columnsIter = Splitter.on(",")
                        .trimResults()
                        .split(cacheKey.getColumns());
                List<String> columns = Lists.newArrayList(columnsIter);
                return new HyperUpdateRecord(dataSource, cacheKey.getPartitionNum(), columns, valuesList);
            case BatchRecord.RECORD_ACTION_DELETE:
                return new HyperDeleteRecord(dataSource, cacheKey.getPartitionNum(), valuesList);
            default:
                return null;
        }
    }

    private boolean reachThreshold(String action, int size) {
        return BatchRecord.RECORD_ACTION_UPDATE.equals(action) ?
                size >= updateThreshold :
                size >= addThreshold;
    }

    @Override
    public void close() throws IOException {
        if (cacheFlusher != null) {
            Iterator<Map.Entry<CacheKey, List<String>>> it = dataCache.entrySet().iterator();
            while (it.hasNext()) {
                Map.Entry<CacheKey, List<String>> entry = it.next();
                CacheKey cacheKey = entry.getKey();
                List<String> valuesList = entry.getValue();
                if (!valuesList.isEmpty()) {
                    synchronized (valuesList) {
                        if (!valuesList.isEmpty()) {
                            try {
                                StringBuffer sb = new StringBuffer("Main thread send a batch of data when closing, action [")
                                        .append(cacheKey.getAction()).append("], size [")
                                        .append(valuesList.size()).append("], partitionNum [")
                                        .append(cacheKey.getPartitionNum()).append("].");
                                log.info(sb.toString());
                                sendData(cacheKey, valuesList);
                            } catch (Exception e) {
                                log.error("Send data error when closing: ", e);
                            }
                        }
                    }
                }
            }
            cacheFlusher.shutdown();
        }
    }

    private static class CacheKey {
        private String columns;
        private String action;
        private int partitionNum;
        private long lastSendTime;

        public CacheKey(String action, int partitionNum) {
            this(action, null, partitionNum);
        }

        public CacheKey(String action, String columns, int partitionNum) {
            this.columns = columns;
            this.action = action;
            this.partitionNum = partitionNum;
        }

        public String getColumns() {
            return columns;
        }

        public String getAction() {
            return action;
        }

        public int getPartitionNum() {
            return partitionNum;
        }

        public long getLastSendTime() {
            return lastSendTime;
        }

        public void setLastSendTime(long lastSendTime) {
            this.lastSendTime = lastSendTime;
        }

        @Override
        public boolean equals(Object that) {
            if (this == that) {
                return true;
            }
            if (that == null || getClass() != that.getClass()) {
                return false;
            }

            CacheKey cacheKey = (CacheKey) that;
            if (columns != null ? !columns.equals(cacheKey.getColumns()) : cacheKey.getColumns() != null) {
                return false;
            }
            if (action != null ? !action.equals(cacheKey.getAction()) : cacheKey.getAction() != null) {
                return false;
            }
            if (partitionNum != cacheKey.getPartitionNum()) {
                return false;
            }

            return true;
        }

        @Override
        public int hashCode() {
            int result = partitionNum;
            result = 31 * result + (columns != null ? columns.hashCode() : 0);
            result = 31 * result + (action != null ? action.hashCode() : 0);
            return result;
        }
    }
}