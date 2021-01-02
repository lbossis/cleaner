package org.jboss.pnc.cleaner.builds;

import io.prometheus.client.Counter;
import io.prometheus.client.Summary;
import org.commonjava.indy.client.core.Indy;
import org.commonjava.indy.client.core.IndyClientException;
import org.commonjava.indy.client.core.module.IndyStoresClientModule;
import org.commonjava.indy.folo.client.IndyFoloAdminClientModule;
import org.commonjava.indy.model.core.Group;
import org.commonjava.indy.model.core.dto.StoreListingDTO;
import org.eclipse.microprofile.metrics.MetricUnits;
import org.eclipse.microprofile.metrics.annotation.Gauge;
import org.jboss.pnc.cleaner.common.LatencyMap;
import org.jboss.pnc.cleaner.common.LatencyMiniMax;

import javax.ws.rs.GET;
import javax.ws.rs.Produces;
import javax.ws.rs.core.MediaType;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.commonjava.indy.model.core.GenericPackageTypeDescriptor.GENERIC_PKG_KEY;

public class FailedBuildsCleanerSession {

    static final Counter exceptionsTotal = Counter.build()
            .name("FailedBuildsCleanerSession_Exceptions_Total")
            .help("Errors and Warnings counting metric")
            .labelNames("severity")
            .register();

    static final Summary requestLatency = Summary.build()
            .name("FailedBuildsCleanerSession_Requests_Latency")
            .help("Request latency in seconds")
            .labelNames("key")
            .register();

    static final Map<String, LatencyMiniMax> methodLatencyMap = LatencyMap.getInstance().getMethodLatencyMap();

    static final String className = "FailedBuildsCleanerSession";

    private IndyFoloAdminClientModule foloAdmin;
    private IndyStoresClientModule stores;

    private List<Group> genericGroups;

    private Instant to;

    public FailedBuildsCleanerSession(Indy indyClient, Instant to) {
        Summary.Timer requestTimer = requestLatency.labels("FailedBuildsCleanerSession").startTimer();
        try {
            this.stores = indyClient.stores();
            this.foloAdmin = indyClient.module(IndyFoloAdminClientModule.class);
        } catch (IndyClientException e) {
            exceptionsTotal.labels("error").inc();
            throw new RuntimeException("Unable to retrieve Indy client module: " + e, e);
        }
        this.to = to;
        LatencyMiniMax lcyMap = methodLatencyMap.get(className + "_FailedBuildsCleanerSession");
        if (lcyMap != null) {
            lcyMap.update(requestTimer.observeDuration());
        }
    }

    public List<Group> getGenericGroups() {
        if (genericGroups == null) {
            Summary.Timer requestTimer = requestLatency.labels("getGenericGroups").startTimer();
            try {
                StoreListingDTO<Group> groupListing = stores.listGroups(GENERIC_PKG_KEY);
                genericGroups = groupListing.getItems();
            } catch (IndyClientException e) {
                exceptionsTotal.labels("error").inc();
                throw new RuntimeException("Error in loading generic http groups: " + e, e);
            }
            LatencyMiniMax lcyMap = methodLatencyMap.get(className + "_getGenericGroups");
            if (lcyMap != null) {
                lcyMap.update(requestTimer.observeDuration());
            }
        }
        return genericGroups;
    }

    public IndyFoloAdminClientModule getFoloAdmin() {
        return foloAdmin;
    }

    public IndyStoresClientModule getStores() {
        return stores;
    }

    public Instant getTo() {
        return to;
    }

    // @GET
    // @Produces(MediaType.TEXT_PLAIN)
    // @Gauge(name = "FailedBuildsCleanerSession_Err_Count", unit = MetricUnits.NONE, description = "Errors count")
    // public int showCurrentErrCount() {
    // return (int) exceptionsTotal.labels("error").get();
    // }
    //
    // @GET
    // @Produces(MediaType.TEXT_PLAIN)
    // @Gauge(name = "FailedBuildsCleanerSession_Warn_Count", unit = MetricUnits.NONE, description = "Warnings count")
    // public int showCurrentWarnCount() {
    // return (int) exceptionsTotal.labels("warning").get();
    // }
}
