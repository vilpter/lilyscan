package org.lilyscan.eval.synth;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;

/**
 * CPython 3.12's {@code random.Random}, draw for draw: the Mersenne Twister (MT19937), seeding
 * from a string as {@code random.Random("solo:100")} does, and the methods the corpus generator
 * uses ({@code random}, {@code randint}, {@code choice}, {@code choices}). With it the Java
 * generator writes the same pieces as {@code lilyscan/synth/generate.py}.
 */
public final class PyRandom {
    private static final int N = 624;
    private static final int M = 397;
    private final int[] mt = new int[N];
    private int mti;

    /** {@code random.Random(seed)} for a {@code str} seed (seed version 2). */
    public PyRandom(String seed) {
        byte[] a = seed.getBytes(StandardCharsets.UTF_8);
        byte[] digest;
        try {
            digest = MessageDigest.getInstance("SHA-512").digest(a);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
        byte[] all = new byte[a.length + digest.length];
        System.arraycopy(a, 0, all, 0, a.length);
        System.arraycopy(digest, 0, all, a.length, digest.length);
        initByArray(words(new BigInteger(1, all)));
    }

    /** {@code random.Random(n)} for a non-negative int seed. */
    public PyRandom(long seed) {
        initByArray(words(BigInteger.valueOf(Math.abs(seed))));
    }

    /** The integer's 32-bit words, least significant first (at least one). */
    private static int[] words(BigInteger n) {
        int count = Math.max(1, (n.bitLength() + 31) / 32);
        int[] key = new int[count];
        for (int i = 0; i < count; i++) {
            key[i] = n.shiftRight(32 * i).intValue();
        }
        return key;
    }

    private void initGenrand(int s) {
        mt[0] = s;
        for (mti = 1; mti < N; mti++) {
            mt[mti] = 1812433253 * (mt[mti - 1] ^ (mt[mti - 1] >>> 30)) + mti;
        }
    }

    private void initByArray(int[] key) {
        initGenrand(19650218);
        int i = 1, j = 0;
        for (int k = Math.max(N, key.length); k > 0; k--) {
            mt[i] = (mt[i] ^ ((mt[i - 1] ^ (mt[i - 1] >>> 30)) * 1664525)) + key[j] + j;
            i++;
            j++;
            if (i >= N) {
                mt[0] = mt[N - 1];
                i = 1;
            }
            if (j >= key.length) j = 0;
        }
        for (int k = N - 1; k > 0; k--) {
            mt[i] = (mt[i] ^ ((mt[i - 1] ^ (mt[i - 1] >>> 30)) * 1566083941)) - i;
            i++;
            if (i >= N) {
                mt[0] = mt[N - 1];
                i = 1;
            }
        }
        mt[0] = 0x80000000;
    }

    /** One 32-bit output, as an unsigned value. */
    long genrandUint32() {
        int y;
        if (mti >= N) {
            int kk;
            for (kk = 0; kk < N - M; kk++) {
                y = (mt[kk] & 0x80000000) | (mt[kk + 1] & 0x7fffffff);
                mt[kk] = mt[kk + M] ^ (y >>> 1) ^ ((y & 1) != 0 ? 0x9908b0df : 0);
            }
            for (; kk < N - 1; kk++) {
                y = (mt[kk] & 0x80000000) | (mt[kk + 1] & 0x7fffffff);
                mt[kk] = mt[kk + (M - N)] ^ (y >>> 1) ^ ((y & 1) != 0 ? 0x9908b0df : 0);
            }
            y = (mt[N - 1] & 0x80000000) | (mt[0] & 0x7fffffff);
            mt[N - 1] = mt[M - 1] ^ (y >>> 1) ^ ((y & 1) != 0 ? 0x9908b0df : 0);
            mti = 0;
        }
        y = mt[mti++];
        y ^= (y >>> 11);
        y ^= (y << 7) & 0x9d2c5680;
        y ^= (y << 15) & 0xefc60000;
        y ^= (y >>> 18);
        return y & 0xffffffffL;
    }

    /** {@code random()}: a double in [0, 1) with 53 random bits. */
    public double random() {
        long a = genrandUint32() >>> 5, b = genrandUint32() >>> 6;
        return (a * 67108864.0 + b) * (1.0 / 9007199254740992.0);
    }

    /** {@code getrandbits(k)} for {@code 0 <= k <= 32}. */
    long getrandbits(int k) {
        if (k == 0) return 0;
        if (k < 0 || k > 32) throw new IllegalArgumentException("k must be in 0..32");
        return genrandUint32() >>> (32 - k);
    }

    /** {@code _randbelow(n)}: uniform in [0, n). */
    int randbelow(int n) {
        if (n <= 0) throw new IllegalArgumentException("empty range");
        int k = 32 - Integer.numberOfLeadingZeros(n);
        long r = getrandbits(k);
        while (r >= n) r = getrandbits(k);
        return (int) r;
    }

    /** {@code randint(a, b)}: uniform in [a, b]. */
    public int randint(int a, int b) {
        if (b < a) throw new IllegalArgumentException("empty range for randint(" + a + ", " + b + ")");
        return a + randbelow(b - a + 1);
    }

    /** {@code choice(seq)}. */
    public <T> T choice(List<T> seq) {
        if (seq.isEmpty()) throw new IndexOutOfBoundsException("Cannot choose from an empty sequence");
        return seq.get(randbelow(seq.size()));
    }

    /** {@code choices(population, weights)[0]}: one weighted draw (k = 1). */
    public <T> T choice(List<T> population, double[] weights) {
        if (weights.length != population.size()) throw new IllegalArgumentException("weights and population differ");
        double[] cum = new double[weights.length];
        double acc = 0;
        for (int i = 0; i < weights.length; i++) {
            acc += weights[i];
            cum[i] = acc;
        }
        double total = cum[cum.length - 1];
        double x = random() * total;
        // bisect_right(cum, x, 0, n - 1)
        int lo = 0, hi = cum.length - 1;
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;
            if (x < cum[mid]) hi = mid; else lo = mid + 1;
        }
        return population.get(lo);
    }
}
