package com.aitome.content;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Page;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.util.List;
public interface ContentPostRepository extends JpaRepository<ContentPost,Long>{
    @Query("select new com.aitome.content.ContentPostSummary(p.id, p.type, p.title, p.summary, p.authorName, p.createdAt, p.likes) " +
            "from ContentPost p order by p.createdAt desc, p.id desc")
    Page<ContentPostSummary> findAllSummaries(Pageable pageable);

    @Query("select new com.aitome.content.ContentPostSummary(p.id, p.type, p.title, p.summary, p.authorName, p.createdAt, p.likes) " +
            "from ContentPost p where p.type = :type order by p.createdAt desc, p.id desc")
    Page<ContentPostSummary> findSummariesByType(@Param("type") ContentPost.Type type, Pageable pageable);

    @Query("select new com.aitome.content.ContentPostSummary(p.id, p.type, p.title, p.summary, p.authorName, p.createdAt, p.likes) " +
            "from ContentPost p where p.authorId = :authorId order by p.createdAt desc, p.id desc")
    Page<ContentPostSummary> findSummariesByAuthorId(@Param("authorId") Long authorId, Pageable pageable);

    Page<ContentPost> findAllByOrderByCreatedAtDescIdDesc(Pageable pageable);
    Page<ContentPost> findByTypeOrderByCreatedAtDescIdDesc(ContentPost.Type type, Pageable pageable);
    List<ContentPost> findByAuthorIdOrderByCreatedAtDesc(Long authorId, Pageable pageable);
    List<ContentPost> findByAuthorIdOrderByCreatedAtDescIdDesc(Long authorId, Pageable pageable);
    long countByAuthorId(Long authorId);
}
