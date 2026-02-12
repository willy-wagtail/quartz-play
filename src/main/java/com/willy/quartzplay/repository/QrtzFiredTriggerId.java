package com.willy.quartzplay.repository;

import java.io.Serializable;
import java.util.Objects;

public class QrtzFiredTriggerId implements Serializable {

    private String schedName;
    private String entryId;

    public QrtzFiredTriggerId() {}

    public QrtzFiredTriggerId(String schedName, String entryId) {
        this.schedName = schedName;
        this.entryId = entryId;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof QrtzFiredTriggerId that)) return false;
        return Objects.equals(schedName, that.schedName) && Objects.equals(entryId, that.entryId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(schedName, entryId);
    }
}
