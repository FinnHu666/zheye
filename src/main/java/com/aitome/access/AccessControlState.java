package com.aitome.access;

import jakarta.persistence.*;

/** 单行版本号：所有角色/权限/分配写操作以它串行化并做乐观并发校验。 */
@Entity
@Table(name = "access_control_state")
public class AccessControlState {
    @Id private Short id;
    @Column(nullable = false) private long version;
    /** 唯一站长（FINNS-16）。空表示尚未初始化；只经转交事务或启动初始化变更。 */
    @Column(name = "owner_user_id") private Long ownerUserId;
    @Column(name = "owner_version", nullable = false, columnDefinition = "bigint default 1 not null") private long ownerVersion = 1;
    protected AccessControlState() {}
}
