package com.willy.quartzplay.repository;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface FiredTriggerRepository extends JpaRepository<QrtzFiredTrigger, QrtzFiredTriggerId> {

    Optional<QrtzFiredTrigger> findFirstBySchedNameAndJobNameAndJobGroupAndState(
            String schedName, String jobName, String jobGroup, String state);
}
