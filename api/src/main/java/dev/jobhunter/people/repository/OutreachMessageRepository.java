package dev.jobhunter.people.repository;

import dev.jobhunter.people.model.OutreachMessage;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface OutreachMessageRepository extends JpaRepository<OutreachMessage, UUID> {

    List<OutreachMessage> findByContactIdOrderBySentAtDesc(UUID contactId);
}
