/**
 * JBoss, Home of Professional Open Source.
 * Copyright 2014-2019 Red Hat, Inc., and individual contributors
 * as indicated by the @author tags.
 * <p>
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * <p>
 * http://www.apache.org/licenses/LICENSE-2.0
 * <p>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.jboss.pnc.cleaner.temporaryBuilds;

import io.prometheus.client.Counter;
import io.prometheus.client.Summary;

import lombok.Data;
import lombok.extern.slf4j.Slf4j;

import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.eclipse.microprofile.metrics.MetricUnits;
import org.eclipse.microprofile.metrics.annotation.Gauge;
import org.eclipse.microprofile.metrics.annotation.Timed;
import org.jboss.pnc.cleaner.common.LatencyMap;
import org.jboss.pnc.dto.response.DeleteOperationResult;
import org.jboss.pnc.cleaner.common.LatencyMiniMax;

import javax.ws.rs.GET;
import javax.ws.rs.Produces;
import javax.ws.rs.core.MediaType;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Manages delete operation callbacks and provides a blocking was of waiting fot the operation completion. First the
 * wait operation must be initiated using a method #initializeHandler and then at any time a blocking method #await can
 * be called.
 *
 * @author Jakub Bartecek
 */
@Slf4j
public class DeleteCallbackManager {
    static final Counter exceptionsTotal = Counter.build()
            .name("DeleteCallbackManager_Exceptions_Total")
            .help("Errors and Warnings counting metric")
            .labelNames("severity")
            .register();

    static final Summary requestLatency = Summary.build()
            .name("DeleteCallbackManager_Requests_Latency")
            .help("Request latency in seconds")
            .labelNames("method")
            .register();

    static final Map<String, LatencyMiniMax> methodLatencyMap = LatencyMap.getInstance().getMethodLatencyMap();

    static final String className = "DeleteCallbackManager";

    private Map<String, CallbackData> buildsMap = new ConcurrentHashMap<>();

    @ConfigProperty(name = "simpleCallbackHandler.max-delete-wait-time", defaultValue = "600")
    long MAX_WAIT_TIME;

    /**
     * Initialize data to wait for a completion of a deletion of a specific build
     *
     * @param buildId ID of a build to wait for
     * @return True if succeeds. False if this build is already registered.
     */
    public boolean initializeHandler(String buildId) {
        if (buildsMap.containsKey(buildId)) {
            // Delete operation is already in progress and waiting for that build deletion
            return false;
        }

        buildsMap.put(buildId, new CallbackData());
        return true;
    }

    /**
     * Registers a response to a delete operation completion
     *
     * @param buildId ID of a build, which deletion completed
     * @param result Result of the operation
     */
    public void callback(String buildId, DeleteOperationResult result) {
        CallbackData callbackData = buildsMap.get(buildId);
        if (callbackData != null) {
            callbackData.setCallbackResponse(result);
            callbackData.getCountDownLatch().countDown();
        } else {
            exceptionsTotal.labels("warning").inc();
            log.warn(
                    "Delete operation callback called for a delete operation, which was not initialized. BuildId: "
                            + "{}",
                    buildId);
        }
    }

    /**
     * Blocking wait operation for completion of delete operation. It waits for a configurable maximum time.
     *
     * @param buildId Build ID
     * @return Result of the operation or null if the callback was not triggered
     * @throws InterruptedException Thrown if an error occurs while waiting for callback
     */
    @Timed
    public DeleteOperationResult await(String buildId) throws InterruptedException {
        Summary.Timer requestTimer = requestLatency.labels("await").startTimer();
        CallbackData callbackData = buildsMap.get(buildId);
        if (callbackData == null) {
            exceptionsTotal.labels("error").inc();
            throw new IllegalArgumentException(
                    "Await operation triggered for a build, which was not initiated using "
                            + "method initializeHandler. This method must be called first!");
        }

        callbackData.getCountDownLatch().await(MAX_WAIT_TIME, TimeUnit.SECONDS);
        buildsMap.remove(buildId);
        DeleteOperationResult rc = callbackData.getCallbackResponse();
        LatencyMiniMax lcyMap = methodLatencyMap.get(className + "_await");
        if (lcyMap != null) {
            lcyMap.update(requestTimer.observeDuration());
        }
        return rc;
    }

    public void cancel(String buildId) {
        buildsMap.remove(buildId);
    }

    @Data
    public class CallbackData {

        private DeleteOperationResult callbackResponse = null;

        private final CountDownLatch countDownLatch = new CountDownLatch(1);
    }

    // @FIXME
    // Test case org.jboss.pnc.cleaner.temporaryBuilds.TemporaryBuildsCleanerImplTest.java
    // fails if two methods below are not commented out
    //
    // @GET
    // @Produces(MediaType.TEXT_PLAIN)
    // @Gauge(name = "DeleteCallbackManager_Err_Count", unit = MetricUnits.NONE, description = "Errors count")
    // public int showCurrentErrCount() {
    // return (int) exceptionsTotal.labels("error").get();
    // }
    //
    // @GET
    // @Produces(MediaType.TEXT_PLAIN)
    // @Gauge(name = "DeleteCallbackManager_Warn_Count", unit = MetricUnits.NONE, description = "Warnings count")
    // public int showCurrentWarnCount() {
    // return (int) exceptionsTotal.labels("warning").get();
    // }
}
