package com.scamgraph.api;

import java.util.List;

/** Money trail around one account, ordered so it can be animated hop by hop. */
public record TrailDto(String account, List<TrailStep> steps) {
}
