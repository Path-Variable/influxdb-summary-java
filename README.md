# DashboardSummary

A small Java service that periodically queries InfluxDB metrics, asks Google Gemini to produce a short human‑friendly summary (2–4 sentences), and writes that summary back to InfluxDB for display on dashboards.

## Features
- Pulls metrics from InfluxDB with configurable measurement/field filters
- Aggregates over a fixed time window (mean/min/max/last)
- Sends a compact JSON payload to Gemini with optional system instruction
- Writes the generated summary to an output measurement in the same bucket
- Can run once (batch mode) or on a fixed schedule

## Requirements
- Java 21+ (the Docker image uses Temurin 21 JRE but the app targets 17)
- InfluxDB 2.x with an API token and bucket/org
- Google Generative Language (Gemini) API key
- Gradle (wrapper included)

Configuration (Environment Variables)
The application reads configuration from environment variables at startup.

### Required
- `INFLUX_URL`: URL of InfluxDB (e.g., http://localhost:8086)
- `INFLUX_TOKEN`: InfluxDB API token with read/write access to the bucket
- `INFLUX_ORG`: InfluxDB organization name/ID
- `INFLUX_BUCKET`: InfluxDB bucket name
- `GOOGLE_API_KEY`: Google Gemini API key

### Optional (with defaults)
- `INFLUX_MEASUREMENT_REGEX`: Regex for measurements to include (default: .*)
- `INFLUX_FIELD_REGEX`: Regex for fields to include (default: unset = all fields)
- `GOOGLE_MODEL`: Gemini model (default: gemini-2.5-flash)
- `OUTPUT_MEASUREMENT`: Measurement to write summaries to (default: dashboard_summary)
- `INTERVAL_MINUTES`: Window size and schedule period in minutes (default: 15)
- `TIMEZONE`: IANA timezone for scheduling (default: system timezone)
- `RUN_ONCE`: If true, runs a single cycle and exits (default: false)
- `SYSTEM_INSTRUCTION`: System prompt sent to Gemini (default: "You are a concise observability assistant.")

## Quick start (local)
### 1) Export environment variables (example)


     export INFLUX_URL="http://localhost:8086"
     export INFLUX_TOKEN="<your-token>"
     export INFLUX_ORG="<your-org>"
     export INFLUX_BUCKET="<your-bucket>"
     export GOOGLE_API_KEY="<your-gemini-api-key>"

#### Optional filters and settings

    export INFLUX_MEASUREMENT_REGEX="^telemetry_.*$"
    export INFLUX_FIELD_REGEX="^(temperature|humidity)$"
    export INTERVAL_MINUTES=15
    export RUN_ONCE=false

### 2) Build and run with Gradle
    ./gradlew run

   To build a distribution you can run:

    ./gradlew installDist
    ./build/install/DashboardSummary/bin/DashboardSummary

## Run once (batch mode)
Set `RUN_ONCE=true` to execute a single summary cycle and exit.

    RUN_ONCE=true ./gradlew run
   # or with the installed distribution
    RUN_ONCE=true ./build/install/DashboardSummary/bin/DashboardSummary

## Docker
Build the image (multi-stage Dockerfile provided):

    docker build -t dashboard-summary:latest .

Run the container (pass env variables):

    docker run --rm \
     -e INFLUX_URL=http://influxdb:8086 \
     -e INFLUX_TOKEN=YOUR_TOKEN \
     -e INFLUX_ORG=YOUR_ORG \
     -e INFLUX_BUCKET=YOUR_BUCKET \
     -e GOOGLE_API_KEY=YOUR_GEMINI_KEY \
     -e INFLUX_MEASUREMENT_REGEX=".*" \
     -e INTERVAL_MINUTES=15 \
     -e OUTPUT_MEASUREMENT=dashboard_summary \
     --name dashboard-summary dashboard-summary:latest

## How it works
- For the last `INTERVAL_MINUTES`, the app queries InfluxDB for each matching measurement/field and computes mean, min, max, and last values.
- It composes a compact JSON payload with these stats and asks Gemini to write a short summary.
- The summary is written back to InfluxDB in `OUTPUT_MEASUREMENT` with fields: text (string) and interval_minutes (int), and tag: model.

## Notes and tips
- Ensure the Influx token has read permission for source measurement(s) and write permission for `OUTPUT_MEASUREMENT` in the target bucket.
- `TIMEZONE` only affects when periodic runs are scheduled; the data range is relative to "now" per InfluxDB.
- If you need stricter control over prompting, set `SYSTEM_INSTRUCTION` to guide the model’s tone and focus.

## Troubleshooting
- Missing configuration: the app logs a clear list of missing variables and exits with code 1.
- Gemini API errors: you will see the HTTP status and body in logs; verify `GOOGLE_API_KEY` and model.
- Influx query failures: the app logs a warning per failed query and continues with available data.

## Development
- Build and test: ./gradlew build
- Code entry point: com.pathvariable.smartgarden.summary.Main
- Key classes: Config, GeminiClient, SummaryJob, TimeUtil, MetricKey
