package com.willy.quartzplay.job;

public enum GroupName {

    DEFAULT("DEFAULT");

    private final String value;

    GroupName(String value) {
        this.value = value;
    }

    @Override
    public String toString() {
        return value;
    }
}
