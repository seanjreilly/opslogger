package com.equalexperts.logging;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedTransferQueue;
import java.util.function.Consumer;
import java.util.function.Supplier;

import static java.util.stream.Collectors.toList;

class AsyncOpsLogger<T extends Enum<T> & LogMessage> implements OpsLogger<T> {

    static final int MAX_BATCH_SIZE = 100;
    private final Future<?> processingThread;
    private final LinkedTransferQueue<Optional<LogicalLogRecord<T>>> transferQueue;
    private final Clock clock;
    private final Supplier<String[]> correlationIdSupplier;
    private final Destination<T> destination;
    private final Consumer<Throwable> errorHandler;

    public AsyncOpsLogger(Clock clock, Supplier<String[]> correlationIdSupplier, Destination<T> destination, Consumer<Throwable> errorHandler, LinkedTransferQueue<Optional<LogicalLogRecord<T>>> transferQueue, AsyncExecutor executor) {
        this.clock = clock;
        this.correlationIdSupplier = correlationIdSupplier;
        this.destination = destination;
        this.errorHandler = errorHandler;
        this.transferQueue = transferQueue;
        processingThread = executor.execute(this::process);
    }

    @Override
    public void log(T message, Object... details) {
        try {
            LogicalLogRecord<T> record = new LogicalLogRecord<>(clock.instant(), correlationIdSupplier.get(), message, Optional.empty(), details);
            transferQueue.put(Optional.of(record));
        } catch (Throwable t) {
            errorHandler.accept(t);
        }
    }

    @Override
    public void log(T message, Throwable cause, Object... details) {
        try {
            LogicalLogRecord<T> record = new LogicalLogRecord<>(clock.instant(), correlationIdSupplier.get(), message, Optional.of(cause), details);
            transferQueue.put(Optional.of(record));
        } catch (Throwable t) {
            errorHandler.accept(t);
        }
    }

    @Override
    public void close() throws Exception {
        try {
            transferQueue.put(Optional.empty()); //an empty optional is the shutdown signal
            processingThread.get();
        } finally {
            destination.close();
        }
    }

    static interface Destination<T extends Enum<T> & LogMessage> extends AutoCloseable {
        void beginBatch() throws Exception;
        void publish(LogicalLogRecord<T> record) throws Exception;
        void endBatch() throws Exception;
    }

    private void process() {
        /*
            An empty optional on the queue is the shutdown signal
         */
        boolean run = true;
        do {
            try {
                List<Optional<LogicalLogRecord<T>>> messages = take(MAX_BATCH_SIZE);
                List<LogicalLogRecord<T>> logRecords = messages.stream()
                        .filter(Optional::isPresent)
                        .map(Optional::get)
                        .collect(toList());

                if (logRecords.size() != messages.size()) {
                    run = false; //shutdown signal detected, don't run again
                }

                if (!logRecords.isEmpty()) {
                    destination.beginBatch();
                    try {
                        for (LogicalLogRecord<T> record : logRecords) {
                            try {
                                destination.publish(record);
                            } catch (Throwable t) {
                                errorHandler.accept(t);
                            }
                        }
                    } finally {
                        destination.endBatch();
                    }
                }
            } catch (Throwable t) {
                errorHandler.accept(t);
            }
        } while (run);
    }

    private List<Optional<LogicalLogRecord<T>>> take(int maxElements) throws InterruptedException {
        assert maxElements > 1;
        List<Optional<LogicalLogRecord<T>>> result = new ArrayList<>();
        result.add(transferQueue.take()); //a blocking operation
        transferQueue.drainTo(result, maxElements - 1);
        return result;
    }
}
