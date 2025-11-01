package com.pathvariable.smartgarden.summary;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.influxdb.client.InfluxDBClient;
import com.influxdb.client.InfluxDBClientFactory;
import com.influxdb.client.QueryApi;
import com.influxdb.client.WriteApiBlocking;
import com.influxdb.client.domain.WritePrecision;
import com.influxdb.client.write.Point;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.*;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class Main {
    private static final Gson GSON = new Gson();

    public static void main(String[] args) {
        Config cfg = Config.fromEnv();
        if (!cfg.isValid()) {
            System.err.println("Missing required configuration. Please set the required environment variables.\n" + cfg.missingDescription());
            System.exit(1);
        }

        Runnable job = () -> {
            try {
                runOnce(cfg);
            } catch (Exception e) {
                System.err.println("[ERROR] Job execution failed: " + e.getMessage());
                e.printStackTrace();
            }
        };

        if (cfg.runOnce) {
            job.run();
            return;
        }

        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "summary-scheduler");
            t.setDaemon(true);
            return t;
        });

        long initialDelayMs = computeInitialDelayToNextInterval(cfg.intervalMinutes, cfg.timezone);
        long periodMs = TimeUnit.MINUTES.toMillis(cfg.intervalMinutes);
        System.out.println("[INFO] Scheduling job. First run in " + initialDelayMs / 1000 + "s, then every " + cfg.intervalMinutes + " minutes.");
        scheduler.scheduleAtFixedRate(job, initialDelayMs, periodMs, TimeUnit.MILLISECONDS);

        // Keep JVM alive
        try {
            //noinspection InfiniteLoopStatement
            while (true) {
                Thread.sleep(60000);
            }
        } catch (InterruptedException ignored) {
        }
    }

    private static void runOnce(Config cfg) throws Exception {
        System.out.println("[INFO] Job started at " + Instant.now());
        try (InfluxDBClient influx = InfluxDBClientFactory.create(cfg.influxUrl, cfg.influxToken.toCharArray(), cfg.influxOrg, cfg.influxBucket)) {
            QueryApi queryApi = influx.getQueryApi();

            // Build default queries for last window
            String range = "|> range(start: -" + cfg.intervalMinutes + "m)";
            String base = "from(bucket: \"" + cfg.influxBucket + "\") " + range + " |> filter(fn: (r) => r[\"_measurement\"] =~ /" + cfg.measurementRegex + "/)" + (cfg.fieldRegex != null ? " |> filter(fn: (r) => r[\"_field\"] =~ /" + cfg.fieldRegex + "/)" : "");

            Map<Key, Double> mean = queryNumberMap(queryApi, base + " |> aggregateWindow(every: " + cfg.intervalMinutes + "m, fn: mean) |> last()");
            Map<Key, Double> min = queryNumberMap(queryApi, base + " |> aggregateWindow(every: " + cfg.intervalMinutes + "m, fn: min) |> last()");
            Map<Key, Double> max = queryNumberMap(queryApi, base + " |> aggregateWindow(every: " + cfg.intervalMinutes + "m, fn: max) |> last()");
            Map<Key, Double> last = queryNumberMap(queryApi, base + " |> last()");

            // Merge keys
            Set<Key> keys = new HashSet<>();
            keys.addAll(mean.keySet());
            keys.addAll(min.keySet());
            keys.addAll(max.keySet());
            keys.addAll(last.keySet());

            // Build a compact metrics JSON for the model
            JsonArray items = new JsonArray();
            for (Key k : keys) {
                JsonObject obj = new JsonObject();
                obj.addProperty("measurement", k.measurement);
                obj.addProperty("field", k.field);
                if (mean.containsKey(k)) obj.addProperty("mean", mean.get(k));
                if (min.containsKey(k)) obj.addProperty("min", min.get(k));
                if (max.containsKey(k)) obj.addProperty("max", max.get(k));
                if (last.containsKey(k)) obj.addProperty("last", last.get(k));
                items.add(obj);
            }

            JsonObject payload = new JsonObject();
            payload.addProperty("interval_minutes", cfg.intervalMinutes);
            payload.addProperty("timezone", cfg.timezone);
            payload.add("metrics", items);

            String prompt = "You are an assistant that summarizes monitoring metrics for a dashboard. " +
                    "Given the following JSON metrics aggregated over the last " + cfg.intervalMinutes + " minutes, " +
                    "write a short, human-friendly summary (2-4 concise sentences) highlighting anomalies, trends, and any actionable insights. " +
                    "Avoid repeating raw numbers unless necessary. JSON: " + payload;

            String summary = callGemini(cfg, prompt);
            System.out.println("[INFO] Model summary: " + summary);

            // Write back to InfluxDB
            WriteApiBlocking write = influx.getWriteApiBlocking();
            Point point = Point.measurement(cfg.outputMeasurement)
                    .addTag("model", cfg.model)
                    .addField("text", summary)
                    .addField("interval_minutes", cfg.intervalMinutes)
                    .time(Instant.now(), WritePrecision.S);
            write.writePoint(point);
            System.out.println("[INFO] Summary written to InfluxDB measurement '" + cfg.outputMeasurement + "' in bucket '" + cfg.influxBucket + "'.");
        }
        System.out.println("[INFO] Job finished at " + Instant.now());
    }

    private static Map<Key, Double> queryNumberMap(QueryApi api, String flux) {
        Map<Key, Double> map = new HashMap<>();
        try {
            api.query(flux).forEach(table -> table.getRecords().forEach(rec -> {
                Object mv = rec.getValueByKey("_measurement");
                Object fv = rec.getValueByKey("_field");
                Object vv = rec.getValue();
                if (mv != null && fv != null && vv instanceof Number) {
                    map.put(new Key(mv.toString(), fv.toString()), ((Number) vv).doubleValue());
                }
            }));
        } catch (Exception e) {
            System.err.println("[WARN] Query failed: " + e.getMessage() + "\nFlux: " + flux);
        }
        return map;
    }

    private static String callGemini(Config cfg, String prompt) throws Exception {
        String url = "https://generativelanguage.googleapis.com/v1beta/models/" + cfg.model + ":generateContent?key=" + cfg.googleApiKey;

        JsonObject userPart = new JsonObject();
        userPart.addProperty("text", prompt);
        JsonObject content = new JsonObject();
        content.add("parts", arrayOf(userPart));

        JsonObject req = new JsonObject();
        req.add("contents", arrayOf(content));
        // Optional system instruction
        if (cfg.systemInstruction != null && !cfg.systemInstruction.isBlank()) {
            JsonObject sys = new JsonObject();
            sys.add("parts", arrayOf(textPart(cfg.systemInstruction)));
            req.add("system_instruction", sys);
        }

        HttpClient http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(15))
                .build();
        HttpRequest httpReq = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(60))
                .header("Content-Type", "application/json; charset=utf-8")
                .POST(HttpRequest.BodyPublishers.ofString(GSON.toJson(req), StandardCharsets.UTF_8))
                .build();
        HttpResponse<String> resp = http.send(httpReq, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (resp.statusCode() < 200 || resp.statusCode() >= 300) {
            throw new RuntimeException("Gemini API error: HTTP " + resp.statusCode() + " - " + resp.body());
        }
        JsonObject json = GSON.fromJson(resp.body(), JsonObject.class);
        // Parse first candidate text
        try {
            JsonArray candidates = json.getAsJsonArray("candidates");
            if (candidates == null || candidates.isEmpty()) return "";
            JsonObject first = candidates.get(0).getAsJsonObject();
            JsonObject c = first.getAsJsonObject("content");
            JsonArray parts = c.getAsJsonArray("parts");
            if (parts == null || parts.isEmpty()) return "";
            JsonObject p0 = parts.get(0).getAsJsonObject();
            return p0.has("text") ? p0.get("text").getAsString() : "";
        } catch (Exception e) {
            throw new RuntimeException("Failed to parse Gemini response: " + e.getMessage() + " Body: " + resp.body());
        }
    }

    private static JsonArray arrayOf(JsonObject obj) {
        JsonArray arr = new JsonArray();
        arr.add(obj);
        return arr;
    }
    private static JsonObject textPart(String text) {
        JsonObject p = new JsonObject();
        p.addProperty("text", text);
        return p;
    }

    private static long computeInitialDelayToNextInterval(Integer interval, String timezone) {
        ZonedDateTime now = ZonedDateTime.now(ZoneId.of(timezone));
        int minute = now.getMinute();
        int nextQuarter = ((minute / interval) + 1) * interval;
        if (nextQuarter >= 60) {
            ZonedDateTime nextHour = now.plusHours(1).withMinute(0).withSecond(0).withNano(0);
            return Duration.between(now, nextHour).toMillis();
        } else {
            ZonedDateTime next = now.withMinute(nextQuarter).withSecond(0).withNano(0);
            return Duration.between(now, next).toMillis();
        }
    }

    private record Key(String measurement, String field) {}

    private static class Config {
        final String influxUrl;
        final String influxToken;
        final String influxOrg;
        final String influxBucket;
        final String measurementRegex;
        final String fieldRegex;
        final String googleApiKey;
        final String model;
        final String outputMeasurement;
        final int intervalMinutes;
        final String timezone;
        final boolean runOnce;
        final String systemInstruction;

        Config(String influxUrl, String influxToken, String influxOrg, String influxBucket, String measurementRegex,
               String fieldRegex, String googleApiKey, String model, String outputMeasurement, int intervalMinutes,
               String timezone, boolean runOnce, String systemInstruction) {
            this.influxUrl = influxUrl;
            this.influxToken = influxToken;
            this.influxOrg = influxOrg;
            this.influxBucket = influxBucket;
            this.measurementRegex = measurementRegex;
            this.fieldRegex = fieldRegex;
            this.googleApiKey = googleApiKey;
            this.model = model;
            this.outputMeasurement = outputMeasurement;
            this.intervalMinutes = intervalMinutes;
            this.timezone = timezone;
            this.runOnce = runOnce;
            this.systemInstruction = systemInstruction;
        }

        static Config fromEnv() {
            String influxUrl = getenv("INFLUX_URL", "http://localhost:8086");
            String influxToken = getenv("INFLUX_TOKEN", null);
            String influxOrg = getenv("INFLUX_ORG", null);
            String influxBucket = getenv("INFLUX_BUCKET", null);
            String measurementRegex = getenv("INFLUX_MEASUREMENT_REGEX", ".*");
            String fieldRegex = getenv("INFLUX_FIELD_REGEX", null);
            String googleApiKey = getenv("GOOGLE_API_KEY", null);
            String model = getenv("GOOGLE_MODEL", "gemini-1.5-flash");
            String outputMeasurement = getenv("OUTPUT_MEASUREMENT", "dashboard_summary");
            int interval = Integer.parseInt(getenv("INTERVAL_MINUTES", "15"));
            String timezone = getenv("TIMEZONE", ZoneId.systemDefault().getId());
            boolean runOnce = Boolean.parseBoolean(getenv("RUN_ONCE", "false"));
            String systemInstruction = getenv("SYSTEM_INSTRUCTION", "You are a concise observability assistant.");
            return new Config(influxUrl, influxToken, influxOrg, influxBucket, measurementRegex, fieldRegex, googleApiKey, model, outputMeasurement, interval, timezone, runOnce, systemInstruction);
        }

        boolean isValid() {
            return notBlank(influxUrl) && notBlank(influxToken) && notBlank(influxOrg) && notBlank(influxBucket)
                    && notBlank(googleApiKey) && notBlank(model) && intervalMinutes > 0;
        }

        String missingDescription() {
            List<String> m = new ArrayList<>();
            if (!notBlank(influxUrl)) m.add("INFLUX_URL");
            if (!notBlank(influxToken)) m.add("INFLUX_TOKEN");
            if (!notBlank(influxOrg)) m.add("INFLUX_ORG");
            if (!notBlank(influxBucket)) m.add("INFLUX_BUCKET");
            if (!notBlank(googleApiKey)) m.add("GOOGLE_API_KEY");
            if (!notBlank(model)) m.add("GOOGLE_MODEL");
            if (intervalMinutes <= 0) m.add("INTERVAL_MINUTES");
            return "Missing/invalid: " + String.join(", ", m);
        }

        private static boolean notBlank(String s) { return s != null && !s.isBlank(); }
        private static String getenv(String k, String def) { String v = System.getenv(k); return v == null ? def : v; }
    }
}