package com.willy.quartzplay.repository;

import java.util.List;
import java.util.Optional;

public class FiredTriggerStorageAdapter {

    public record FiredTriggerInfo(String schedulerName, String fireInstanceId, String instanceName,
                                   String jobName, String jobGroup, String state) {}

    private final Storage storage;

    public static FiredTriggerStorageAdapter create(FiredTriggerRepository repository) {
        return new FiredTriggerStorageAdapter(new JpaStorage(repository));
    }

    public static FiredTriggerStorageAdapter createNull(List<FiredTriggerInfo> triggers) {
        return new FiredTriggerStorageAdapter(new NulledStorage(triggers));
    }

    private FiredTriggerStorageAdapter(Storage storage) {
        this.storage = storage;
    }

    public Optional<FiredTriggerInfo> findExecutingTrigger(String schedulerName, String jobName, String jobGroup) {
        return storage.findExecutingTrigger(schedulerName, jobName, jobGroup);
    }

    private interface Storage {
        Optional<FiredTriggerInfo> findExecutingTrigger(String schedulerName, String jobName, String jobGroup);
    }

    private static class JpaStorage implements Storage {
        private final FiredTriggerRepository repository;

        JpaStorage(FiredTriggerRepository repository) {
            this.repository = repository;
        }

        @Override
        public Optional<FiredTriggerInfo> findExecutingTrigger(String schedulerName, String jobName, String jobGroup) {
            return repository.findFirstBySchedNameAndJobNameAndJobGroupAndState(
                    schedulerName, jobName, jobGroup, "EXECUTING")
                .map(t -> new FiredTriggerInfo(
                    t.getSchedName(), t.getEntryId(), t.getInstanceName(),
                    t.getJobName(), t.getJobGroup(), t.getState()));
        }
    }

    private static class NulledStorage implements Storage {
        private final List<FiredTriggerInfo> triggers;

        NulledStorage(List<FiredTriggerInfo> triggers) {
            this.triggers = triggers;
        }

        @Override
        public Optional<FiredTriggerInfo> findExecutingTrigger(String schedulerName, String jobName, String jobGroup) {
            return triggers.stream()
                .filter(t -> t.schedulerName().equals(schedulerName)
                    && t.jobName().equals(jobName)
                    && t.jobGroup().equals(jobGroup)
                    && "EXECUTING".equals(t.state()))
                .findFirst();
        }
    }
}
