package com.pathvariable.smartgarden.summary;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.influxdb.client.InfluxDBClient;
import com.influxdb.client.InfluxDBClientFactory;
import com.influxdb.client.QueryApi;
import com.influxdb.client.WriteApiBlocking;
import com.influxdb.client.domain.WritePrecision;
import com.influxdb.client.write.Point;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Executes one summary generation cycle: query InfluxDB, call model, and write the summary back.
 */
public final class SummaryJob {

    private static final Logger LOG = LoggerFactory.getLogger(SummaryJob.class);
    private final GeminiClient geminiClient;

    public SummaryJob(GeminiClient geminiClient) {
        this.geminiClient = geminiClient;
    }

    public void runOnce(Config cfg) throws Exception {
        LOG.info("Job started at {}", Instant.now());
        try (InfluxDBClient influx = InfluxDBClientFactory.create(cfg.influxUrl(), cfg.influxToken().toCharArray(), cfg.influxOrg(), cfg.influxBucket())) {
            QueryApi queryApi = influx.getQueryApi();

            // Build default queries for last window
            String range = "|> range(start: -" + cfg.intervalMinutes() + "m)";
            String base = "from(bucket: \"" + cfg.influxBucket() + "\") " + range +
                    " |> filter(fn: (r) => r[\"_measurement\"] =~ /" + cfg.measurementRegex() + "/)" +
                    (cfg.fieldRegex() != null ? " |> filter(fn: (r) => r[\"_field\"] =~ /" + cfg.fieldRegex() + "/)" : "");

            Map<MetricKey, Double> mean = queryNumberMap(queryApi, base + " |> aggregateWindow(every: " + cfg.intervalMinutes() + "m, fn: mean) |> last()");
            Map<MetricKey, Double> min = queryNumberMap(queryApi, base + " |> aggregateWindow(every: " + cfg.intervalMinutes() + "m, fn: min) |> last()");
            Map<MetricKey, Double> max = queryNumberMap(queryApi, base + " |> aggregateWindow(every: " + cfg.intervalMinutes() + "m, fn: max) |> last()");
            Map<MetricKey, Double> last = queryNumberMap(queryApi, base + " |> last()");

            // Merge keys
            Set<MetricKey> keys = new HashSet<>();
            keys.addAll(mean.keySet());
            keys.addAll(min.keySet());
            keys.addAll(max.keySet());
            keys.addAll(last.keySet());

            // Build a compact metrics JSON for the model
            JsonArray items = new JsonArray();
            for (MetricKey k : keys) {
                JsonObject obj = new JsonObject();
                obj.addProperty("measurement", k.measurement());
                obj.addProperty("field", k.field());
                if (mean.containsKey(k)) obj.addProperty("mean", mean.get(k));
                if (min.containsKey(k)) obj.addProperty("min", min.get(k));
                if (max.containsKey(k)) obj.addProperty("max", max.get(k));
                if (last.containsKey(k)) obj.addProperty("last", last.get(k));
                items.add(obj);
            }

            JsonObject payload = new JsonObject();
            payload.addProperty("interval_minutes", cfg.intervalMinutes());
            payload.addProperty("timezone", cfg.timezone());
            payload.add("metrics", items);

            String prompt = "You are an assistant that summarizes monitoring metrics for a dashboard. " +
                    "Given the following JSON metrics aggregated over the last " + cfg.intervalMinutes() + " minutes, " +
                    "write a short, human-friendly summary (2-4 concise sentences) highlighting anomalies, trends, and any actionable insights. " +
                    "Avoid repeating raw numbers unless necessary. JSON: " + payload;

            String summary = geminiClient.generateSummary(prompt);
            LOG.info("Model summary: {}", summary);

            // Write back to InfluxDB
            WriteApiBlocking write = influx.getWriteApiBlocking();
            Point point = Point.measurement(cfg.outputMeasurement())
                    .addTag("model", cfg.model())
                    .addField("text", summary)
                    .addField("interval_minutes", cfg.intervalMinutes())
                    .time(Instant.now(), WritePrecision.S);
            write.writePoint(point);
            LOG.info("Summary written to InfluxDB measurement '{}' in bucket '{}'", cfg.outputMeasurement(), cfg.influxBucket());
        }
        LOG.info("Job finished at {}", Instant.now());
    }

    private static Map<MetricKey, Double> queryNumberMap(QueryApi api, String flux) {
        Map<MetricKey, Double> map = new HashMap<>();
        try {
            api.query(flux).forEach(table -> table.getRecords().forEach(rec -> {
                Object mv = rec.getValueByKey("_measurement");
                Object fv = rec.getValueByKey("_field");
                Object vv = rec.getValue();
                if (mv != null && fv != null && vv instanceof Number) {
                    map.put(new MetricKey(mv.toString(), fv.toString()), ((Number) vv).doubleValue());
                }
            }));
        } catch (Exception e) {
            LOG.warn("Query failed: {}\nFlux: {}", e.getMessage(), flux);
        }
        return map;
    }
}
