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
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.eclipse.microprofile.metrics.MetricUnits;
import org.eclipse.microprofile.metrics.annotation.Gauge;
import org.jboss.pnc.common.util.TimeUtils;
import org.jboss.pnc.dto.Build;
import org.jboss.pnc.dto.GroupBuild;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.enterprise.context.ApplicationScoped;
import javax.inject.Inject;
import javax.ws.rs.GET;
import javax.ws.rs.Produces;
import java.util.Collection;
import java.util.Date;
import java.util.HashSet;
import java.util.Set;

/**
 * Deletes temporary builds via Orchestrator REST API
 *
 * @author Jakub Bartecek
 */
@ApplicationScoped
public class TemporaryBuildsCleanerImpl implements TemporaryBuildsCleaner {
    static final Counter exceptionsTotal = Counter.build()
            .name("TemporaryBuildsCleanerImpl_Exceptions_Total")
            .help("Errors and Warnings counting metric")
            .labelNames("severity")
            .register();

    static final Summary requestLatency = Summary.build()
            .name("TemporaryBuildsCleanerImpl_Requests_Latency")
            .help("Request latency in seconds")
            .labelNames("expired_build_records_type")
            .register();

    private final Logger log = LoggerFactory.getLogger(TemporaryBuildsCleanerImpl.class);

    @ConfigProperty(name = "temporaryBuildsCleaner.lifespan")
    Integer TEMPORARY_BUILD_LIFESPAN;

    @Inject
    TemporaryBuildsCleanerAdapter temporaryBuildsCleanerAdapter;

    @Override
    public void cleanupExpiredTemporaryBuilds() {
        log.info(
                "Regular cleanup of expired temporary builds started. Removing builds older than "
                        + TEMPORARY_BUILD_LIFESPAN + " days.");
        Date expirationThreshold = TimeUtils.getDateXDaysAgo(TEMPORARY_BUILD_LIFESPAN);

        deleteExpiredBuildConfigSetRecords(expirationThreshold);
        deleteExpiredBuildRecords(expirationThreshold);

        log.info("Regular cleanup of expired temporary builds finished.");
    }

    void deleteExpiredBuildConfigSetRecords(Date expirationThreshold) {
        Summary.Timer requestTimer = requestLatency.labels("config_set_records").startTimer();
        Collection<GroupBuild> expiredBCSRecords = temporaryBuildsCleanerAdapter
                .findTemporaryGroupBuildsOlderThan(expirationThreshold);

        for (GroupBuild groupBuild : expiredBCSRecords) {
            try {
                log.info("Deleting temporary BuildConfigSetRecord {}", groupBuild);
                temporaryBuildsCleanerAdapter.deleteTemporaryGroupBuild(groupBuild.getId());
                log.info("Temporary BuildConfigSetRecord {} was deleted successfully", groupBuild);
            } catch (OrchInteractionException ex) {
                exceptionsTotal.labels("warning").inc();
                log.warn("Deletion of temporary BuildConfigSetRecord {} failed!", groupBuild);
            }
        }
        requestTimer.observeDuration();
    }

    void deleteExpiredBuildRecords(Date expirationThreshold) {
        Summary.Timer requestTimer = requestLatency.labels("records").startTimer();
        Set<Build> failedBuilds = new HashSet<>();
        Collection<Build> expiredBuilds = null;
        do {
            log.info("Doing an iteration of Temporary Builds deletion.");
            expiredBuilds = temporaryBuildsCleanerAdapter.findTemporaryBuildsOlderThan(expirationThreshold);
            expiredBuilds.removeAll(failedBuilds);
            for (Build build : expiredBuilds) {
                try {
                    log.info("Deleting temporary build {}", build);
                    temporaryBuildsCleanerAdapter.deleteTemporaryBuild(build.getId());
                    log.info("Temporary build {} was deleted successfully", build);
                } catch (OrchInteractionException ex) {
                    exceptionsTotal.labels("warning").inc();
                    log.warn("Deletion of temporary build {} failed! Cause: {}", build, ex);
                    failedBuilds.add(build);
                }
            }
        } while (!expiredBuilds.isEmpty());

        requestTimer.observeDuration();
    }

    @GET
    @Produces("text/plain")
    @Gauge(name = "TemporaryBuildsCleanerImpl_Warn_Count", unit = MetricUnits.NONE, description = "Warnings count")
    public int showCurrentWarnCount() {
        return (int) exceptionsTotal.labels("warning").get();
    }
}
