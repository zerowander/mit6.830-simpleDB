package simpledb.execution;

import simpledb.common.Type;
import simpledb.storage.*;

import java.util.*;

/**
 * Knows how to compute some aggregate over a set of IntFields.
 */
public class IntegerAggregator implements Aggregator {

    private static final long serialVersionUID = 1L;

    private final int gbfield;
    private final Type gbfieldtype;
    private final int afield;
    private final Op what;
    
    private final Map<Field, Integer> groupAggregates;
    private final Map<Field, Integer> groupCounts;
    private int noGroupingAggregate;
    private int noGroupingCount;

    /**
     * Aggregate constructor
     * 
     * @param gbfield
     *            the 0-based index of the group-by field in the tuple, or
     *            NO_GROUPING if there is no grouping
     * @param gbfieldtype
     *            the type of the group by field (e.g., Type.INT_TYPE), or null
     *            if there is no grouping
     * @param afield
     *            the 0-based index of the aggregate field in the tuple
     * @param what
     *            the aggregation operator
     */

    public IntegerAggregator(int gbfield, Type gbfieldtype, int afield, Op what) {
        this.gbfield = gbfield;
        this.gbfieldtype = gbfieldtype;
        this.afield = afield;
        this.what = what;
        this.groupAggregates = new HashMap<>();
        this.groupCounts = new HashMap<>();
        this.noGroupingAggregate = 0;
        this.noGroupingCount = 0;
    }

    /**
     * Merge a new tuple into the aggregate, grouping as indicated in the
     * constructor
     * 
     * @param tup
     *            the Tuple containing an aggregate field and a group-by field
     */
    public void mergeTupleIntoGroup(Tuple tup) {
        Field groupField = (gbfield == NO_GROUPING) ? null : tup.getField(gbfield);
        IntField aggField = (IntField) tup.getField(afield);
        int value = aggField.getValue();
        
        if (gbfield == NO_GROUPING) {
            noGroupingCount++;
            switch (what) {
                case MIN:
                    if (noGroupingCount == 1 || value < noGroupingAggregate) {
                        noGroupingAggregate = value;
                    }
                    break;
                case MAX:
                    if (noGroupingCount == 1 || value > noGroupingAggregate) {
                        noGroupingAggregate = value;
                    }
                    break;
                case SUM:
                    noGroupingAggregate += value;
                    break;
                case AVG:
                    noGroupingAggregate += value;
                    break;
                case COUNT:
                    noGroupingAggregate = noGroupingCount;
                    break;
            }
        } else {
            groupCounts.put(groupField, groupCounts.getOrDefault(groupField, 0) + 1);
            int currentCount = groupCounts.get(groupField);
            int currentAgg = groupAggregates.getOrDefault(groupField, 0);
            
            switch (what) {
                case MIN:
                    if (currentCount == 1 || value < currentAgg) {
                        groupAggregates.put(groupField, value);
                    }
                    break;
                case MAX:
                    if (currentCount == 1 || value > currentAgg) {
                        groupAggregates.put(groupField, value);
                    }
                    break;
                case SUM:
                    groupAggregates.put(groupField, currentAgg + value);
                    break;
                case AVG:
                    groupAggregates.put(groupField, currentAgg + value);
                    break;
                case COUNT:
                    groupAggregates.put(groupField, currentCount);
                    break;
            }
        }
    }

    /**
     * Create a OpIterator over group aggregate results.
     * 
     * @return a OpIterator whose tuples are the pair (groupVal, aggregateVal)
     *         if using group, or a single (aggregateVal) if no grouping. The
     *         aggregateVal is determined by the type of aggregate specified in
     *         the constructor.
     */
    public OpIterator iterator() {
        List<Tuple> tuples = new ArrayList<>();
        
        if (gbfield == NO_GROUPING) {
            // No grouping - return single tuple with aggregate value
            TupleDesc td = new TupleDesc(new Type[] { Type.INT_TYPE });
            Tuple t = new Tuple(td);
            int value = (what == Op.AVG) ? noGroupingAggregate / noGroupingCount : noGroupingAggregate;
            t.setField(0, new IntField(value));
            tuples.add(t);
        } else {
            // Grouping - return tuples with (groupVal, aggregateVal)
            TupleDesc td = new TupleDesc(new Type[] { gbfieldtype, Type.INT_TYPE });
            for (Map.Entry<Field, Integer> entry : groupAggregates.entrySet()) {
                Tuple t = new Tuple(td);
                t.setField(0, entry.getKey());
                int value = entry.getValue();
                if (what == Op.AVG) {
                    value = value / groupCounts.get(entry.getKey());
                }
                t.setField(1, new IntField(value));
                tuples.add(t);
            }
        }
        
        return new TupleIterator(getTupleDesc(), tuples);
    }
    
    private TupleDesc getTupleDesc() {
        if (gbfield == NO_GROUPING) {
            return new TupleDesc(new Type[] { Type.INT_TYPE });
        } else {
            return new TupleDesc(new Type[] { gbfieldtype, Type.INT_TYPE });
        }
    }
    
    /**
     * Helper class to iterate over tuples
     */
    private static class TupleIterator implements OpIterator {
        private final TupleDesc td;
        private final List<Tuple> tuples;
        private int index;
        private boolean open;
        
        public TupleIterator(TupleDesc td, List<Tuple> tuples) {
            this.td = td;
            this.tuples = tuples;
            this.index = 0;
            this.open = false;
        }
        
        public void open() {
            open = true;
            index = 0;
        }
        
        public boolean hasNext() {
            return open && index < tuples.size();
        }
        
        public Tuple next() throws NoSuchElementException {
            if (!hasNext()) {
                throw new NoSuchElementException();
            }
            return tuples.get(index++);
        }
        
        public void rewind() {
            index = 0;
        }
        
        public TupleDesc getTupleDesc() {
            return td;
        }
        
        public void close() {
            open = false;
        }
    }

}
