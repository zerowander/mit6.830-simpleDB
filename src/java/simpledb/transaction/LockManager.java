package simpledb.transaction;

import simpledb.common.Permissions;
import simpledb.storage.PageId;

import java.util.*;

/**
 * LockManager handles locking of pages for transactions.
 * Supports shared (read) and exclusive (write) locks.
 * Implements deadlock detection via a wait-for graph.
 */
public class LockManager {

    private static class Lock {
        TransactionId tid;
        Permissions perm;

        Lock(TransactionId tid, Permissions perm) {
            this.tid = tid;
            this.perm = perm;
        }
    }

    // PageId -> list of locks on that page
    private final Map<PageId, List<Lock>> lockTable = new HashMap<>();

    // Wait-for graph: tid -> set of tids it is waiting for
    private final Map<TransactionId, Set<TransactionId>> waitForGraph = new HashMap<>();

    // Which page each transaction is currently waiting for
    private final Map<TransactionId, PageId> waitingFor = new HashMap<>();

    /**
     * Acquire a lock on behalf of a transaction.
     * Blocks if the lock is not available.
     * Throws TransactionAbortedException if a deadlock is detected.
     */
    public synchronized void acquireLock(TransactionId tid, PageId pageId, Permissions permissions)
            throws TransactionAbortedException {

        // Fast path: already holds compatible lock
        List<Lock> locks = lockTable.get(pageId);
        if (locks != null) {
            for (Lock lock : locks) {
                if (lock.tid.equals(tid)) {
                    if (lock.perm.equals(Permissions.READ_WRITE) || permissions.equals(Permissions.READ_ONLY)) {
                        return; // already have compatible lock
                    }
                    // Want to upgrade
                    if (locks.size() == 1) {
                        lock.perm = Permissions.READ_WRITE;
                        return;
                    }
                    // Can't upgrade now, need to wait
                    break;
                }
            }
        }

        // Need to acquire the lock, possibly waiting
        while (!canGrantLock(pageId, permissions, tid)) {
            Set<TransactionId> blockers = getBlockers(pageId, tid);
            addWaitEdges(tid, blockers);

            if (hasCycle(tid)) {
                removeWaitEdges(tid);
                throw new TransactionAbortedException();
            }

            waitingFor.put(tid, pageId);
            try {
                // Use timeout to prevent lost wakeups from causing indefinite waits
                wait(2000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                removeWaitEdges(tid);
                throw new TransactionAbortedException();
            }
            waitingFor.remove(tid);
            removeWaitEdges(tid);
        }

        // Grant the lock
        locks = lockTable.computeIfAbsent(pageId, k -> new ArrayList<>());
        for (Lock lock : locks) {
            if (lock.tid.equals(tid)) {
                lock.perm = Permissions.READ_WRITE; // upgrade
                return;
            }
        }
        locks.add(new Lock(tid, permissions));
    }

    /**
     * Check if a lock can be granted.
     */
    private boolean canGrantLock(PageId pageId, Permissions perm, TransactionId tid) {
        List<Lock> locks = lockTable.get(pageId);
        if (locks == null || locks.isEmpty()) {
            return true;
        }

        if (perm.equals(Permissions.READ_ONLY)) {
            // Can grant if no one holds WRITE
            for (Lock lock : locks) {
                if (lock.perm.equals(Permissions.READ_WRITE)) {
                    return false;
                }
            }
            return true;
        } else {
            // WRITE: can only grant if this transaction is the sole holder (with READ)
            if (locks.size() == 1 && locks.get(0).tid.equals(tid)) {
                return true;
            }
            return false;
        }
    }

    /**
     * Get the set of transactions currently holding locks on the page
     * that block the requesting transaction.
     */
    private Set<TransactionId> getBlockers(PageId pageId, TransactionId requestingTid) {
        Set<TransactionId> blockers = new HashSet<>();
        List<Lock> locks = lockTable.get(pageId);
        if (locks != null) {
            for (Lock lock : locks) {
                if (!lock.tid.equals(requestingTid)) {
                    blockers.add(lock.tid);
                }
            }
        }
        return blockers;
    }

    private void addWaitEdges(TransactionId from, Set<TransactionId> toSet) {
        waitForGraph.computeIfAbsent(from, k -> new HashSet<>()).addAll(toSet);
    }

    private void removeWaitEdges(TransactionId from) {
        waitForGraph.remove(from);
    }

    /**
     * Check if there is a cycle in the wait-for graph involving start.
     */
    private boolean hasCycle(TransactionId start) {
        Set<TransactionId> visited = new HashSet<>();
        Deque<TransactionId> stack = new ArrayDeque<>();
        stack.push(start);

        while (!stack.isEmpty()) {
            TransactionId current = stack.pop();
            if (visited.contains(current)) {
                if (current.equals(start)) {
                    return true;
                }
                continue;
            }
            visited.add(current);
            Set<TransactionId> neighbors = waitForGraph.get(current);
            if (neighbors != null) {
                for (TransactionId neighbor : neighbors) {
                    stack.push(neighbor);
                }
            }
        }
        return false;
    }

    /**
     * Release a lock held by a transaction.
     */
    public synchronized void releaseLock(TransactionId tid, PageId pageId) {
        List<Lock> locks = lockTable.get(pageId);
        if (locks == null) {
            return;
        }
        locks.removeIf(lock -> lock.tid.equals(tid));
        if (locks.isEmpty()) {
            lockTable.remove(pageId);
        }
        notifyAll();
    }

    /**
     * Release all locks held by a transaction.
     */
    public synchronized void releaseAllLocks(TransactionId tid) {
        List<PageId> emptyPages = new ArrayList<>();
        for (Map.Entry<PageId, List<Lock>> entry : lockTable.entrySet()) {
            entry.getValue().removeIf(lock -> lock.tid.equals(tid));
            if (entry.getValue().isEmpty()) {
                emptyPages.add(entry.getKey());
            }
        }
        for (PageId pid : emptyPages) {
            lockTable.remove(pid);
        }
        waitForGraph.remove(tid);
        waitingFor.remove(tid);
        notifyAll();
    }

    /**
     * Check if a transaction holds a lock on a page.
     */
    public synchronized boolean holdsLock(TransactionId tid, PageId pageId) {
        List<Lock> locks = lockTable.get(pageId);
        if (locks == null) {
            return false;
        }
        for (Lock lock : locks) {
            if (lock.tid.equals(tid)) {
                return true;
            }
        }
        return false;
    }
}
