package com.equalexperts.logging.impl;

import com.equalexperts.logging.LogMessage;
import com.equalexperts.logging.OpsLogger;
import org.junit.Before;
import org.junit.Test;
import org.mockito.*;

import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.locks.Lock;
import java.util.function.Consumer;
import java.util.function.Supplier;

import static org.junit.Assert.*;
import static org.mockito.Matchers.any;
import static org.mockito.Mockito.*;

public class BasicOpsLoggerTest {
    private Clock fixedClock = Clock.fixed(Instant.parse("2014-02-01T14:57:12.500Z"), ZoneOffset.UTC);
    @Mock private Destination<TestMessages> destination;
    @Mock private Supplier<Map<String,String>> correlationIdSupplier;
    @Mock private Consumer<Throwable> exceptionConsumer;
    @Mock private Lock lock;
    @Captor private ArgumentCaptor<LogicalLogRecord<TestMessages>> captor;

    private OpsLogger<TestMessages> logger;

    @Before
    public void setup() {
        MockitoAnnotations.initMocks(this);
        logger = new BasicOpsLogger<>(fixedClock, correlationIdSupplier, destination, lock, exceptionConsumer);
    }

    @Test
    public void log_shouldWriteALogicalLogRecordToTheDestination_givenALogMessageInstance() throws Exception {
        Map<String,String> expectedCorrelationIds = generateCorrelationIds();
        when(correlationIdSupplier.get()).thenReturn(expectedCorrelationIds);
        doNothing().when(destination).publish(captor.capture());

        logger.log(TestMessages.Bar, 64, "Hello, World");

        verify(destination, times(1)).publish(any());
        verify(correlationIdSupplier).get();
        verifyNoMoreInteractions(correlationIdSupplier);

        LogicalLogRecord<TestMessages> record = captor.getValue();
        assertEquals(fixedClock.instant(), record.getTimestamp());
        assertEquals(expectedCorrelationIds, record.getCorrelationIds());
        assertEquals(TestMessages.Bar, record.getMessage());
        assertNotNull(record.getCause());
        assertFalse(record.getCause().isPresent());
        assertArrayEquals(new Object[] {64, "Hello, World"}, record.getDetails());
    }

    @Test
    public void log_shouldObtainAndReleaseALockAndBeginAndEndADestinationBatch_givenALogMessageInstance() throws Exception {
        logger.log(TestMessages.Foo);

        InOrder inOrder = inOrder(lock, destination);
        inOrder.verify(lock).lock();
        inOrder.verify(destination).beginBatch();
        inOrder.verify(destination).publish(any());
        inOrder.verify(destination).endBatch();
        inOrder.verify(lock).unlock();
    }

    @Test
    public void log_shouldExposeAnExceptionToTheHandler_givenAProblemCreatingTheLogRecord() throws Exception {
        logger.log(null);

        verify(exceptionConsumer).accept(Mockito.isA(NullPointerException.class));
    }

    @Test
    public void log_shouldNotAcquireALockOrInteractWithTheDestination_givenAProblemCreatingTheLogRecord() throws Exception {
        logger.log(null);

        verifyZeroInteractions(lock, destination);
    }

    @Test
    public void log_shouldExposeAnExceptionToTheHandler_givenAProblemObtainingCorrelationIds() throws Exception {
        Error expectedThrowable = new Error();
        when(correlationIdSupplier.get()).thenThrow(expectedThrowable);

        logger.log(TestMessages.Foo);

        verify(exceptionConsumer).accept(Mockito.same(expectedThrowable));
    }

    @Test
    public void log_shouldNotAcquireALockOrInteractWithTheDestination_givenAProblemObtainingCorrelationIds() throws Exception {
        when(correlationIdSupplier.get()).thenThrow(new RuntimeException());

        logger.log(TestMessages.Foo);

        verifyZeroInteractions(lock, destination);
    }

    @Test
    public void log_shouldExposeAnExceptionToTheHandler_givenAProblemPublishingALogRecord() throws Exception {
        RuntimeException expectedException = new NullPointerException();
        doThrow(expectedException).when(destination).publish(any());

        logger.log(TestMessages.Foo);

        verify(exceptionConsumer).accept(Mockito.same(expectedException));
    }

    @Test
    public void log_shouldEndTheBatchAndReleaseTheLock_givenAProblemPublishingALogRecord() throws Exception {
        doThrow(new RuntimeException()).when(destination).publish(any());

        logger.log(TestMessages.Foo);

        InOrder inOrder = inOrder(lock, destination);
        inOrder.verify(lock).lock();
        inOrder.verify(destination).beginBatch();
        inOrder.verify(destination).publish(any());
        inOrder.verify(destination).endBatch();
        inOrder.verify(lock).unlock();
    }

