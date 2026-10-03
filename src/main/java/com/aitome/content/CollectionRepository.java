package com.aitome.content;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import org.springframework.data.domain.Pageable;
public interface CollectionRepository extends JpaRepository<CommunityCollection,Long>{
    List<CommunityCollection> findTop8ByOrderByCreatedAtDesc();
    List<CommunityCollection> findAllByOrderByUpdatedAtDesc(Pageable pageable);
    List<CommunityCollection> findByOwnerIdOrderByUpdatedAtDesc(Long ownerId, Pageable pageable);
    long countByOwnerId(Long ownerId);
}
