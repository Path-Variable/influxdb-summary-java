package com.pathvariable.smartgarden.summary;

/**
 * Immutable key describing a metric by measurement and field.
 */
public record MetricKey(String measurement, String field) {}
