package org.jboss.pnc.cleaner.common;

public class LatencyMiniMax {
    static final double EPSILON = 1.0E-8;
    private double maxLatency = 1.0E-4;
    private double minLatency = 1.0E-4;

    public double getMinLatency() {
        return minLatency;
    }

    public double getMaxLatency() {
        return maxLatency;
    }

    public void update(double latency) {
        if ((latency - maxLatency) > EPSILON) {
            maxLatency = latency;
        }
        if ((minLatency - latency) > EPSILON) {
            minLatency = latency;
        }
    }

    public String toString() {
        return String.format("latency:\n\tlow  = %f\n\thigh = %f\n", getMinLatency(), getMaxLatency());
    }
}