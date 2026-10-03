package com.aitome.content;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name="community_collections", indexes = @Index(name="idx_collection_updated", columnList="updatedAt,id"))
public class CommunityCollection {
    public enum Format { GUIDE, READING_PATH, RESOURCE_LIST, CUSTOM }
    public enum Status { DRAFT, PUBLISHED, ARCHIVED }
    @Id @GeneratedValue(strategy=GenerationType.IDENTITY) private Long id;
    @Column(nullable=false,length=80) private String name;
    @Column(nullable=false,length=260) private String description;
    @Column(nullable=false,length=40) private String ownerName;
    @Column(name="owner_id") private Long ownerId;
    @Column(nullable=false) private int itemCount;
    @Column(nullable=false) private Instant createdAt=Instant.now();
    @Column(nullable=false) private Instant updatedAt=Instant.now();
    @Enumerated(EnumType.STRING) @Column(nullable=false,length=24) private Format format=Format.CUSTOM;
    @Enumerated(EnumType.STRING) @Column(nullable=false,length=16) private Status status=Status.PUBLISHED;
    @Column(nullable=false,length=160) private String audience="";
    @Column(nullable=false,length=300) private String goal="";
    @Column(nullable=false) private long revision=0;
    private Instant publishedAt;
    protected CommunityCollection(){}
    public CommunityCollection(String name,String description,String ownerName,int itemCount){this(name,description,ownerName,itemCount,null);}
    public CommunityCollection(String name,String description,String ownerName,int itemCount,Long ownerId){this.name=name;this.description=description;this.ownerName=ownerName;this.itemCount=itemCount;this.ownerId=ownerId;}
    public CommunityCollection(String name,String description,String ownerName,Long ownerId,Format format,Status status,String audience,String goal){
        this.name=name;this.description=description;this.ownerName=ownerName;this.ownerId=ownerId;this.itemCount=0;
        this.format=format;this.status=status;this.audience=audience;this.goal=goal;
        if(status==Status.PUBLISHED)this.publishedAt=Instant.now();
    }
    public Long getId(){return id;} public String getName(){return name;} public String getDescription(){return description;}
    public String getOwnerName(){return ownerName;} public Long getOwnerId(){return ownerId;} public int getItemCount(){return itemCount;} public Instant getCreatedAt(){return createdAt;} public Instant getUpdatedAt(){return updatedAt;}
    public Format getFormat(){return format;} public Status getStatus(){return status;} public String getAudience(){return audience;} public String getGoal(){return goal;} public long getRevision(){return revision;} public Instant getPublishedAt(){return publishedAt;}
    public void touch(){this.updatedAt=Instant.now();}
}
