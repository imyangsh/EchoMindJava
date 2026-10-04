package com.echomind.api;

import com.echomind.api.dto.EvalRunRequest;
import com.echomind.evaluation.EvalJobService;
import com.echomind.monitor.PerformanceMonitor;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@Tag(name = "Monitoring and Evaluation", description = "Monitoring, metrics, and evaluation endpoints")
public class MonitorEvalController {

    private final PerformanceMonitor performanceMonitor;
    private final EvalJobService evalJobService;
    private final PrometheusMeterRegistry prometheusMeterRegistry;

    public MonitorEvalController(PerformanceMonitor performanceMonitor, EvalJobService evalJobService, PrometheusMeterRegistry prometheusMeterRegistry) {
        this.performanceMonitor = performanceMonitor;
        this.evalJobService = evalJobService;
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
    @Operation(summary = "Submit an evaluation job (async); poll /eval/status/{jobId} for the report")
    public ResponseEntity<Map<String, Object>> eval(@Valid @RequestBody(required = false) EvalRunRequest request) {
        //评测跑批含大量串行 LLM 调用，同步执行会长时间占用 Tomcat 线程；
        //改为异步任务并返回 202 + jobId
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(evalJobService.submit(request));
    }

    @GetMapping("/eval/status/{jobId}")
    @Operation(summary = "Evaluation job status and report")
    public Map<String, Object> evalStatus(@PathVariable String jobId) {
        return evalJobService.status(jobId);
    }
}
