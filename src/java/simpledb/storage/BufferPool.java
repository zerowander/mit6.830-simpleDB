package simpledb.storage;

import simpledb.common.Database;
import simpledb.common.Permissions;
import simpledb.common.DbException;
import simpledb.transaction.TransactionAbortedException;
import simpledb.transaction.TransactionId;
import simpledb.transaction.LockManager;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * BufferPool manages the reading and writing of pages into memory from
 * disk. Access methods call into it to retrieve pages, and it fetches
 * pages from the appropriate location.
 * <p>
 * The BufferPool is also responsible for locking;  when a transaction fetches
 * a page, BufferPool checks that the transaction has the appropriate
 * locks to read/write the page.
 */
public class BufferPool {

    /** Bytes per page, including header. */
    private static final int DEFAULT_PAGE_SIZE = 4096;
    private static int pageSize = DEFAULT_PAGE_SIZE;

    /** Default number of pages passed to the constructor. This is used by
     other classes. BufferPool should use the numPages argument to the
     constructor instead. */
    public static final int DEFAULT_PAGES = 50;

    private final int numPages;
    private final ConcurrentHashMap<PageId, Page> pageCache;
    private final LockManager lockManager;

    /**
     * Creates a BufferPool that caches up to numPages pages.
     *
     * @param numPages maximum number of pages in this buffer pool.
     */
    public BufferPool(int numPages) {
        this.numPages = numPages;
        this.pageCache = new ConcurrentHashMap<>();
        this.lockManager = new LockManager();
    }

    public static int getPageSize() {
        return pageSize;
    }

    public static void setPageSize(int pageSize) {
        BufferPool.pageSize = pageSize;
    }

    public static void resetPageSize() {
        BufferPool.pageSize = DEFAULT_PAGE_SIZE;
    }

    /**
     * Retrieve the specified page with the associated permissions.
     * Will acquire a lock and may block if that lock is held by another
     * transaction.
     * <p>
     * The retrieved page should be looked up in the buffer pool.  If it
     * is present, it should be returned.  If it is not present, it should
     * be added to the buffer pool and returned.  If there is insufficient
     * space in the buffer pool, a page should be evicted and the new page
     * should be added in its place.
     */
    public Page getPage(TransactionId tid, PageId pid, Permissions perm)
            throws TransactionAbortedException, DbException {

        // Acquire lock (may block or throw TransactionAbortedException on deadlock)
        lockManager.acquireLock(tid, pid, perm);

        // Get page from cache or disk
        Page page = pageCache.get(pid);
        if (page != null) {
            return page;
        }

        // Double-check locking to prevent concurrent disk reads
        synchronized (this) {
            page = pageCache.get(pid);
            if (page != null) {
                return page;
            }

            // Evict if cache is full
            if (pageCache.size() >= numPages) {
                evictPage();
            }

            // Read from disk
            DbFile dbFile = Database.getCatalog().getDatabaseFile(pid.getTableId());
            page = dbFile.readPage(pid);
            page.setBeforeImage();
            pageCache.put(pid, page);
        }

        return page;
    }

    /**
     * Releases the lock on a page.
     */
    public void unsafeReleasePage(TransactionId tid, PageId pid) {
        lockManager.releaseLock(tid, pid);
    }

    /**
     * Release all locks associated with a given transaction.
     */
    public void transactionComplete(TransactionId tid) {
        transactionComplete(tid, true);
    }

    /** Return true if the specified transaction has a lock on the specified page */
    public boolean holdsLock(TransactionId tid, PageId p) {
        return lockManager.holdsLock(tid, p);
    }

    /**
     * Commit or abort a given transaction; release all locks associated to
     * the transaction.
     *
     * @param tid the ID of the transaction requesting the unlock
     * @param commit a flag indicating whether we should commit or abort
     */
    public void transactionComplete(TransactionId tid, boolean commit) {
        if (commit) {
            // Commit: flush all dirty pages to disk
            try {
                flushPages(tid);
            } catch (IOException e) {
                e.printStackTrace();
            }
        } else {
            // Abort: discard dirty pages from cache
            revertDirtyPages(tid);
        }

        // Strict 2PL: release all locks after transaction completes
        lockManager.releaseAllLocks(tid);
    }

    /**
     * Revert dirty pages by removing them from buffer pool
     */
    private synchronized void revertDirtyPages(TransactionId tid) {
        for (Map.Entry<PageId, Page> entry : pageCache.entrySet()) {
            Page page = entry.getValue();
            if (tid.equals(page.isDirty())) {
                pageCache.remove(entry.getKey());
            }
        }
    }

    /**
     * Add a tuple to the specified table on behalf of transaction tid.
     */
    public void insertTuple(TransactionId tid, int tableId, Tuple t)
            throws DbException, IOException, TransactionAbortedException {
        DbFile file = Database.getCatalog().getDatabaseFile(tableId);
        List<Page> modifiedPages = file.insertTuple(tid, t);

        for (Page page : modifiedPages) {
            page.markDirty(true, tid);
            pageCache.put(page.getId(), page);
        }
    }

    /**
     * Remove the specified tuple from the buffer pool.
     */
    public void deleteTuple(TransactionId tid, Tuple t)
            throws DbException, IOException, TransactionAbortedException {
        DbFile file = Database.getCatalog().getDatabaseFile(t.getRecordId().getPageId().getTableId());
        List<Page> modifiedPages = file.deleteTuple(tid, t);

        for (Page page : modifiedPages) {
            page.markDirty(true, tid);
            pageCache.put(page.getId(), page);
        }
    }

    /**
     * Flush all dirty pages to disk.
     */
    public synchronized void flushAllPages() throws IOException {
        for (PageId pid : pageCache.keySet()) {
            flushPage(pid);
        }
    }

    /**
     * Remove the specific page id from the buffer pool.
     */
    public synchronized void discardPage(PageId pid) {
        pageCache.remove(pid);
    }

    /**
     * Flushes a certain page to disk
     */
    private synchronized void flushPage(PageId pid) throws IOException {
        Page page = pageCache.get(pid);
        if (page == null) return;

        TransactionId dirtier = page.isDirty();
        if (dirtier != null) {
            Database.getLogFile().logWrite(dirtier, page.getBeforeImage(), page);
            Database.getCatalog().getDatabaseFile(pid.getTableId()).writePage(page);
            page.markDirty(false, null);
            page.setBeforeImage();
        }
    }

    /**
     * Write all pages of the specified transaction to disk.
     */
    public synchronized void flushPages(TransactionId tid) throws IOException {
        for (Map.Entry<PageId, Page> entry : pageCache.entrySet()) {
            Page page = entry.getValue();
            if (tid.equals(page.isDirty())) {
                flushPage(entry.getKey());
            }
        }
    }

    /**
     * Discards a page from the buffer pool.
     */
    private synchronized void evictPage() throws DbException {
        PageId evictPid = null;

        // Find a clean page to evict
        for (Map.Entry<PageId, Page> entry : pageCache.entrySet()) {
            Page page = entry.getValue();
            // NO STEAL: never evict dirty pages!
            if (page.isDirty() == null) {
                evictPid = entry.getKey();
                break;
            }
        }

        if (evictPid == null) {
            throw new DbException("All pages in buffer pool are dirty. Cannot evict.");
        }

        pageCache.remove(evictPid);
    }
}
