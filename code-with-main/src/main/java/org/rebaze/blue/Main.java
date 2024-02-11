package org.rebaze.blue;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.baggage.propagation.W3CBaggagePropagator;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.propagation.ContextPropagators;
import io.opentelemetry.context.propagation.TextMapPropagator;
import io.opentelemetry.exporter.logging.LoggingMetricExporter;
import io.opentelemetry.exporter.logging.LoggingSpanExporter;
import io.opentelemetry.exporter.logging.SystemOutLogRecordExporter;
import io.opentelemetry.exporter.otlp.trace.OtlpGrpcSpanExporter;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.logs.SdkLoggerProvider;
import io.opentelemetry.sdk.logs.export.BatchLogRecordProcessor;
import io.opentelemetry.sdk.metrics.SdkMeterProvider;
import io.opentelemetry.sdk.metrics.export.PeriodicMetricReader;
import io.opentelemetry.sdk.resources.Resource;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.export.BatchSpanProcessor;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import io.opentelemetry.semconv.ResourceAttributes;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

public class Main
{
    public static void main(String[] args) throws Exception {
        //server();
        OpenTelemetry otel = openTelemetry();
        Tracer tracer = otel.getTracer("instrumentation-scope-name", "instrumentation-scope-version");
        Span span = tracer.spanBuilder("main").startSpan();
        try {
            new Main().callEndpoint(tracer, span);
        } finally {
            span.end();
        }

        Thread.currentThread().join();
    }

    public void callEndpoint(Tracer tracer, Span parent) {
        HttpClient client = HttpClient.newHttpClient();
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://example.com"))
                .build();

        List<CompletableFuture<Void>> futures = new ArrayList<>();

        for (int i = 0; i < 100; i++) {
            Span span = tracer.spanBuilder("call-endpoint-" + i)
                    .setParent(Context.current().with(parent))
                    .startSpan();
            futures.add(client.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                    .thenApply(response -> {
                        System.out.println("Response code: " + response.statusCode());
                        return response;
                    })
                    .thenAccept(response -> span.end())
                    .exceptionally(e -> {
                        System.out.println("Error: " + e.getMessage());
                        span.recordException(e);
                        span.end();
                        return null;
                    }));
        }

        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
    }

    private static void server() throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress(8000), 0);
        OpenTelemetry openTelemetry = openTelemetry();

        server.createContext("/ping", new HttpHandler() {
            @Override
            public void handle(HttpExchange exchange) throws IOException {
                String response = "pong";
                exchange.sendResponseHeaders(200, response.length());
                OutputStream os = exchange.getResponseBody();
                os.write(response.getBytes());
                os.close();
            }
        });

        server.setExecutor(null); // creates a default executor
        server.start();
    }

    public static OpenTelemetry openTelemetry() {
        System.out.println("Creating OpenTelemetry instance...");
        Resource resource = Resource.getDefault().toBuilder().put(ResourceAttributes.SERVICE_NAME, "main").put(ResourceAttributes.SERVICE_VERSION, "0.1.0").build();

        SdkTracerProvider sdkTracerProvider = SdkTracerProvider.builder()
                .addSpanProcessor(SimpleSpanProcessor.create(LoggingSpanExporter.create()))
                .addSpanProcessor(BatchSpanProcessor.builder(OtlpGrpcSpanExporter.builder()
                        .setEndpoint("http://localhost:4317")
                        .build()).build())
                .setResource(resource)
                .build();

        SdkMeterProvider sdkMeterProvider = SdkMeterProvider.builder()
                .registerMetricReader(PeriodicMetricReader.builder(LoggingMetricExporter.create()).build())
                .setResource(resource)
                .build();

        SdkLoggerProvider sdkLoggerProvider = SdkLoggerProvider.builder()
                .addLogRecordProcessor(BatchLogRecordProcessor.builder(SystemOutLogRecordExporter.create()).build())
                .setResource(resource)
                .build();

        OpenTelemetry openTelemetry = OpenTelemetrySdk.builder()
                .setTracerProvider(sdkTracerProvider)
                .setMeterProvider(sdkMeterProvider)
                .setLoggerProvider(sdkLoggerProvider)
                .setPropagators(ContextPropagators.create(TextMapPropagator.composite(W3CTraceContextPropagator.getInstance(), W3CBaggagePropagator.getInstance())))
                .buildAndRegisterGlobal();

        return openTelemetry;
    }
}
