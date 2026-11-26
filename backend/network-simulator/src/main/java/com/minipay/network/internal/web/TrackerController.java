package com.minipay.network.internal.web;

import com.minipay.network.api.TrackerApi;
import com.minipay.network.api.TrackerSummary;
import com.minipay.network.api.TrackerView;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/tracker")
@Tag(name = "UETR tracker", description = "The network's end-to-end view of a payment: every hop, deduction, status and return")
class TrackerController {

    private final TrackerApi tracker;

    TrackerController(TrackerApi tracker) {
        this.tracker = tracker;
    }

    @GetMapping("/{uetr}")
    @Operation(summary = "Track a payment by UETR across every bank on its route")
    ResponseEntity<TrackerView> track(@PathVariable String uetr) {
        return ResponseEntity.of(tracker.track(uetr));
    }

    @GetMapping
    @Operation(summary = "Payments most recently seen on the network")
    List<TrackerSummary> recent(@RequestParam(defaultValue = "50") int limit) {
        return tracker.recent(limit);
    }
}
