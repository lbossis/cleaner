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
import lombok.extern.slf4j.Slf4j;

import org.eclipse.microprofile.config.Config;
import org.eclipse.microprofile.metrics.MetricUnits;
import org.eclipse.microprofile.metrics.annotation.Gauge;
import org.jboss.pnc.cleaner.common.LatencyMap;
import org.jboss.pnc.cleaner.common.LatencyMiniMax;
import org.jboss.pnc.client.BuildClient;
import org.jboss.pnc.client.GroupBuildClient;
import org.jboss.pnc.client.RemoteCollection;
import org.jboss.pnc.client.RemoteResourceException;
import org.jboss.pnc.dto.Build;
import org.jboss.pnc.dto.response.DeleteOperationResult;
import org.jboss.pnc.dto.GroupBuild;

import javax.annotation.PostConstruct;
import javax.enterprise.context.ApplicationScoped;
import javax.inject.Inject;
import javax.ws.rs.GET;
import javax.ws.rs.Produces;
import javax.ws.rs.core.MediaType;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Collection;
import java.util.Date;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Implementation of an adapter providing high-level operations on Orchestrator REST API
 *
 * @author Jakub Bartecek
 */
@ApplicationScoped
@Slf4j
public class TemporaryBuildsCleanerAdapterImpl implements TemporaryBuildsCleanerAdapter {
    static final Counter exceptionsTotal = Counter.build()
            .name("TemporaryBuildsCleanerAdapterImpl_Exceptions_Total")
            .help("Errors and Warnings counting metric")
            .labelNames("severity")
            .register();

    static final Summary requestLatency = Summary.build()
            .name("TemporaryBuildsCleanerAdapterImpl_Requests_Latency")
            .help("Request latency in seconds")
            .labelNames("method")
            .register();

    static final Map<String, LatencyMiniMax> methodLatencyMap = LatencyMap.getInstance().getMethodLatencyMap();

    static final String className = "TemporaryBuildsCleanerAdapterImpl";

    private String BASE_DELETE_BUILD_CALLBACK_URL;

    private String BASE_DELETE_BUILD_GROUP_CALLBACK_URL;

    @Inject
    Config config;

    @Inject
    BuildClient buildClient;

    @Inject
    GroupBuildClient groupBuildClient;

    @Inject
    BuildDeleteCallbackManager buildDeleteCallbackManager;

    @Inject
    BuildGroupDeleteCallbackManager buildGroupDeleteCallbackManager;

    @PostConstruct
    void init() {
        final String host = config.getValue("applicationUri", String.class);

        BASE_DELETE_BUILD_CALLBACK_URL = host + "/callbacks/delete/builds/";
        BASE_DELETE_BUILD_GROUP_CALLBACK_URL = host + "/callbacks/delete/group-builds/";
    }

    @Override
    public Collection<Build> findTemporaryBuildsOlderThan(Date expirationDate) {
        Summary.Timer requestTimer = requestLatency.labels("findTemporaryBuildsOlderThan").startTimer();
        Collection<Build> buildsRest = new HashSet<>();

        try {
            RemoteCollection<Build> remoteCollection = buildClient
                    .getAllIndependentTempBuildsOlderThanTimestamp(expirationDate.getTime());
            remoteCollection.forEach(buildsRest::add);
        } catch (RemoteResourceException e) {
            exceptionsTotal.labels("warning").inc();
            log.warn(
                    "Querying of temporary builds from Orchestrator failed with [status: {}, errorResponse: {}]",
                    e.getStatus(),
                    e.getResponse().orElse(null));
            return buildsRest;
        }

        LatencyMiniMax lcyMap = methodLatencyMap.get(className + "_findTemporaryBuildsOlderThan");
        if (lcyMap != null) {
            lcyMap.update(requestTimer.observeDuration());
        }
        return buildsRest;
    }

    @Override
    public void deleteTemporaryBuild(String id) throws OrchInteractionException {
        Summary.Timer requestTimer = requestLatency.labels("deleteTemporaryBuild").startTimer();
        buildDeleteCallbackManager.initializeHandler(id);
        try {
            buildClient.delete(id, BASE_DELETE_BUILD_CALLBACK_URL + id);
            DeleteOperationResult result = buildDeleteCallbackManager.await(id);

            if (result != null && result.getStatus() != null && result.getStatus().isSuccess()) {
                LatencyMiniMax lcyMap = methodLatencyMap.get(className + "_deleteTemporaryBuild");
                if (lcyMap != null) {
                    lcyMap.update(requestTimer.observeDuration());
                }
                return;
            } else {
                exceptionsTotal.labels("error").inc();
                throw new OrchInteractionException(
                        String.format(
                                "Deletion of a build %s failed! " + "Orchestrator"
                                        + " reported a failure: [status={}, message={}].",
                                result == null ? null : result.getStatus(),
                                result == null ? null : result.getMessage()));
            }

        } catch (RemoteResourceException e) {
            buildDeleteCallbackManager.cancel(id);
            throw new OrchInteractionException(
                    String.format(
                            "Deletion of a build %s failed! The operation " + "failed with errorStatus=%s.",
                            id,
                            e.getStatus()),
                    e);
        } catch (InterruptedException e) {
            exceptionsTotal.labels("error").inc();
            buildDeleteCallbackManager.cancel(id);
            throw new OrchInteractionException(
                    String.format("Deletion of a build %s failed! Wait operation " + "failed with an exception.", id),
                    e);
        }

    }

