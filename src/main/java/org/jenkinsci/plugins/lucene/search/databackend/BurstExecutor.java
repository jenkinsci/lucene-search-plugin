package org.jenkinsci.plugins.lucene.search.databackend;

import org.apache.log4j.Logger;

import java.util.*;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

public class BurstExecutor<T> {
    private static final Logger LOGGER = Logger.getLogger(BurstExecutor.class);
    private final LinkedBlockingQueue<T> workQueue = new LinkedBlockingQueue<T>();
    private final TreeSet<WorkerThread> activeThreads = new TreeSet<WorkerThread>();
    private final RunWithArgument<T> worker;
    private final int maxThreads;
    private boolean started;

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
        @Override
        public void run() {
            try {
                while (!workQueue.isEmpty()) {
                    try {
                        T poll = workQueue.poll(1000, TimeUnit.MILLISECONDS);
                        if (poll != null) {
                            worker.run(poll);
                        }
                    } catch (Exception e) {
                        LOGGER.error("WorkerThread exception", e);
                    }
                }
            } finally {
                removeThread(this);
            }
        }
    }

    public synchronized void waitForCompletion() throws InterruptedException {
        if (!started) {
            throw new IllegalStateException("Not started yet");
        }
        ensureEnoughThreadToFinishJob();
        while(! activeThreads.isEmpty()) {
            activeThreads.first().join();
        }
    }

    public static<T> BurstExecutor<T> create(RunWithArgument<T> worker, int maxThreads) {
        return new BurstExecutor<T>(worker, maxThreads);
    }

    public BurstExecutor<T> andStart() {
        started = true;
        int startThreads = Math.min(workQueue.size(), maxThreads);
        for(int i = 0; i < startThreads; i++) {
            ensureEnoughThreadToFinishJob();
        }
        return this;
    }
}
