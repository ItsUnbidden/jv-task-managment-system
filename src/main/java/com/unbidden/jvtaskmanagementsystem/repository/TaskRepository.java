package com.unbidden.jvtaskmanagementsystem.repository;

import com.unbidden.jvtaskmanagementsystem.model.Task;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.lang.NonNull;

public interface TaskRepository extends JpaRepository<Task, Long> {
    @NonNull
    @EntityGraph(attributePaths = {"project", "assignee"})
    Optional<Task> findById(@NonNull Long id);

    @NonNull
    @EntityGraph(attributePaths = {"project", "assignee"})
    List<Task> findByAssigneeId(@NonNull Long assigneeId, Pageable pageable);

    @NonNull
    @EntityGraph(attributePaths = {"project", "assignee"})
    List<Task> findByProjectId(@NonNull Long projectId, Pageable pageable);

    @NonNull
    @EntityGraph(attributePaths = {"project", "assignee"})
    @Query("from Task t left join fetch t.project p left join fetch t.assignee a "
            + "where a.id = :assigneeId and p.id = :projectId")
    List<Task> findByAssigneeIdAndByProjectId(@NonNull Long assigneeId, 
            @NonNull Long projectId, Pageable pageable);
}
