package com.scamgraph.api;

/** One hop in a money trail. hop &lt; 0 = money coming in (upstream), hop &gt; 0 = money going out (downstream). */
public record TrailStep(String from, String to, long amount, int count, int hop) {
}