    @Override
    public Collection<GroupBuild> findTemporaryGroupBuildsOlderThan(Date expirationDate) {
        Summary.Timer requestTimer = requestLatency.labels("findTemporaryGroupBuildsOlderThan").startTimer();
        Collection<GroupBuild> groupBuilds = new HashSet<>();
        try {
            RemoteCollection<GroupBuild> remoteCollection = groupBuildClient.getAll(
                    Optional.empty(),
                    Optional.of("temporaryBuild==TRUE;endTime<" + formatTimestampForRsql(expirationDate)));
            remoteCollection.forEach(build -> groupBuilds.add(build));

        } catch (RemoteResourceException e) {
            exceptionsTotal.labels("warning").inc();
            log.warn(
                    "Querying of temporary group builds from Orchestrator failed with [status: {}, errorResponse: "
                            + "{}]",
                    e.getStatus(),
                    e.getResponse().orElse(null));
        }

        LatencyMiniMax lcyMap = methodLatencyMap.get(className + "_findTemporaryGroupBuildsOlderThan");
        if (lcyMap != null) {
            lcyMap.update(requestTimer.observeDuration());
        }
        return groupBuilds;
    }

    @Override
    public void deleteTemporaryGroupBuild(String id) throws OrchInteractionException {
        Summary.Timer requestTimer = requestLatency.labels("deleteTemporaryGroupBuild").startTimer();
        buildGroupDeleteCallbackManager.initializeHandler(id);

        try {
            groupBuildClient.delete(id, BASE_DELETE_BUILD_GROUP_CALLBACK_URL + id);
            DeleteOperationResult result = buildGroupDeleteCallbackManager.await(id);

            if (result != null && result.getStatus() != null && result.getStatus().isSuccess()) {
                LatencyMiniMax lcyMap = methodLatencyMap.get(className + "_deleteTemporaryGroupBuild");
                if (lcyMap != null) {
                    lcyMap.update(requestTimer.observeDuration());
                }
                return;
            } else {
                exceptionsTotal.labels("error").inc();
                throw new OrchInteractionException(
                        String.format(
                                "Deletion of a group build %s failed! " + "Orchestrator"
                                        + " reported a failure: [status={}, message={}].",
                                result == null ? null : result.getStatus(),
                                result == null ? null : result.getMessage()));
            }

        } catch (RemoteResourceException e) {
            exceptionsTotal.labels("error").inc();
            buildDeleteCallbackManager.cancel(id);
            throw new OrchInteractionException(
                    String.format(
                            "Deletion of a group build %s failed! The operation " + "failed with errorMessage=%s.",
                            id,
                            e.getStatus()),
                    e);
        } catch (InterruptedException e) {
            exceptionsTotal.labels("error").inc();
            buildDeleteCallbackManager.cancel(id);
            throw new OrchInteractionException(
                    String.format(
                            "Deletion of a group build %s failed! Wait operation " + "failed with an exception.",
                            id),
                    e);
        }
    }

    private String formatTimestampForRsql(Date expirationDate) {
        Summary.Timer requestTimer = requestLatency.labels("formatTimestampForRsql").startTimer();
        String res = DateTimeFormatter.ISO_DATE_TIME.withLocale(Locale.ROOT)
                .withZone(ZoneId.of("UTC"))
                .format(Instant.ofEpochMilli(expirationDate.getTime()));
        LatencyMiniMax lcyMap = methodLatencyMap.get(className + "_formatTimestampForRsql");
        if (lcyMap != null) {
            lcyMap.update(requestTimer.observeDuration());
        }
        return res;
    }

    @GET
    @Produces(MediaType.TEXT_PLAIN)
    @Gauge(name = "TemporaryBuildsCleanerAdapterImpl_Err_Count", unit = MetricUnits.NONE, description = "Errors count")
    public int showCurrentErrCount() {
        return (int) exceptionsTotal.labels("error").get();
    }

    @GET
    @Produces(MediaType.TEXT_PLAIN)
    @Gauge(
            name = "TemporaryBuildsCleanerAdapterImpl_Warn_Count",
            unit = MetricUnits.NONE,
            description = "Warnings count")
    public int showCurrentWarnCount() {
        return (int) exceptionsTotal.labels("warning").get();
    }
}
