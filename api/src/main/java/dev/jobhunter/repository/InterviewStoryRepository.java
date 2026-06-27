package dev.jobhunter.repository;

import dev.jobhunter.model.InterviewStory;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface InterviewStoryRepository extends JpaRepository<InterviewStory, UUID> {

    List<InterviewStory> findAllByOrderByCreatedAtDesc();
}
