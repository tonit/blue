package org.acme;

import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

@Path("/hello")
public class GreetingResource {

    OpenTelemetry otel;

    private Tracer tracer;

    GreetingResource(OpenTelemetry otel) {
        this.otel = otel;
        tracer = otel.getTracer("instrumentation-scope-name", "instrumentation-scope-version");
    }

    @GET
    @Produces(MediaType.TEXT_PLAIN)
    public String hello() {
        Span span = tracer.spanBuilder("rollTheDice").startSpan();

        callEndpoint(span);
        new Dice(1,6,otel).rollTheDice(100);
        // Make the span the current span
        try (Scope scope = span.makeCurrent()) {
            return "Hello you";
        } catch(Throwable t) {
            span.recordException(t);
            throw t;
        } finally {
            span.end();
        }
    }

    public void callEndpoint(Span parent) {
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


}