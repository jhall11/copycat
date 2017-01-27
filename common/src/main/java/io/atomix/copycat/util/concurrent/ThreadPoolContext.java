/*
 * Copyright 2016 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License
 */
package io.atomix.copycat.util.concurrent;

import io.atomix.copycat.util.Assert;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.LinkedList;
import java.util.concurrent.*;
import java.util.function.Supplier;

/**
 * Thread pool context.
 * <p>
 * This is a special {@link ThreadContext} implementation that schedules events to be executed
 * on a thread pool. Events executed by this context are guaranteed to be executed on order but may be executed on different
 * threads in the provided thread pool.
 *
 * @author <a href="http://github.com/kuujo">Jordan Halterman</a>
 */
public class ThreadPoolContext implements ThreadContext {
  private static final Logger LOGGER = LoggerFactory.getLogger(ThreadPoolContext.class);
  private final ScheduledExecutorService parent;
  private final Runnable runner;
  private final LinkedList<Runnable> tasks = new LinkedList<>();
  private volatile boolean blocked;
  private boolean running;
  private final Executor executor = new Executor() {
    @Override
    public void execute(Runnable command) {
      synchronized (tasks) {
        tasks.add(command);
        if (!running) {
          running = true;
          parent.execute(runner);
        }
      }
    }
  };

  /**
   * Creates a new thread pool context.
   *
   * @param parent The thread pool on which to execute events.
   */
  public ThreadPoolContext(ScheduledExecutorService parent) {
    this.parent = Assert.notNull(parent, "parent");

    // This code was shamelessly stolededed from Vert.x:
    // https://github.com/eclipse/vert.x/blob/master/src/main/java/io/vertx/core/impl/OrderedExecutorFactory.java
    runner = () -> {
      ((CopycatThread) Thread.currentThread()).setContext(this);
      for (;;) {
        final Runnable task;
        synchronized (tasks) {
          task = tasks.poll();
          if (task == null) {
            running = false;
            return;
          }
        }

        try {
          task.run();
        } catch (Throwable t) {
          LOGGER.error("An uncaught exception occurred", t);
          throw t;
        }
      }
    };
  }

  @Override
  public Logger logger() {
    return LOGGER;
  }

  @Override
  public boolean isBlocked() {
    return blocked;
  }

  @Override
  public void block() {
    blocked = true;
  }

  @Override
  public void unblock() {
    blocked = false;
  }

  @Override
  public void execute(Runnable callback) {
    executor.execute(callback);
  }

  @Override
  public <T> CompletableFuture<T> execute(Supplier<T> callback) {
    CompletableFuture<T> future = new CompletableFuture<>();
    executor.execute(() -> {
      try {
        future.complete(callback.get());
      } catch (Throwable t) {
        future.completeExceptionally(t);
      }
    });
    return future;
  }

  @Override
  public Scheduled schedule(Duration delay, Runnable runnable) {
    ScheduledFuture<?> future = parent.schedule(() -> executor.execute(Runnables.logFailure(runnable, LOGGER)), delay.toMillis(), TimeUnit.MILLISECONDS);
    return () -> future.cancel(false);
  }

  @Override
  public Scheduled schedule(Duration delay, Duration interval, Runnable runnable) {
    ScheduledFuture<?> future = parent.scheduleAtFixedRate(() -> executor.execute(Runnables.logFailure(runnable, LOGGER)), delay.toMillis(), interval.toMillis(), TimeUnit.MILLISECONDS);
    return () -> future.cancel(false);
  }

  @Override
  public void close() {
    // Do nothing.
  }

}
