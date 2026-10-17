package com.aitome.access;

import jakarta.persistence.*;

/** 单行版本号：所有角色/权限/分配写操作以它串行化并做乐观并发校验。 */
@Entity
@Table(name = "access_control_state")
public class AccessControlState {
    @Id private Short id;
    @Column(nullable = false) private long version;
    protected AccessControlState() {}
}
