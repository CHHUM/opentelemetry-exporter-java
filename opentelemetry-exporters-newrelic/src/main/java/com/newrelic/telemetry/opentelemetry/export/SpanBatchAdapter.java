/*
 * Copyright 2019 New Relic Corporation. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.newrelic.telemetry.opentelemetry.export;

import static com.newrelic.telemetry.opentelemetry.export.AttributesSupport.makeAttributes;
import static java.util.concurrent.TimeUnit.NANOSECONDS;

import com.newrelic.telemetry.Attributes;
import com.newrelic.telemetry.logs.Log;
import com.newrelic.telemetry.logs.LogBatch;
import com.newrelic.telemetry.spans.Span;
import com.newrelic.telemetry.spans.Span.SpanBuilder;
import com.newrelic.telemetry.spans.SpanBatch;
import io.opentelemetry.common.AttributeValue;
import io.opentelemetry.sdk.resources.Resource;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.trace.SpanId;
import io.opentelemetry.trace.Status;
import java.util.Collection;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

class SpanBatchAdapter {

  private final Attributes commonAttributes;

  SpanBatchAdapter(Attributes commonAttributes) {
    this.commonAttributes =
        commonAttributes
            .copy()
            .put("instrumentation.provider", "opentelemetry")
            .put("collector.name", "newrelic-opentelemetry-exporter");
  }

  SpanBatch adaptToSpanBatch(Collection<SpanData> openTelemetrySpans) {
    Collection<Span> newRelicSpans =
        openTelemetrySpans
            .stream()
            .map(SpanBatchAdapter::makeNewRelicSpan)
            .collect(Collectors.toSet());
    return new SpanBatch(newRelicSpans, commonAttributes);
  }

  // todo: this needs unit testing!
  LogBatch adaptEventsAsLogs(Collection<SpanData> openTelemetrySpans) {
    Collection<Log> logs =
        openTelemetrySpans.stream().flatMap(this::extractEvents).collect(Collectors.toSet());
    return new LogBatch(logs, commonAttributes);
  }

  private Stream<Log> extractEvents(SpanData span) {
    return span.getTimedEvents()
        .stream()
        .map(
            timedEvent ->
                Log.builder()
                    .message(timedEvent.getName())
                    .timestamp(NANOSECONDS.toMillis(timedEvent.getEpochNanos()))
                    .attributes(
                        makeAttributes(timedEvent.getAttributes())
                            .put("span.id", span.getSpanId().toLowerBase16())
                            .put("trace.id", span.getTraceId().toLowerBase16()))
                    .build());
  }

  private static com.newrelic.telemetry.spans.Span makeNewRelicSpan(SpanData span) {
    SpanBuilder spanBuilder =
        com.newrelic.telemetry.spans.Span.builder(span.getSpanId().toLowerBase16())
            .name(span.getName().isEmpty() ? null : span.getName())
            .parentId(makeParentSpanId(span.getParentSpanId()))
            .traceId(span.getTraceId().toLowerBase16())
            .attributes(generateSpanAttributes(span));

    spanBuilder.timestamp(calculateTimestampMillis(span));
    spanBuilder.durationMs(calculateDuration(span));
    return spanBuilder.build();
  }

  private static String makeParentSpanId(SpanId parentSpanId) {
    if (parentSpanId.isValid()) {
      return parentSpanId.toLowerBase16();
    }
    return null;
  }

  private static Attributes generateSpanAttributes(SpanData span) {
    Attributes attributes = new Attributes();
    attributes = createIntrinsicAttributes(span, attributes);
    attributes = addPossibleErrorAttribute(span, attributes);
    attributes = addPossibleInstrumentationAttributes(span, attributes);
    return addResourceAttributes(span, attributes);
  }

  private static Attributes addPossibleInstrumentationAttributes(
      SpanData span, Attributes attributes) {
    Attributes updatedAttributes =
        AttributesSupport.populateLibraryInfo(attributes, span.getInstrumentationLibraryInfo());
    return AttributesSupport.addResourceAttributes(updatedAttributes, span.getResource());
  }

  private static Attributes createIntrinsicAttributes(SpanData span, Attributes attributes) {
    Map<String, AttributeValue> originalAttributes = span.getAttributes();
    AttributesSupport.putInAttributes(attributes, originalAttributes);
    attributes.put("span.kind", span.getKind().name());
    return attributes;
  }

  private static Attributes addPossibleErrorAttribute(SpanData span, Attributes attributes) {
    Status status = span.getStatus();
    if (!status.isOk() && status.getDescription() != null && !status.getDescription().isEmpty()) {
      attributes.put("error.message", status.getDescription());
    }
    return attributes;
  }

  private static Attributes addResourceAttributes(SpanData span, Attributes attributes) {
    Resource resource = span.getResource();
    if (resource != null) {
      Map<String, AttributeValue> labelsMap = resource.getAttributes();
      AttributesSupport.putInAttributes(attributes, labelsMap);
    }
    return attributes;
  }

  private static double calculateDuration(SpanData span) {
    long startTime = span.getStartEpochNanos();
    long endTime = span.getEndEpochNanos();

    long nanoDifference = endTime - startTime;
    // note: we don't use NANOSECONDS.toMillis here, because we want to see  sub-ms resolution.
    return nanoDifference / 1_000_000d;
  }

  private static long calculateTimestampMillis(SpanData span) {
    return NANOSECONDS.toMillis(span.getStartEpochNanos());
  }
}