    @Test
    public void log_shouldWriteALogicalLogRecordToTheDestination_givenALogMessageInstanceAndAThrowable() throws Exception {
        Map<String, String> expectedCorrelationIds = generateCorrelationIds();
        when(correlationIdSupplier.get()).thenReturn(expectedCorrelationIds);
        doNothing().when(destination).publish(captor.capture());
        RuntimeException expectedException = new RuntimeException("expected");

        logger.log(TestMessages.Bar, expectedException, 64, "Hello, World");

        verify(destination, times(1)).publish(any());
        verify(correlationIdSupplier).get();
        verifyNoMoreInteractions(correlationIdSupplier);

        LogicalLogRecord<TestMessages> record = captor.getValue();
        assertEquals(fixedClock.instant(), record.getTimestamp());
        assertEquals(expectedCorrelationIds, record.getCorrelationIds());
        assertEquals(TestMessages.Bar, record.getMessage());
        assertNotNull(record.getCause());
        assertSame(expectedException, record.getCause().get());
        assertArrayEquals(new Object[] {64, "Hello, World"}, record.getDetails());
    }

    @Test
    public void log_shouldObtainAndReleaseALockAndBeginAndEndADestinationBatch_givenALogMessageInstanceAndAThrowable() throws Exception {
        logger.log(TestMessages.Foo, new RuntimeException());

        InOrder inOrder = inOrder(lock, destination);
        inOrder.verify(lock).lock();
        inOrder.verify(destination).beginBatch();
        inOrder.verify(destination).publish(any());
        inOrder.verify(destination).endBatch();
        inOrder.verify(lock).unlock();
    }

    @Test
    public void log_shouldExposeAnExceptionToTheHandler_givenAProblemCreatingTheLogRecordWithAThrowable() throws Exception {
        logger.log(TestMessages.Foo, (Throwable) null);

        verify(exceptionConsumer).accept(Mockito.isA(NullPointerException.class));
    }

    @Test
    public void log_shouldNotObtainALockOrInteractWithTheDestination_givenAProblemCreatingTheLogRecordWithAThrowable() throws Exception {
        logger.log(null, new Throwable());

        verifyZeroInteractions(lock, destination);
    }

    @Test
    public void log_shouldExposeAnExceptionToTheHandler_givenAProblemPublishingALogRecordWithAThrowable() throws Exception {
        Exception expectedException = new IOException("Couldn't write to the output stream");
        doThrow(expectedException).when(destination).publish(any());

        logger.log(TestMessages.Foo, new NullPointerException());

        verify(exceptionConsumer).accept(Mockito.same(expectedException));
    }

    @Test
    public void log_shouldEndTheBatchAndReleaseTheLock_givenAProblemPublishingTheLogRecordWithAThrowable() throws Exception {
        doThrow(new RuntimeException()).when(destination).publish(any());

        logger.log(TestMessages.Foo, new Error());

        InOrder inOrder = inOrder(lock, destination);
        inOrder.verify(lock).lock();
        inOrder.verify(destination).beginBatch();
        inOrder.verify(destination).publish(any());
        inOrder.verify(destination).endBatch();
        inOrder.verify(lock).unlock();
    }

    @Test
    public void log_shouldExposeAnExceptionToTheHandler_givenAProblemObtainingCorrelationIdsWithAThrowable() throws Exception {
        Error expectedThrowable = new Error();
        when(correlationIdSupplier.get()).thenThrow(expectedThrowable);

        logger.log(TestMessages.Foo, new RuntimeException());

        verify(exceptionConsumer).accept(Mockito.same(expectedThrowable));
    }

    @Test
    public void log_shouldNotObtainALockOrInteractWithTheDestination_givenAProblemObtainingCorrelationIdsWithAThrowable() throws Exception {
        when(correlationIdSupplier.get()).thenThrow(new RuntimeException());

        logger.log(TestMessages.Foo, new Exception());

        verifyZeroInteractions(lock, destination);
    }

    @Test
    public void close_shouldCloseTheDestination() throws Exception {
        logger.close();

        verify(destination).close();
    }

    private Map<String, String> generateCorrelationIds() {
        Map<String, String> result = new HashMap<>();
        result.put("foo", "fooValue");
        result.put("bar", "barValue");
        return result;
    }

    private static enum TestMessages implements LogMessage {
        Foo("CODE-Foo", "An event of some kind occurred"),
        Bar("CODE-Bar", "An event with %d %s messages");

        //region LogMessage implementation guts
        private final String messageCode;
        private final String messagePattern;

        TestMessages(String messageCode, String messagePattern) {
            this.messageCode = messageCode;
            this.messagePattern = messagePattern;
        }

        @Override
        public String getMessageCode() {
            return messageCode;
        }

        @Override
        public String getMessagePattern() {
            return messagePattern;
        }
        //endregion
    }
}