/*
 * Copyright 2017 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.gradle.test.fixtures.server.http;

import com.google.common.base.Charsets;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import org.gradle.internal.UncheckedException;
import org.gradle.internal.exceptions.DefaultMultiCauseException;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.Lock;

class ChainingHttpHandler implements HttpHandler {
    private final AtomicInteger counter;
    private final List<TrackingHttpHandler> handlers = new CopyOnWriteArrayList<TrackingHttpHandler>();
    private final List<RequestOutcome> outcomes = new ArrayList<RequestOutcome>();
    private final Lock lock;
    private WaitPrecondition last;
    private boolean completed;
    private final Condition condition;
    private int requestCount;

    ChainingHttpHandler(Lock lock, AtomicInteger counter, WaitPrecondition first) {
        this.lock = lock;
        this.condition = lock.newCondition();
        this.counter = counter;
        this.last = first;
    }

    public <T extends TrackingHttpHandler> T addHandler(HandlerFactory<T> factory) {
        lock.lock();
        try {
            T handler = factory.create(last);
            handlers.add(handler);
            last = handler.getWaitPrecondition();
            return handler;
        } finally {
            lock.unlock();
        }
    }

    public void assertComplete() {
        lock.lock();
        try {
            List<Throwable> failures = new ArrayList<Throwable>();
            for (RequestOutcome outcome : outcomes) {
                outcome.collectFailures(failures);
            }
            if (!completed) {
                for (TrackingHttpHandler handler : handlers) {
                    try {
                        handler.assertComplete();
                    } catch (Throwable t) {
                        failures.add(t);
                    }
                }
                completed = true;
            }
            if (!failures.isEmpty()) {
                throw new DefaultMultiCauseException("Failed to handle all HTTP requests.", failures);
            }
        } finally {
            lock.unlock();
        }
    }

    @Override
    public void handle(HttpExchange httpExchange) {
        try {
            int id = counter.incrementAndGet();

            RequestOutcome outcome = requestStarted(httpExchange);
            System.out.println(String.format("[%d] handling %s", id, outcome.getDisplayName()));

            try {
                ResourceHandler resourceHandler = selectHandler(id, httpExchange, outcome);
                if (resourceHandler != null) {
                    System.out.println(String.format("[%d] sending response for %s", id, outcome.getDisplayName()));
                    resourceHandler.writeTo(id, httpExchange);
                } else {
                    System.out.println(String.format("[%d] sending error response for unexpected request", id));
                    if (outcome.method.equals("HEAD")) {
                        httpExchange.sendResponseHeaders(500, -1);
                    } else {
                        byte[] message = String.format("Failed request %s", outcome.getDisplayName()).getBytes(Charsets.UTF_8);
                        httpExchange.sendResponseHeaders(500, message.length);
                        httpExchange.getResponseBody().write(message);
                    }
                }
            } catch (Throwable t) {
                System.out.println(String.format("[%d] handling %s failed with exception", id, outcome.getDisplayName()));
                requestFailed(outcome, t);
            } finally {
                requestCompleted(outcome);
            }
        } finally {
            httpExchange.close();
        }
    }

    private RequestOutcome requestStarted(HttpExchange httpExchange) {
        lock.lock();
        RequestOutcome outcome;
        try {
            outcome = new RequestOutcome(httpExchange.getRequestMethod(), httpExchange.getRequestURI().getPath());
            outcomes.add(outcome);
        } finally {
            lock.unlock();
        }
        return outcome;
    }

    private void requestFailed(RequestOutcome outcome, Throwable t) {
        lock.lock();
        try {
            if (outcome.failure == null) {
                outcome.failure = new AssertionError(String.format("Failed to handle %s", outcome.getDisplayName()), t);
            }
        } finally {
            lock.unlock();
        }
    }

    private void requestCompleted(RequestOutcome outcome) {
        lock.lock();
        try {
            outcome.completed = true;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Returns null on failure.
     */
    @Nullable
    private ResourceHandler selectHandler(int id, HttpExchange httpExchange, RequestOutcome outcome) {
        lock.lock();
        try {
            requestCount++;
            condition.signalAll();
            if (completed) {
                System.out.println(String.format("[%d] received request %s %s after HTTP server has stopped.", id, httpExchange.getRequestMethod(), httpExchange.getRequestURI()));
                return null;
            }
            for (TrackingHttpHandler handler : handlers) {
                ResourceHandler resourceHandler = handler.handle(id, httpExchange);
                if (resourceHandler != null) {
                    return resourceHandler;
                }
            }
            System.out.println(String.format("[%d] unexpected request %s %s", id, httpExchange.getRequestMethod(), httpExchange.getRequestURI()));
            outcome.failure = new AssertionError(String.format("Received unexpected request %s %s", httpExchange.getRequestMethod(), httpExchange.getRequestURI().getPath()));
        } catch (Throwable t) {
            System.out.println(String.format("[%d] error during handling of request %s %s", id, httpExchange.getRequestMethod(), httpExchange.getRequestURI()));
            outcome.failure = new AssertionError(String.format("Failed to handle %s %s", httpExchange.getRequestMethod(), httpExchange.getRequestURI().getPath()), t);
        } finally {
            lock.unlock();
        }
        return null;
    }

    void waitForRequests(int requestCount) {
        lock.lock();
        try {
            while (this.requestCount < requestCount) {
                try {
                    condition.await();
                } catch (InterruptedException e) {
                    throw UncheckedException.throwAsUncheckedException(e);
                }
            }
        } finally {
            lock.unlock();
        }
    }

    private static class RequestOutcome {
        final String method;
        final String url;
        Throwable failure;
        boolean completed;

        public RequestOutcome(String method, String url) {
            this.method = method;
            this.url = url;
        }

        String getDisplayName() {
            return method + " " + url;
        }

        public void collectFailures(List<Throwable> failures) {
            if (failure != null) {
                failures.add(failure);
            }
            if (!completed) {
                failures.add(new AssertionError(String.format("Request %s has not yet completed.", getDisplayName())));
            }
        }
    }

    interface HandlerFactory<T extends TrackingHttpHandler> {
        T create(WaitPrecondition previous);
    }
}
