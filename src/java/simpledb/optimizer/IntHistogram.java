package simpledb.optimizer;

import simpledb.execution.Predicate;

/** A class to represent a fixed-width histogram over a single integer-based field.
 */
public class IntHistogram {

    private final int buckets;
    private final int min;
    private final int max;
    private final int[] bucketCounts;
    private int totalCount;
    private final double bucketWidth;

    /**
     * Create a new IntHistogram.
     * 
     * This IntHistogram should maintain a histogram of integer values that it receives.
     * It should split the histogram into "buckets" buckets.
     * 
     * The values that are being histogrammed will be provided one-at-a-time through the "addValue()" function.
     * 
     * Your implementation should use space and have execution time that are both
     * constant with respect to the number of values being histogrammed.  For example, you shouldn't 
     * simply store every value that you see in a sorted list.
     * 
     * @param buckets The number of buckets to split the input value into.
     * @param min The minimum integer value that will ever be passed to this class for histogramming
     * @param max The maximum integer value that will ever be passed to this class for histogramming
     */
    public IntHistogram(int buckets, int min, int max) {
        this.buckets = buckets;
        this.min = min;
        this.max = max;
        this.bucketCounts = new int[buckets];
        this.totalCount = 0;
        // Compute bucket width
        int range = max - min + 1;
        if (range <= buckets) {
            // Each value gets its own bucket, or some buckets are empty
            this.bucketWidth = 1.0;
        } else {
            this.bucketWidth = (double) range / buckets;
        }
    }

    /**
     * Add a value to the set of values that you are keeping a histogram of.
     * @param v Value to add to the histogram
     */
    public void addValue(int v) {
        int bucketIndex = getBucketIndex(v);
        if (bucketIndex >= 0 && bucketIndex < buckets) {
            bucketCounts[bucketIndex]++;
        }
        totalCount++;
    }

    /**
     * Get the bucket index for a value
     */
    private int getBucketIndex(int v) {
        if (v < min) {
            return 0;
        }
        if (v > max) {
            return buckets - 1;
        }
        // Compute which bucket this value belongs to
        int index = (int) ((v - min) / bucketWidth);
        // Handle edge case where v == max
        if (index >= buckets) {
            index = buckets - 1;
        }
        return index;
    }

    /**
     * Estimate the selectivity of a particular predicate and operand on this table.
     * 
     * For example, if "op" is "GREATER_THAN" and "v" is 5, 
     * return your estimate of the fraction of elements that are greater than 5.
     * 
     * @param op Operator
     * @param v Value
     * @return Predicted selectivity of this particular operator and value
     */
    public double estimateSelectivity(Predicate.Op op, int v) {
        if (totalCount == 0) {
            return 0.0;
        }

        int bucketIndex = getBucketIndex(v);
        
        switch (op) {
            case EQUALS:
                return estimateEqualsSelectivity(v, bucketIndex);
            case GREATER_THAN:
                return estimateGreaterThanSelectivity(v, bucketIndex);
            case LESS_THAN:
                return estimateLessThanSelectivity(v, bucketIndex);
            case GREATER_THAN_OR_EQ:
                return estimateEqualsSelectivity(v, bucketIndex) + 
                       estimateGreaterThanSelectivity(v, bucketIndex);
            case LESS_THAN_OR_EQ:
                return estimateEqualsSelectivity(v, bucketIndex) + 
                       estimateLessThanSelectivity(v, bucketIndex);
            case NOT_EQUALS:
                return 1.0 - estimateEqualsSelectivity(v, bucketIndex);
            default:
                return 0.0;
        }
    }

    /**
     * Estimate selectivity for equality predicate
     */
    private double estimateEqualsSelectivity(int v, int bucketIndex) {
        // If v is outside the range [min, max], assume selectivity is 0
        if (v < min || v > max) {
            return 0.0;
        }
        int bucketHeight = bucketCounts[bucketIndex];
        // Assume uniform distribution within the bucket
        // If bucketWidth < 1 (when range < buckets), treat each value as having its own bucket
        double w = Math.max(1.0, bucketWidth);
        double fractionOfBucket = 1.0 / w;
        return (fractionOfBucket * bucketHeight) / totalCount;
    }

    /**
     * Estimate selectivity for greater than predicate
     */
    private double estimateGreaterThanSelectivity(int v, int bucketIndex) {
        // If v is less than min, all values are greater than v
        if (v < min) {
            return 1.0;
        }
        // If v is greater than or equal to max, no values are greater than v
        if (v >= max) {
            return 0.0;
        }

        // Calculate the right boundary of the current bucket
        double bucketRight = min + (bucketIndex + 1) * bucketWidth;
        // Calculate the fraction of the current bucket that is greater than v
        double bucketSelectivity;
        if (bucketWidth <= 1.0) {
            // Each value is in its own bucket
            bucketSelectivity = 0.0;
        } else {
            bucketSelectivity = (bucketRight - v) / bucketWidth;
        }
        
        // Contribution from current bucket
        double selectivity = (bucketCounts[bucketIndex] * bucketSelectivity) / totalCount;
        
        // Add contributions from all buckets to the right
        for (int i = bucketIndex + 1; i < buckets; i++) {
            selectivity += (double) bucketCounts[i] / totalCount;
        }
        
        return selectivity;
    }

    /**
     * Estimate selectivity for less than predicate
     */
    private double estimateLessThanSelectivity(int v, int bucketIndex) {
        // If v is greater than max, all values are less than v
        if (v > max) {
            return 1.0;
        }
        // If v is less than or equal to min, no values are less than v
        if (v <= min) {
            return 0.0;
        }

        // Calculate the left boundary of the current bucket
        double bucketLeft = min + bucketIndex * bucketWidth;
        // Calculate the fraction of the current bucket that is less than v
        double bucketSelectivity;
        if (bucketWidth <= 1.0) {
            // Each value is in its own bucket
            bucketSelectivity = 0.0;
        } else {
            bucketSelectivity = (v - bucketLeft) / bucketWidth;
        }
        
        // Contribution from current bucket
        double selectivity = (bucketCounts[bucketIndex] * bucketSelectivity) / totalCount;
        
        // Add contributions from all buckets to the left
        for (int i = 0; i < bucketIndex; i++) {
            selectivity += (double) bucketCounts[i] / totalCount;
        }
        
        return selectivity;
    }
    
    /**
     * @return
     *     the average selectivity of this histogram.
     *     
     *     This is not an indispensable method to implement the basic
     *     join optimization. It may be needed if you want to
     *     implement a more efficient optimization
     * */
    public double avgSelectivity()
    {
        // Assuming uniform distribution, return 1.0 / totalCount for equals
        if (totalCount == 0) {
            return 0.0;
        }
        return 1.0 / totalCount;
    }
    
    /**
     * @return A string describing this histogram, for debugging purposes
     */
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append("IntHistogram: buckets=").append(buckets)
          .append(", min=").append(min)
          .append(", max=").append(max)
          .append(", totalCount=").append(totalCount)
          .append("\n");
        for (int i = 0; i < buckets; i++) {
            double bucketLeft = min + i * bucketWidth;
            double bucketRight = min + (i + 1) * bucketWidth;
            sb.append("  Bucket[").append(i).append("]: [")
              .append(String.format("%.1f", bucketLeft)).append(", ")
              .append(String.format("%.1f", bucketRight)).append("): ")
              .append(bucketCounts[i]).append("\n");
        }
        return sb.toString();
    }
}
