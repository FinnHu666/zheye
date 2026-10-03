package com.aitome.content;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.domain.Pageable;
import java.util.List;
public interface CommentRepository extends JpaRepository<Comment,Long> {
    List<Comment> findByPostIdOrderByCreatedAtAscIdAsc(Long postId, Pageable pageable);
    long countByPostId(Long postId);
}
