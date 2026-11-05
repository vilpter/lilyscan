package org.lilyscan.core.prepare;

import static org.bytedeco.opencv.global.opencv_core.CV_32F;
import static org.bytedeco.opencv.global.opencv_core.CV_32FC2;
import static org.bytedeco.opencv.global.opencv_imgproc.boxPoints;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import org.bytedeco.javacpp.indexer.FloatIndexer;
import org.bytedeco.opencv.opencv_core.Mat;
import org.bytedeco.opencv.opencv_core.Point2f;
import org.bytedeco.opencv.opencv_core.RotatedRect;
import org.bytedeco.opencv.opencv_core.Size2f;
import org.junit.jupiter.api.Test;

class PageImageTest {
    private static final float[][] SQUARE = {{10, 10}, {90, 10}, {90, 90}, {10, 90}};

    /** A contour as approxPolyDP returns it: N x 1, two channels. */
    @Test
    void pointsOfAContour() {
        Mat m = new Mat(4, 1, CV_32FC2);
        FloatIndexer ix = m.createIndexer();
        for (int i = 0; i < 4; i++) {
            ix.put(i, 0, 0, SQUARE[i][0]);
            ix.put(i, 0, 1, SQUARE[i][1]);
        }
        float[][] p = PageImage.points(m);
        assertEquals(4, p.length);
        for (int i = 0; i < 4; i++) assertArrayEquals(SQUARE[i], p[i]);
    }

    /** A point list as boxPoints returns it: N x 2, one channel (used to overrun, #105). */
    @Test
    void pointsOfABoxPointsResult() {
        Mat box = new Mat();
        boxPoints(new RotatedRect(new Point2f(50, 50), new Size2f(80, 80), 0), box);
        assertEquals(CV_32F, box.type());
        float[][] p = PageImage.points(box);
        assertEquals(4, p.length);
        for (float[] xy : p) {
            assertEquals(2, xy.length);
            assertEquals(1, java.util.Arrays.stream(SQUARE).filter(s -> s[0] == xy[0] && s[1] == xy[1]).count(),
                    () -> "unexpected corner " + xy[0] + "," + xy[1]);
        }
    }
}
