package com.aitome.vps;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;

@Entity
@Table(name="vps_recommendations")
public class VpsRecommendation {
    @Id @GeneratedValue(strategy=GenerationType.IDENTITY) private Long id;
    @Column(nullable=false,length=60) private String provider;
    @Column(nullable=false,length=80) private String planName;
    @Column(nullable=false,length=50) private String region;
    @Column(nullable=false) private int cpu;
    @Column(nullable=false) private int memoryGb;
    @Column(nullable=false) private int storageGb;
    @Column(nullable=false,precision=10,scale=2) private BigDecimal monthlyPrice;
    @Column(nullable=false) private double score;
    @Column(nullable=false,length=300) private String description;
    @Column(nullable=false,length=40) private String authorName;
    @Column(nullable=false) private Instant createdAt=Instant.now();
    protected VpsRecommendation(){}
    public VpsRecommendation(String provider,String planName,String region,int cpu,int memoryGb,int storageGb,BigDecimal monthlyPrice,double score,String description,String authorName){this.provider=provider;this.planName=planName;this.region=region;this.cpu=cpu;this.memoryGb=memoryGb;this.storageGb=storageGb;this.monthlyPrice=monthlyPrice;this.score=score;this.description=description;this.authorName=authorName;}
    public Long getId(){return id;} public String getProvider(){return provider;} public String getPlanName(){return planName;} public String getRegion(){return region;}
    public int getCpu(){return cpu;} public int getMemoryGb(){return memoryGb;} public int getStorageGb(){return storageGb;} public BigDecimal getMonthlyPrice(){return monthlyPrice;}
    public double getScore(){return score;} public String getDescription(){return description;} public String getAuthorName(){return authorName;} public Instant getCreatedAt(){return createdAt;}
}
