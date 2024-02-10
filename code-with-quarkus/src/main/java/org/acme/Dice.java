package org.acme;

import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Context;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

public class Dice {
    private int min;
    private int max;
    private Tracer tracer;

    public Dice(int min, int max, OpenTelemetry openTelemetry) {
        this.min = min;
        this.max = max;
        this.tracer = openTelemetry.getTracer(Dice.class.getName(), "0.1.0");
    }

    public Dice(int min, int max) {
        this(min, max, OpenTelemetry.noop());
    }

    public List<Integer> rollTheDice(int rolls) {
        Span parentSpan = tracer.spanBuilder("parent").startSpan();
        List<Integer> results = new ArrayList<Integer>();
        try {
            for (int i = 0; i < rolls; i++) {
                results.add(this.rollOnce(parentSpan));
            }
            return results;
        } finally {
            parentSpan.end();
        }
    }

    private int rollOnce(Span parentSpan) {
        Span childSpan = tracer.spanBuilder("child")
                .setParent(Context.current().with(parentSpan))
                .startSpan();
        try {
            return ThreadLocalRandom.current().nextInt(this.min, this.max + 1);
        } finally {
            childSpan.end();
        }
    }
}
