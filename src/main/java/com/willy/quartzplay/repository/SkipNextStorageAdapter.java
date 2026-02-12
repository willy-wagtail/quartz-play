package com.willy.quartzplay.repository;

import java.util.HashSet;
import java.util.Set;

public class SkipNextStorageAdapter {

    private final Storage storage;

    public static SkipNextStorageAdapter create(SkipNextRepository repository) {
        return new SkipNextStorageAdapter(new JpaStorage(repository));
    }

    public static SkipNextStorageAdapter createNull() {
        return new SkipNextStorageAdapter(new NulledStorage());
    }

    private SkipNextStorageAdapter(Storage storage) {
        this.storage = storage;
    }

    public boolean exists(String jobName) {
        return storage.exists(jobName);
    }

    public void save(String jobName) {
        storage.save(jobName);
    }

    public void delete(String jobName) {
        storage.delete(jobName);
    }

    private interface Storage {
        boolean exists(String jobName);
        void save(String jobName);
        void delete(String jobName);
    }

    private static class JpaStorage implements Storage {
        private final SkipNextRepository repository;

        JpaStorage(SkipNextRepository repository) {
            this.repository = repository;
        }

        @Override
        public boolean exists(String jobName) {
            return repository.existsById(jobName);
        }

        @Override
        public void save(String jobName) {
            repository.save(new SkipNextEntity(jobName));
        }

        @Override
        public void delete(String jobName) {
            repository.deleteById(jobName);
        }
    }

    private static class NulledStorage implements Storage {
        private final Set<String> skips = new HashSet<>();

        @Override
        public boolean exists(String jobName) {
            return skips.contains(jobName);
        }

        @Override
        public void save(String jobName) {
            skips.add(jobName);
        }

        @Override
        public void delete(String jobName) {
            skips.remove(jobName);
        }
    }
}
