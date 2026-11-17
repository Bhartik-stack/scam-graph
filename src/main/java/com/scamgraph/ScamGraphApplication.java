package com.scamgraph;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * ScamGraph: finds mule-account fraud rings in bank transactions using graph theory.
 *
 * Run this class, then open http://localhost:8080 in your browser.
 */
@SpringBootApplication
public class ScamGraphApplication {

    public static void main(String[] args) {
        SpringApplication.run(ScamGraphApplication.class, args);
    }
}
