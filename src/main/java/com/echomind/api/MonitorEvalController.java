package com.echomind.api;

import com.echomind.api.dto.EvalRunRequest;
import com.echomind.evaluation.EndToEndEvaluator;
import com.echomind.monitor.PerformanceMonitor;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@Tag(name = "Monitoring and Evaluation", description = "Monitoring, metrics, and evaluation endpoints")
public class MonitorEvalController {

    private final PerformanceMonitor performanceMonitor;
    private final EndToEndEvaluator evaluator;
    private final PrometheusMeterRegistry prometheusMeterRegistry;

    public MonitorEvalController(PerformanceMonitor performanceMonitor, EndToEndEvaluator evaluator, PrometheusMeterRegistry prometheusMeterRegistry) {
        this.performanceMonitor = performanceMonitor;
        this.evaluator = evaluator;
        this.prometheusMeterRegistry = prometheusMeterRegistry;
    }

    @GetMapping("/monitor")
    @Operation(summary = "Monitoring summary")
    public Map<String, Object> monitor() {
        return performanceMonitor.summary();
    }

    @GetMapping(value = "/metrics", produces = "text/plain; version=0.0.4; charset=utf-8")
    @Operation(summary = "Prometheus metrics")
    public ResponseEntity<String> metrics() {
        return ResponseEntity.ok(prometheusMeterRegistry.scrape());
    }

    @PostMapping(value = "/eval/run", consumes = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Run evaluation")
    public Map<String, Object> eval(@RequestBody(required = false) EvalRunRequest request) {
        return evaluator.run(request);
    }
}
