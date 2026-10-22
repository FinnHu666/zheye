package com.aitome.access;

import java.util.Arrays;
import java.util.Optional;

/**
 * FINNS-11 权限目录（契约 §1）。代码检查依赖这里的枚举；数据库只保存“角色拥有哪些权限”。
 * lockAll：所有角色必须拥有；lockAdmin：管理员角色必须拥有（防止锁死管理入口）；站长角色拥有全部且不可关闭。
 */
public enum Permission {
    CONTENT_PUBLIC("content.public", "页面", "浏览公开内容", "发现、专题广场、公开专题、VPS 指南", true, false, true),
    PROFILE_MANAGE("profile.manage", "页面", "个人资料与我的内容", "登录、退出、个人资料始终可用", true, false, false),
    SELECTION_MANAGE("selection.manage", "操作", "自选专题", "加入、移除、排序自选", false, false, false),
    POST_CREATE("post.create", "操作", "发布文章", "“开始创作”入口与发布接口", false, false, false),
    POST_INTERACT("post.interact", "操作", "点赞、讨论、收录", "内容详情中的互动", false, false, false),
    COLLECTION_MANAGE("collection.manage", "页面", "创建和编辑专题", "我创建的专题、专题编辑、导入", false, false, false),
    AI_USE("ai.use", "页面", "使用 AI", "AI 配置、AI 创建专题", false, false, false),
    CONTENT_RESTRICTED("content.restricted", "内容", "阅读受限专题", "列表、总数、详情、直达、自选", false, false, false),
    ADMIN_USERS("admin.users", "管理", "用户与角色分配", "查看用户、调整用户角色", false, true, false),
    ADMIN_AUDIT("admin.audit", "管理", "查看操作记录", "筛选、查询角色与权限变更", false, true, false),
    ADMIN_ROLES("admin.roles", "管理", "管理角色", "修改权限，新增、改名、删除角色", false, true, false),
    /** FINNS-17：独立于用户管理的内容审批权限；默认仅站长拥有，其他角色手动授予。 */
    CONTENT_REVIEW("content.review", "管理", "内容审批", "审批待审内容、下架与恢复；不含敏感规则维护", false, false, false);

    private final String code, group, label, detail;
    private final boolean lockAll, lockAdmin, guest;

    Permission(String code, String group, String label, String detail, boolean lockAll, boolean lockAdmin, boolean guest) {
        this.code = code; this.group = group; this.label = label; this.detail = detail;
        this.lockAll = lockAll; this.lockAdmin = lockAdmin; this.guest = guest;
    }

    public String code() { return code; }
    public String group() { return group; }
    public String label() { return label; }
    public String detail() { return detail; }
    public boolean lockAll() { return lockAll; }
    public boolean lockAdmin() { return lockAdmin; }
    public boolean guest() { return guest; }

    /** 该权限在给定角色上是否不可关闭。 */
    public boolean lockedFor(String systemKind) { return lockAll || Role.KIND_OWNER.equals(systemKind) || (lockAdmin && Role.KIND_ADMIN.equals(systemKind)); }

    public static Optional<Permission> fromCode(String code) {
        return Arrays.stream(values()).filter(p -> p.code.equals(code)).findFirst();
    }
}
