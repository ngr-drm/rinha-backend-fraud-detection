package com.rb.fraud.api;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.concurrent.atomic.AtomicBoolean;

@RestController
public class ReadyController {

    private static final AtomicBoolean ready = new AtomicBoolean(false);

    /**
     * Chamado após mmap de vectors.bin + vptree.bin
     */
    public static void markReady() {
        ready.set(true);
    }

    @GetMapping("/ready")
    public ResponseEntity<Void> ready() {
        if (ready.get()) {
            return ResponseEntity.ok().build();
        }
        return ResponseEntity.status(503).build();
    }
}

