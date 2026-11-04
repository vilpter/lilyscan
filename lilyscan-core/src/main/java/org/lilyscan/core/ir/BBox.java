package org.lilyscan.core.ir;

import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import java.util.Objects;

/** Pixel rectangle on a source page (0-based page index). */
@JsonPropertyOrder({"page", "x", "y", "w", "h"})
public final class BBox {
    public int page;
    public double x;
    public double y;
    public double w;
    public double h;

    public BBox() {}

    public BBox(int page, double x, double y, double w, double h) {
        this.page = page;
        this.x = x;
        this.y = y;
        this.w = w;
        this.h = h;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof BBox b && b.page == page && b.x == x && b.y == y && b.w == w && b.h == h;
    }

    @Override
    public int hashCode() {
        return Objects.hash(page, x, y, w, h);
    }

    @Override
    public String toString() {
        return "BBox(page=" + page + ", x=" + x + ", y=" + y + ", w=" + w + ", h=" + h + ")";
    }
}
