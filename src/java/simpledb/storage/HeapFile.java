package simpledb.storage;

import simpledb.common.Database;
import simpledb.common.DbException;
import simpledb.common.Debug;
import simpledb.common.Permissions;
import simpledb.transaction.TransactionAbortedException;
import simpledb.transaction.TransactionId;

import java.io.*;
import java.util.*;

/**
 * HeapFile is an implementation of a DbFile that stores a collection of tuples
 * in no particular order. Tuples are stored on pages, each of which is a fixed
 * size, and the file is simply a collection of those pages. HeapFile works
 * closely with HeapPage. The format of HeapPages is described in the HeapPage
 * constructor.
 * 
 * @see HeapPage#HeapPage
 * @author Sam Madden
 */
public class HeapFile implements DbFile {

    private final File f;
    private final TupleDesc td;

    /**
     * Constructs a heap file backed by the specified file.
     * 
     * @param f
     *            the file that stores the on-disk backing store for this heap
     *            file.
     */
    public HeapFile(File f, TupleDesc td) {
        this.f = f;
        this.td = td;
    }

    /**
     * Returns the File backing this HeapFile on disk.
     * 
     * @return the File backing this HeapFile on disk.
     */
    public File getFile() {
        return f;
    }

    /**
     * Returns an ID uniquely identifying this HeapFile. Implementation note:
     * you will need to generate this tableid somewhere to ensure that each
     * HeapFile has a "unique id," and that you always return the same value for
     * a particular HeapFile. We suggest hashing the absolute file name of the
     * file underlying the heapfile, i.e. f.getAbsoluteFile().hashCode().
     * 
     * @return an ID uniquely identifying this HeapFile.
     */
    public int getId() {
        return f.getAbsoluteFile().hashCode();
    }

    /**
     * Returns the TupleDesc of the table stored in this DbFile.
     * 
     * @return TupleDesc of this DbFile.
     */
    public TupleDesc getTupleDesc() {
        return td;
    }

    // see DbFile.java for javadocs
    public Page readPage(PageId pid) {
        int pgNo = pid.getPageNumber();
        int offset = pgNo * BufferPool.getPageSize();
        byte[] data = new byte[BufferPool.getPageSize()];
        
        try (RandomAccessFile raf = new RandomAccessFile(f, "r")) {
            raf.seek(offset);
            int bytesRead = raf.read(data);
            if (bytesRead != BufferPool.getPageSize()) {
                throw new IllegalArgumentException("Failed to read full page");
            }
            return new HeapPage((HeapPageId) pid, data);
        } catch (IOException e) {
            throw new RuntimeException("Failed to read page", e);
        }
    }

    // see DbFile.java for javadocs
    public void writePage(Page page) throws IOException {
        byte[] data = page.getPageData();
        int offset = page.getId().getPageNumber() * BufferPool.getPageSize();
        
        try (RandomAccessFile raf = new RandomAccessFile(f, "rw")) {
            raf.seek(offset);
            raf.write(data);
        }
    }

    /**
     * Returns the number of pages in this HeapFile.
     */
    public int numPages() {
        return (int) (f.length() / BufferPool.getPageSize());
    }

    // see DbFile.java for javadocs
    public List<Page> insertTuple(TransactionId tid, Tuple t)
            throws DbException, IOException, TransactionAbortedException {
        // Find a page with an empty slot
        for (int i = 0; i < numPages(); i++) {
            HeapPageId pid = new HeapPageId(getId(), i);
            HeapPage page = (HeapPage) Database.getBufferPool().getPage(tid, pid, Permissions.READ_WRITE);
            if (page.getNumEmptySlots() > 0) {
                page.insertTuple(t);
                page.markDirty(true, tid);
                return Collections.singletonList(page);
            }
        }
        
        // No page with empty slot, create a new page
        HeapPageId newPid = new HeapPageId(getId(), numPages());
        HeapPage newPage = new HeapPage(newPid, HeapPage.createEmptyPageData());
        newPage.insertTuple(t);
        newPage.markDirty(true, tid);
        
        // Append to file
        writePage(newPage);
        
        return Collections.singletonList(newPage);
    }

    // see DbFile.java for javadocs
    public ArrayList<Page> deleteTuple(TransactionId tid, Tuple t) throws DbException,
            TransactionAbortedException {
        RecordId rid = t.getRecordId();
        if (rid == null) {
            throw new DbException("Tuple has no RecordId");
        }
        PageId pid = rid.getPageId();
        HeapPage page = (HeapPage) Database.getBufferPool().getPage(tid, pid, Permissions.READ_WRITE);
        page.deleteTuple(t);
        page.markDirty(true, tid);
        ArrayList<Page> result = new ArrayList<>();
        result.add(page);
        return result;
    }

    // see DbFile.java for javadocs
    public DbFileIterator iterator(TransactionId tid) {
        return new HeapFileIterator(this, tid);
    }
    
    /**
     * Helper class for iterating over all tuples in the file
     */
    private static class HeapFileIterator implements DbFileIterator {
        private final HeapFile heapFile;
        private final TransactionId tid;
        private int currentPage;
        private Iterator<Tuple> currentTupleIterator;
        private boolean open;
        
        public HeapFileIterator(HeapFile heapFile, TransactionId tid) {
            this.heapFile = heapFile;
            this.tid = tid;
            this.currentPage = 0;
            this.open = false;
        }
        
        @Override
        public void open() throws DbException, TransactionAbortedException {
            open = true;
            currentPage = 0;
            currentTupleIterator = getTupleIteratorForPage(0);
        }
        
        private Iterator<Tuple> getTupleIteratorForPage(int pageNo) throws TransactionAbortedException, DbException {
            if (pageNo < 0 || pageNo >= heapFile.numPages()) {
                return null;
            }
            HeapPageId pid = new HeapPageId(heapFile.getId(), pageNo);
            HeapPage page = (HeapPage) Database.getBufferPool().getPage(tid, pid, Permissions.READ_ONLY);
            return page.iterator();
        }

        @Override
        public boolean hasNext() throws DbException, TransactionAbortedException {
            if (!open) {
                return false;
            }
            
            while (currentTupleIterator != null) {
                if (currentTupleIterator.hasNext()) {
                    return true;
                }
                // Move to next page
                currentPage++;
                currentTupleIterator = getTupleIteratorForPage(currentPage);
                if (currentPage >= heapFile.numPages()) {
                    return false;
                }
            }
            return false;
        }

        @Override
        public Tuple next() throws DbException, TransactionAbortedException, NoSuchElementException {
            if (!open || currentTupleIterator == null) {
                throw new NoSuchElementException();
            }
            return currentTupleIterator.next();
        }

        @Override
        public void rewind() throws DbException, TransactionAbortedException {
            close();
            open();
        }

        @Override
        public void close() {
            open = false;
            currentTupleIterator = null;
        }
    }

}
