package com.aitome.content;

import com.aitome.common.ApiExceptionHandler.ApiProblem;
import org.springframework.http.HttpStatus;
import java.util.List;

public final class CollectionTemplates {
    private CollectionTemplates() {}
    public record Template(String key, String title, String example, List<String> sections) {}
    public static final List<Template> ALL = List.of(
        new Template("RENTING", "租房经验", "例如：看房时检查采光、噪音和设施，记录自己的检查结果。", List.of("我的需求与预算", "寻找房源", "看房检查", "签约", "入住", "经验复盘")),
        new Template("TUTORIAL", "操作教程", "填写适用条件、实际步骤、遇到的问题以及最后核验日期。", List.of("适用条件与核验日期", "准备事项", "操作步骤", "常见问题", "参考来源")),
        new Template("EXERCISE", "锻炼计划", "填写自己的目标、每周可用时间和安排，执行后记录体验。", List.of("目标与条件", "每周安排", "动作说明", "执行记录", "阶段复盘")),
        new Template("RETROSPECTIVE", "经验总结", "例如：我尝试了什么、结果如何、哪些做法下次可以复用。", List.of("背景", "目标", "做法", "结果", "踩坑", "下次改进")),
        new Template("BLANK", "空白专题", "从一个章节开始，随时添加或删除。", List.of("开始记录")));
    public static List<String> sections(String key) {
        return ALL.stream().filter(t -> t.key().equals(key)).findFirst().orElseThrow(() -> new ApiProblem(HttpStatus.BAD_REQUEST, "模板不存在")).sections();
    }
}
