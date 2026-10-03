package com.aitome.vps;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
public interface VpsRepository extends JpaRepository<VpsRecommendation,Long>{List<VpsRecommendation> findTop12ByOrderByScoreDesc();}
