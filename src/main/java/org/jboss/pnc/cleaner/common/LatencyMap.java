package org.jboss.pnc.cleaner.common;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class LatencyMap {
    private static LatencyMap instance = null;
    static private Map<String, LatencyMiniMax> methodLatencyMap = new ConcurrentHashMap<>();

    private LatencyMap() {
        methodLatencyMap.put("DeleteCallbackManager_await", new LatencyMiniMax());

        methodLatencyMap.put("FailedBuildsCleaner_cleanOlder", new LatencyMiniMax());
        methodLatencyMap.put("FailedBuildsCleaner_initIndy", new LatencyMiniMax());
        methodLatencyMap.put("FailedBuildsCleaner_getGroupNames", new LatencyMiniMax());
        methodLatencyMap.put("FailedBuildsCleaner_cleanBuildIfNeeded", new LatencyMiniMax());
        methodLatencyMap.put("FailedBuildsCleaner_getBuildRecord", new LatencyMiniMax());
        methodLatencyMap.put("FailedBuildsCleaner_deleteGroupAndHostedRepo", new LatencyMiniMax());

        methodLatencyMap.put("TemporaryBuildsCleanerAdapterImpl_findTemporaryBuildsOlderThan", new LatencyMiniMax());
        methodLatencyMap.put("TemporaryBuildsCleanerAdapterImpl_deleteTemporaryBuild", new LatencyMiniMax());
        methodLatencyMap
                .put("TemporaryBuildsCleanerAdapterImpl_findTemporaryGroupBuildsOlderThan", new LatencyMiniMax());
        methodLatencyMap.put("TemporaryBuildsCleanerAdapterImpl_deleteTemporaryGroupBuild", new LatencyMiniMax());
        methodLatencyMap.put("TemporaryBuildsCleanerAdapterImpl_formatTimestampForRsql", new LatencyMiniMax());

        methodLatencyMap.put("TemporaryBuildsCleanerImpl_deleteExpiredBuildConfigSetRecords", new LatencyMiniMax());
        methodLatencyMap.put("TemporaryBuildsCleanerImpl_deleteExpiredBuildRecords", new LatencyMiniMax());
    }

    public static LatencyMap getInstance() {
        if (instance == null) {
            instance = new LatencyMap();
        }
        return instance;
    }

    public Map<String, LatencyMiniMax> getMethodLatencyMap() {
        return methodLatencyMap;
    }
}
