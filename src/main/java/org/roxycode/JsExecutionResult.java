package org.roxycode;

import io.micronaut.serde.annotation.Serdeable;

@Serdeable
public record JsExecutionResult(
    boolean success,
    String result,
    String error,
    String logs
) {}
