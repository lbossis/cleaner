package org.jboss.pnc.cleaner.logverifier;

import io.prometheus.client.Counter;
import io.prometheus.client.Summary;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.eclipse.microprofile.metrics.MetricUnits;
import org.eclipse.microprofile.metrics.annotation.Gauge;
import org.eclipse.microprofile.metrics.annotation.Timed;
import org.eclipse.microprofile.rest.client.inject.RestClient;
import org.jboss.pnc.api.bifrost.dto.MetaData;
import org.jboss.pnc.api.bifrost.enums.Direction;
import org.jboss.pnc.cleaner.common.LatencyMap;
import org.jboss.pnc.cleaner.common.LatencyMiniMax;
import org.jboss.pnc.client.BuildClient;
import org.jboss.pnc.client.RemoteCollection;
import org.jboss.pnc.client.RemoteResourceException;
import org.jboss.pnc.dto.Build;
import org.jboss.pnc.rest.api.parameters.BuildsFilterParameters;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.enterprise.context.ApplicationScoped;
import javax.inject.Inject;
import javax.ws.rs.GET;
import javax.ws.rs.Produces;
import javax.ws.rs.core.MediaType;
import java.io.IOException;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * @author <a href="mailto:matejonnet@gmail.com">Matej Lazar</a>
 */
@ApplicationScoped
public class BuildLogVerifier {

    static final Counter exceptionsTotal = Counter.build()
            .name("BuildLogVerifier_Exceptions_Total")
            .help("Errors and Warnings counting metric")
            .labelNames("severity")
            .register();

    static final Summary requestLatency = Summary.build()
            .name("BuildLogVerifier_Requests_Latency")
            .help("Request latency in seconds")
            .labelNames("method")
            .register();

    static final Map<String, LatencyMiniMax> methodLatencyMap = LatencyMap.getInstance().getMethodLatencyMap();

    static final String className = "BuildLogVerifier";

    private final Logger logger = LoggerFactory.getLogger(BuildLogVerifier.class);

    @Inject
    @RestClient
    BifrostClient bifrost;

    @Inject
    BuildClient buildClient;

    @ConfigProperty(name = "buildLogVerifierScheduler.maxRetries")
    private Integer maxRetries;

    private final Map<String, AtomicInteger> buildESLogErrorCounter = new HashMap<>();

    public static final String BUILD_OUTPUT_OK_KEY = "BUILD_OUTPUT_OK";

    public BuildLogVerifier() {
    }

    @Timed
    public int verifyUnflaggedBuilds() {
        logger.info("Verifying log checksums ...");
        Summary.Timer requestTimer = requestLatency.labels("verifyUnflaggedBuilds").startTimer();
        Collection<Build> unverifiedBuilds = getUnverifiedBuilds().getAll();
        logger.info("Found {} unverified builds.", unverifiedBuilds.size());
        unverifiedBuilds.forEach(build -> verify(build.getId(), build.getBuildOutputChecksum()));
        LatencyMiniMax lcyMap = methodLatencyMap.get(className + "_verifyUnflaggedBuilds");
        if (lcyMap != null) {
            lcyMap.update(requestTimer.observeDuration());
        }
        return unverifiedBuilds.size();
    }

    @Timed
    private void verify(String buildId, String checksum) {
        try {
            logger.debug("Verifying log for build id: {}", buildId);
            Summary.Timer requestTimer = requestLatency.labels("verify").startTimer();
            String esChecksum = getESChecksum(buildId);
            if (checksum.equals(esChecksum)) {
                logger.info("Build output checksum OK. BuildId: {}, Checksum: {}.", buildId, checksum);
                flagPncBuild(buildId, true);

                removeRetryCounter(buildId);
                LatencyMiniMax lcyMap = methodLatencyMap.get(className + "_verify");
                if (lcyMap != null) {
                    lcyMap.update(requestTimer.observeDuration());
                }
            } else {
                exceptionsTotal.labels("warning").inc();
                logger.warn(
                        "Build output checksum MISMATCH. BuildId: {}, Db checksum: {}, ElasticSearch checksum {}.",
                        buildId,
                        checksum,
                        esChecksum);

                handleMismatchWithRetries(buildId);
            }
        } catch (IOException e) {
            exceptionsTotal.labels("error").inc();
            logger.error("Cannot verify checksum for buildId: " + buildId + ".", e);
        }
    }

    private void removeRetryCounter(String buildId) {
        buildESLogErrorCounter.remove(buildId);
    }

    private void handleMismatchWithRetries(String buildId) {
        if (!buildESLogErrorCounter.containsKey(buildId)) {
            buildESLogErrorCounter.put(buildId, new AtomicInteger(0));
        }

        AtomicInteger numOfRetries = buildESLogErrorCounter.get(buildId);
        if (numOfRetries.get() >= maxRetries) {
            exceptionsTotal.labels("warning").inc();
            logger.warn("Marking build with id: {} as mismatch", buildId);
            flagPncBuild(buildId, false);
            removeRetryCounter(buildId);
            return;
        }

        exceptionsTotal.labels("warning").inc();
        logger.warn("Increasing retry counter (counter: {}) for build with id: {}", numOfRetries, buildId);
        buildESLogErrorCounter.get(buildId).incrementAndGet();
    }

    private String getESChecksum(String buildId) throws IOException {
        String matchFilters = "mdc.processContext.keyword:build-" + buildId;
        String prefixFilters = "loggerName.keyword:org.jboss.pnc._userlog_.build-log";

        MetaData metaData = bifrost.getMetaData(matchFilters, prefixFilters, null, Direction.ASC, null);
        return metaData.getMd5Digest();
    }

    private void flagPncBuild(String buildId, boolean checksumMatch) {
        try {
            buildClient.addAttribute(buildId, BUILD_OUTPUT_OK_KEY, Boolean.toString(checksumMatch));
        } catch (RemoteResourceException e) {
            exceptionsTotal.labels("error").inc();
            logger.error("Cannot set {} attribute to build id: {}.", checksumMatch, buildId);
        }
    }

    @Timed
    private RemoteCollection<Build> getUnverifiedBuilds() {
        Summary.Timer requestTimer = requestLatency.labels("getUnverifiedBuilds").startTimer();

        BuildsFilterParameters buildsFilterParameters = new BuildsFilterParameters();
        buildsFilterParameters.setRunning(false);
        List<String> attributes = Collections.singletonList("!" + BUILD_OUTPUT_OK_KEY);
        try {
            String query = "buildOutputChecksum!=null";
            RemoteCollection<Build> res = buildClient
                    .getAll(buildsFilterParameters, attributes, Optional.empty(), Optional.of(query));
            LatencyMiniMax lcyMap = methodLatencyMap.get(className + "_getUnverifiedBuilds");
            if (lcyMap != null) {
                lcyMap.update(requestTimer.observeDuration());
            }
            return res;
        } catch (RemoteResourceException e) {
            exceptionsTotal.labels("error").inc();
            logger.error("Cannot read remote builds.", e);
            return RemoteCollection.empty();
        }
    }

    @GET
    @Produces(MediaType.TEXT_PLAIN)
    @Gauge(name = "BuildLogVerifier_Err_Count", unit = MetricUnits.NONE, description = "Errors count")
    public int showCurrentErrCount() {
        return (int) exceptionsTotal.labels("error").get();
    }

    @GET
    @Produces(MediaType.TEXT_PLAIN)
    @Gauge(name = "BuildLogVerifier_Warn_Count", unit = MetricUnits.NONE, description = "Warnings count")
    public int showCurrentWarnCount() {
        return (int) exceptionsTotal.labels("warning").get();
    }
}
