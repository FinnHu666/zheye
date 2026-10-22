package com.aitome.content;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface ArticleDraftRepository extends JpaRepository<ArticleDraft, Long> {
    Optional<ArticleDraft> findByPostId(Long postId);

    /** 契约 §3.0 锁序的第一把锁：草稿行。 */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select d from ArticleDraft d where d.id = :id")
    Optional<ArticleDraft> lockById(@Param("id") Long id);

    List<ArticleDraft> findByAuthorIdOrderByUpdatedAtDescIdDesc(Long authorId);
}
