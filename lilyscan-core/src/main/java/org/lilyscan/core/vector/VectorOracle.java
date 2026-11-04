package org.lilyscan.core.vector;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.List;
import org.lilyscan.core.ir.Score;
import org.lilyscan.core.omr.OmrBook;
import org.lilyscan.core.repair.Repair;

/** Stage 4 (port of {@code lilyscan/vector/__init__.py}): the born-digital PDF as a correction oracle. */
public final class VectorOracle {
    private VectorOracle() {}

    /** Corrects the engine's score from the PDF it was read from; returns the changes. */
    public static List<Repair> apply(Score score, Path pdf, OmrBook book) {
        try {
            return Tuplets.apply(score, VectorPdf.read(pdf), book, new Tuplets.Stats());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
