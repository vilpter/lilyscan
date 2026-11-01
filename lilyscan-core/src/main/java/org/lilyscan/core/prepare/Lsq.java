package org.lilyscan.core.prepare;

import java.util.List;

/** Linear least squares by Householder QR (in place of NumPy's {@code lstsq}). */
final class Lsq {
    private Lsq() {}

    /** The {@code cols} coefficients minimizing |A x - b|; minimum-norm columns set to 0 when rank-deficient. */
    static double[] solve(List<double[]> rows, List<Double> rhs, int cols) {
        int m = rows.size();
        double[][] a = new double[m][cols];
        double[] b = new double[m];
        for (int i = 0; i < m; i++) {
            System.arraycopy(rows.get(i), 0, a[i], 0, cols);
            b[i] = rhs.get(i);
        }
        int n = Math.min(m, cols);
        double[] diag = new double[cols];
        for (int k = 0; k < n; k++) {
            double norm = 0;
            for (int i = k; i < m; i++) norm = Math.hypot(norm, a[i][k]);
            if (norm == 0) continue;
            if (a[k][k] < 0) norm = -norm;
            for (int i = k; i < m; i++) a[i][k] /= norm;
            a[k][k] += 1.0;
            for (int j = k + 1; j < cols; j++) {
                double s = 0;
                for (int i = k; i < m; i++) s += a[i][k] * a[i][j];
                s = -s / a[k][k];
                for (int i = k; i < m; i++) a[i][j] += s * a[i][k];
            }
            double s = 0;
            for (int i = k; i < m; i++) s += a[i][k] * b[i];
            s = -s / a[k][k];
            for (int i = k; i < m; i++) b[i] += s * a[i][k];
            diag[k] = -norm;
        }
        double[] x = new double[cols];
        for (int k = n - 1; k >= 0; k--) {
            if (diag[k] == 0) continue;
            double s = b[k];
            for (int j = k + 1; j < cols; j++) s -= a[k][j] * x[j];
            x[k] = s / diag[k];
        }
        return x;
    }
}
