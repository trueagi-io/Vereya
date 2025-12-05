package io.singularitynet.tests;

/** Simple wrapper for a received TCP payload with a capture timestamp. */
public final class TimestampedByteVector {
    public final long timestampNs;
    public final byte[] data;
    public TimestampedByteVector(long timestampNs, byte[] data) {
        this.timestampNs = timestampNs;
        this.data = data;
    }
    public double getTimestampSeconds() { return timestampNs / 1_000_000_000.0; }
}

