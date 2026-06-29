package org.jenkinsci.plugins.lucene.search.databackend;

import java.util.HashSet;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.apache.log4j.Logger;

public class BurstExecutor<T> {
    private static final Logger LOGGER = Logger.getLogger(BurstExecutor.class);
    private final LinkedBlockingQueue<T> workQueue = new LinkedBlockingQueue<T>();
    private final HashSet<WorkerThread> activeThreads = new HashSet<WorkerThread>();
    private final RunWithArgument<T> worker;
    private final int maxThreads;
    private boolean started;
    // Only for giving better names to threads
    private static int threadIndex = 1;

    private BurstExecutor(RunWithArgument<T> worker, int maxThreads) {
        this.worker = worker;
        this.maxThreads = maxThreads;
    }

    public void add(T workload) {
        workQueue.add(workload);
        if (started) {
            ensureEnoughThreadToFinishJob();
        }
    }

    private synchronized void ensureEnoughThreadToFinishJob() {
        if (!workQueue.isEmpty() && activeThreads.size() < maxThreads) {
            WorkerThread thread = new WorkerThread();
            activeThreads.add(thread);
            thread.start();
        }
    }

    private synchronized void removeThread(WorkerThread wt) {
        activeThreads.remove(wt);
    }

    private class WorkerThread extends Thread {
        WorkerThread() {
            super("WorkerThread-" + threadIndex++);
        }

        @Override
        public void run() {
            try {
                while (!workQueue.isEmpty()) {
                    try {
                        T poll = workQueue.poll(100, TimeUnit.MILLISECONDS);
                        //                        T poll = workQueue.poll();
                        if (poll != null) {
                            worker.run(poll);
                        }
                    } catch (Exception e) {
                        LOGGER.error("WorkerThread " + getName() + " exception", e);
                    }
                }
            } finally {
                removeThread(this);
            }
        }
    }

    public void waitForCompletion() throws InterruptedException {
        if (!started) {
            throw new IllegalStateException("Not started yet");
        }
        // Spawn workers to handle remaining work (including work added by
        // workers themselves during processing).  Do NOT drain the queue
        // on the calling thread — that would defeat the parallelism.
        ensureEnoughThreadToFinishJob();
        // Snapshot active threads under the lock, then join every one
        // outside the lock to avoid deadlock with removeThread().
        java.util.ArrayList<WorkerThread> snapshot;
        synchronized (this) {
            snapshot = new java.util.ArrayList<>(activeThreads);
        }
        for (WorkerThread wt : snapshot) {
            wt.join();
        }
        // A joined thread may have spawned a new one from
        // ensureEnoughThreadToFinishJob inside its run loop, so
        // do one final drain pass just in case.
        while (!workQueue.isEmpty()) {
            worker.run(workQueue.poll());
        }
    }

    public static <T> BurstExecutor<T> create(RunWithArgument<T> worker, int maxThreads) {
        return new BurstExecutor<T>(worker, maxThreads);
    }

    public BurstExecutor<T> andStart() {
        started = true;
        int startThreads = Math.min(workQueue.size(), maxThreads);
        for (int i = 0; i < startThreads; i++) {
            ensureEnoughThreadToFinishJob();
        }
        return this;
    }
}
