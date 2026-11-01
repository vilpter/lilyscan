package org.lilyscan.core.ir;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

/** A tuplet ratio {@code (actual, normal)}, e.g. (3, 2); serialized as {@code [3, 2]}. */
@JsonFormat(shape = JsonFormat.Shape.ARRAY)
@JsonPropertyOrder({"actual", "normal"})
public record Tuplet(@JsonProperty("actual") int actual, @JsonProperty("normal") int normal) {}
