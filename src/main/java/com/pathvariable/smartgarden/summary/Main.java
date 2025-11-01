package com.pathvariable.smartgarden.summary;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

import static com.pathvariable.smartgarden.summary.TimeUtil.computeInitialDelayToNextInterval;
import static java.lang.Thread.currentThread;
import static java.util.concurrent.TimeUnit.*;

public class Main {
    private static final Logger LOG = LoggerFactory.getLogger(Main.class);

    public static void main(String[] args) {
        Config config = Config.fromEnv();
        if (!config.isValid()) {
            LOG.error("Missing required configuration. Please set the required environment variables.\n{}", config.missingDescription());
            System.exit(1);
        }

        GeminiClient geminiClient = new GeminiClient(config);
        SummaryJob summaryJob = new SummaryJob(geminiClient);

        Runnable job = () -> {
            try {
                summaryJob.runOnce(config);
            } catch (Exception e) {
                LOG.error("Job execution failed", e);
            }
        };

        if (config.runOnce()) {
            job.run();
            return;
        }

        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "summary-scheduler");
            t.setDaemon(true);
            return t;
        });

        long initialDelayMs = computeInitialDelayToNextInterval(config.intervalMinutes(), config.timezone());
        long periodMs = MINUTES.toMillis(config.intervalMinutes());
        LOG.info("Scheduling job. First run in {}s, then every {} minutes.", initialDelayMs / 1000, config.intervalMinutes());
        scheduler.scheduleAtFixedRate(job, initialDelayMs, periodMs, MILLISECONDS);

        try {
            while (!scheduler.awaitTermination(60, SECONDS)) {
                LOG.debug("Waiting for scheduler to terminate");
            }
        } catch (InterruptedException ignored) {
            currentThread().interrupt();
            LOG.debug("Main thread interrupted");
        }
    }
}