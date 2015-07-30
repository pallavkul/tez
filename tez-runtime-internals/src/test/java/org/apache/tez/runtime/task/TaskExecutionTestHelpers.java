/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.tez.runtime.task;

import static org.junit.Assert.fail;

import java.io.IOException;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

import org.apache.hadoop.ipc.ProtocolSignature;
import org.apache.hadoop.yarn.api.records.ApplicationAttemptId;
import org.apache.hadoop.yarn.api.records.ApplicationId;
import org.apache.hadoop.yarn.api.records.ContainerId;
import org.apache.tez.common.ContainerContext;
import org.apache.tez.common.ContainerTask;
import org.apache.tez.common.TezTaskUmbilicalProtocol;
import org.apache.tez.dag.api.TezException;
import org.apache.tez.dag.records.TezTaskAttemptID;
import org.apache.tez.runtime.api.AbstractLogicalIOProcessor;
import org.apache.tez.runtime.api.Event;
import org.apache.tez.runtime.api.LogicalInput;
import org.apache.tez.runtime.api.LogicalOutput;
import org.apache.tez.runtime.api.ProcessorContext;
import org.apache.tez.runtime.api.events.TaskAttemptCompletedEvent;
import org.apache.tez.runtime.api.events.TaskAttemptFailedEvent;
import org.apache.tez.runtime.api.impl.TezEvent;
import org.apache.tez.runtime.api.impl.TezHeartbeatRequest;
import org.apache.tez.runtime.api.impl.TezHeartbeatResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class TaskExecutionTestHelpers {

  public static final String HEARTBEAT_EXCEPTION_STRING = "HeartbeatException";

  // Uses static fields for signaling. Ensure only used by one test at a time.
  public static class TestProcessor extends AbstractLogicalIOProcessor {

    public static final byte[] CONF_EMPTY = new byte[] { 0 };
    public static final byte[] CONF_THROW_IO_EXCEPTION = new byte[] { 1 };
    public static final byte[] CONF_THROW_TEZ_EXCEPTION = new byte[] { 2 };
    public static final byte[] CONF_SIGNAL_FATAL_AND_THROW = new byte[] { 4 };
    public static final byte[] CONF_SIGNAL_FATAL_AND_LOOP = new byte[] { 8 };
    public static final byte[] CONF_SIGNAL_FATAL_AND_COMPLETE = new byte[] { 16 };

    private static final Logger LOG = LoggerFactory.getLogger(TestProcessor.class);

    private static final ReentrantLock processorLock = new ReentrantLock();
    private static final Condition processorCondition = processorLock.newCondition();
    private static final Condition loopCondition = processorLock.newCondition();
    private static final Condition completionCondition = processorLock.newCondition();
    private static final Condition runningCondition = processorLock.newCondition();
    private static volatile boolean completed = false;
    private static volatile boolean running = false;
    private static volatile boolean looping = false;
    private static volatile boolean signalled = false;

    private static boolean receivedInterrupt = false;
    private static volatile boolean wasAborted = false;

    private boolean throwIOException = false;
    private boolean throwTezException = false;
    private boolean signalFatalAndThrow = false;
    private boolean signalFatalAndLoop = false;
    private boolean signalFatalAndComplete = false;

    public TestProcessor(ProcessorContext context) {
      super(context);
    }

    @Override
    public void initialize() throws Exception {
      parseConf(getContext().getUserPayload().deepCopyAsArray());
    }

    @Override
    public void handleEvents(List<Event> processorEvents) {

    }

    @Override
    public void close() throws Exception {

    }

    private void parseConf(byte[] bytes) {
      byte b = bytes[0];
      throwIOException = (b & 1) > 0;
      throwTezException = (b & 2) > 0;
      signalFatalAndThrow = (b & 4) > 0;
      signalFatalAndLoop = (b & 8) > 0;
      signalFatalAndComplete = (b & 16) > 0;
    }

    public static void reset() {
      signalled = false;
      receivedInterrupt = false;
      completed = false;
      running = false;
      wasAborted = false;
    }

    public static void signal() {
      LOG.info("Signalled");
      processorLock.lock();
      try {
        signalled = true;
        processorCondition.signal();
      } finally {
        processorLock.unlock();
      }
    }

    public static void awaitStart() throws InterruptedException {
      LOG.info("Awaiting Process run");
      processorLock.lock();
      try {
        if (running) {
          return;
        }
        runningCondition.await();
      } finally {
        processorLock.unlock();
      }
    }

    public static void awaitLoop() throws InterruptedException {
      LOG.info("Awaiting loop after signalling error");
      processorLock.lock();
      try {
        if (looping) {
          return;
        }
        loopCondition.await();
      } finally {
        processorLock.unlock();
      }
    }

    public static void awaitCompletion() throws InterruptedException {
      LOG.info("Await completion");
      processorLock.lock();
      try {
        if (completed) {
          return;
        } else {
          completionCondition.await();
        }
      } finally {
        processorLock.unlock();
      }
    }

    public static boolean wasInterrupted() {
      processorLock.lock();
      try {
        return receivedInterrupt;
      } finally {
        processorLock.unlock();
      }
    }

    public static boolean wasAborted() {
      processorLock.lock();
      try {
        return wasAborted;
      } finally {
        processorLock.unlock();
      }
    }

    @Override
    public void abort() {
      wasAborted = true;
    }

    @Override
    public void run(Map<String, LogicalInput> inputs, Map<String, LogicalOutput> outputs) throws
        Exception {
      processorLock.lock();
      running = true;
      runningCondition.signal();
      try {
        try {
          LOG.info("Signal is: " + signalled);
          if (!signalled) {
            LOG.info("Waiting for processor signal");
            processorCondition.await();
          }
          if (Thread.currentThread().isInterrupted()) {
            throw new InterruptedException();
          }
          LOG.info("Received processor signal");
          if (throwIOException) {
            throw createProcessorIOException();
          } else if (throwTezException) {
            throw createProcessorTezException();
          } else if (signalFatalAndThrow) {
            IOException io = new IOException("FATALERROR");
            getContext().fatalError(io, "FATALERROR");
            throw io;
          } else if (signalFatalAndComplete) {
            IOException io = new IOException("FATALERROR");
            getContext().fatalError(io, "FATALERROR");
            return;
          } else if (signalFatalAndLoop) {
            IOException io = createProcessorIOException();
            getContext().fatalError(io, "FATALERROR");
            LOG.info("looping");
            looping = true;
            loopCondition.signal();
            LOG.info("Waiting for Processor signal again");
            processorCondition.await();
            LOG.info("Received second processor signal");
          }
        } catch (InterruptedException e) {
          receivedInterrupt = true;
        }
      } finally {
        completed = true;
        completionCondition.signal();
        processorLock.unlock();
      }
    }
  }

  public static TezException createProcessorTezException() {
    return new TezException("TezException");
  }

  public static IOException createProcessorIOException() {
    return new IOException("IOException");
  }

  public static class TezTaskUmbilicalForTest implements TezTaskUmbilicalProtocol {

    private static final Logger LOG = LoggerFactory.getLogger(TezTaskUmbilicalForTest.class);

    private final List<TezEvent> requestEvents = new LinkedList<TezEvent>();

    private final ReentrantLock umbilicalLock = new ReentrantLock();
    private final Condition eventCondition = umbilicalLock.newCondition();
    private boolean pendingEvent = false;
    private boolean eventEnacted = false;

    volatile int getTaskInvocations = 0;

    private boolean shouldThrowException = false;
    private boolean shouldSendDieSignal = false;

    public void signalThrowException() {
      umbilicalLock.lock();
      try {
        shouldThrowException = true;
        pendingEvent = true;
      } finally {
        umbilicalLock.unlock();
      }
    }

    public void signalSendShouldDie() {
      umbilicalLock.lock();
      try {
        shouldSendDieSignal = true;
        pendingEvent = true;
      } finally {
        umbilicalLock.unlock();
      }
    }

    public void awaitRegisteredEvent() throws InterruptedException {
      umbilicalLock.lock();
      try {
        if (eventEnacted) {
          return;
        }
        LOG.info("Awaiting event");
        eventCondition.await();
      } finally {
        umbilicalLock.unlock();
      }
    }

    public void resetTrackedEvents() {
      umbilicalLock.lock();
      try {
        requestEvents.clear();
      } finally {
        umbilicalLock.unlock();
      }
    }

    public void verifyNoCompletionEvents() {
      umbilicalLock.lock();
      try {
        for (TezEvent event : requestEvents) {
          if (event.getEvent() instanceof TaskAttemptFailedEvent) {
            fail("Found a TaskAttemptFailedEvent when not expected");
          }
          if (event.getEvent() instanceof TaskAttemptCompletedEvent) {
            fail("Found a TaskAttemptCompletedvent when not expected");
          }
        }
      } finally {
        umbilicalLock.unlock();
      }
    }

    public void verifyTaskFailedEvent(String diagnostics) {
      umbilicalLock.lock();
      try {
        for (TezEvent event : requestEvents) {
          if (event.getEvent() instanceof TaskAttemptFailedEvent) {
            TaskAttemptFailedEvent failedEvent = (TaskAttemptFailedEvent) event.getEvent();
            if (failedEvent.getDiagnostics().startsWith(diagnostics)) {
              return;
            } else {
              fail("Diagnostic message does not match expected message. Found [" +
                  failedEvent.getDiagnostics() + "], Expected: [" + diagnostics + "]");
            }
          }
        }
        fail("No TaskAttemptFailedEvents sent over umbilical");
      } finally {
        umbilicalLock.unlock();
      }
    }

    public void verifyTaskFailedEvent(String diagStart, String diagContains) {
      umbilicalLock.lock();
      try {
        for (TezEvent event : requestEvents) {
          if (event.getEvent() instanceof TaskAttemptFailedEvent) {
            TaskAttemptFailedEvent failedEvent = (TaskAttemptFailedEvent) event.getEvent();
            if (failedEvent.getDiagnostics().startsWith(diagStart)) {
              if (diagContains != null) {
                if (failedEvent.getDiagnostics().contains(diagContains)) {
                  return;
                } else {
                  fail("Diagnostic message does not contain expected message. Found [" +
                      failedEvent.getDiagnostics() + "], Expected: [" + diagContains + "]");
                }
              }
            } else {
              fail("Diagnostic message does not start with expected message. Found [" +
                  failedEvent.getDiagnostics() + "], Expected: [" + diagStart + "]");
            }
          }
        }
        fail("No TaskAttemptFailedEvents sent over umbilical");
      } finally {
        umbilicalLock.unlock();
      }
    }

    public void verifyTaskSuccessEvent() {
      umbilicalLock.lock();
      try {
        for (TezEvent event : requestEvents) {
          if (event.getEvent() instanceof TaskAttemptCompletedEvent) {
            return;
          }
        }
        fail("No TaskAttemptFailedEvents sent over umbilical");
      } finally {
        umbilicalLock.unlock();
      }
    }

    @Override
    public long getProtocolVersion(String protocol, long clientVersion) throws IOException {
      return 0;
    }

    @Override
    public ProtocolSignature getProtocolSignature(String protocol, long clientVersion,
                                                  int clientMethodsHash) throws IOException {
      return null;
    }

    @Override
    public ContainerTask getTask(ContainerContext containerContext) throws IOException {
      // Return shouldDie = true
      getTaskInvocations++;
      return new ContainerTask(null, true, null, null, false);
    }

    @Override
    public boolean canCommit(TezTaskAttemptID taskid) throws IOException {
      return true;
    }

    @Override
    public TezHeartbeatResponse heartbeat(TezHeartbeatRequest request) throws IOException,
        TezException {
      umbilicalLock.lock();
      if (request.getEvents() != null) {
        requestEvents.addAll(request.getEvents());
      }
      try {
        if (shouldThrowException) {
          LOG.info("TestUmbilical throwing Exception");
          throw new IOException(HEARTBEAT_EXCEPTION_STRING);
        }
        TezHeartbeatResponse response = new TezHeartbeatResponse();
        response.setLastRequestId(request.getRequestId());
        if (shouldSendDieSignal) {
          LOG.info("TestUmbilical returning shouldDie=true");
          response.setShouldDie();
        }
        return response;
      } finally {
        if (pendingEvent) {
          eventEnacted = true;
          LOG.info("Signalling Event");
          eventCondition.signal();
        }
        umbilicalLock.unlock();
      }
    }
  }

  public static ContainerId createContainerId(ApplicationId appId) {
    ApplicationAttemptId appAttemptId = ApplicationAttemptId.newInstance(appId, 1);
    ContainerId containerId = ContainerId.newInstance(appAttemptId, 1);
    return containerId;
  }

  public static TaskReporter createTaskReporter(ApplicationId appId, TezTaskUmbilicalForTest umbilical) {
    TaskReporter taskReporter = new TaskReporter(umbilical, 100, 1000, 100, new AtomicLong(0),
        createContainerId(appId).toString());
    return taskReporter;
  }
}