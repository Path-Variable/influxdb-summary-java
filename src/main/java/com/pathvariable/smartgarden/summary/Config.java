package com.pathvariable.smartgarden.summary;

import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

/**
 * Application configuration loaded from environment variables.
 */
public record Config(String influxUrl, String influxToken, String influxOrg, String influxBucket,
                     String measurementRegex, String fieldRegex, String googleApiKey, String model,
                     String outputMeasurement, int intervalMinutes, String timezone, boolean runOnce,
                     String systemInstruction) {
    
    public static Config fromEnv() {
        return Config.builder()
                .influxUrl(getenv("INFLUX_URL", "http://localhost:8086"))
                .influxToken(getenv("INFLUX_TOKEN", null))
                .influxOrg(getenv("INFLUX_ORG", null))
                .influxBucket(getenv("INFLUX_BUCKET", null))
                .measurementRegex(getenv("INFLUX_MEASUREMENT_REGEX", ".*"))
                .fieldRegex(getenv("INFLUX_FIELD_REGEX", null))
                .googleApiKey(getenv("GOOGLE_API_KEY", null))
                .model(getenv("GOOGLE_MODEL", "gemini-2.5-flash"))
                .outputMeasurement(getenv("OUTPUT_MEASUREMENT", "dashboard_summary"))
                .intervalMinutes(Integer.parseInt(getenv("INTERVAL_MINUTES", "15")))
                .timezone(getenv("TIMEZONE", ZoneId.systemDefault().getId()))
                .runOnce(Boolean.parseBoolean(getenv("RUN_ONCE", "false")))
                .systemInstruction(getenv("SYSTEM_INSTRUCTION", "You are a concise observability assistant."))
                .build();
    }

    public boolean isValid() {
        return notBlank(influxUrl) && notBlank(influxToken) && notBlank(influxOrg) && notBlank(influxBucket)
                && notBlank(googleApiKey) && notBlank(model) && intervalMinutes > 0;
    }

    public String missingDescription() {
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

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }

    private static String getenv(String k, String def) {
        String v = System.getenv(k);
        return v == null ? def : v;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private String influxUrl = "http://localhost:8086";
        private String influxToken;
        private String influxOrg;
        private String influxBucket;
        private String measurementRegex = ".*";
        private String fieldRegex;
        private String googleApiKey;
        private String model = "gemini-2.5-flash";
        private String outputMeasurement = "dashboard_summary";
        private int intervalMinutes = 15;
        private String timezone = ZoneId.systemDefault().getId();
        private boolean runOnce = false;
        private String systemInstruction = "You are a concise observability assistant.";

        public Builder influxUrl(String influxUrl) {
            this.influxUrl = influxUrl;
            return this;
        }

        public Builder influxToken(String influxToken) {
            this.influxToken = influxToken;
            return this;
        }

        public Builder influxOrg(String influxOrg) {
            this.influxOrg = influxOrg;
            return this;
        }

        public Builder influxBucket(String influxBucket) {
            this.influxBucket = influxBucket;
            return this;
        }

        public Builder measurementRegex(String measurementRegex) {
            this.measurementRegex = measurementRegex;
            return this;
        }

        public Builder fieldRegex(String fieldRegex) {
            this.fieldRegex = fieldRegex;
            return this;
        }

        public Builder googleApiKey(String googleApiKey) {
            this.googleApiKey = googleApiKey;
            return this;
        }

        public Builder model(String model) {
            this.model = model;
            return this;
        }

        public Builder outputMeasurement(String outputMeasurement) {
            this.outputMeasurement = outputMeasurement;
            return this;
        }

        public Builder intervalMinutes(int intervalMinutes) {
            this.intervalMinutes = intervalMinutes;
            return this;
        }

        public Builder timezone(String timezone) {
            this.timezone = timezone;
            return this;
        }

        public Builder runOnce(boolean runOnce) {
            this.runOnce = runOnce;
            return this;
        }

        public Builder systemInstruction(String systemInstruction) {
            this.systemInstruction = systemInstruction;
            return this;
        }

        public Config build() {
            return new Config(influxUrl, influxToken, influxOrg, influxBucket, measurementRegex,
                    fieldRegex, googleApiKey, model, outputMeasurement, intervalMinutes,
                    timezone, runOnce, systemInstruction);
        }
    }
    
}
