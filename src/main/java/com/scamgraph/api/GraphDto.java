package com.scamgraph.api;

import java.util.List;

/** Everything needed to draw the network. */
public record GraphDto(List<NodeDto> nodes, List<EdgeDto> edges) {
}
