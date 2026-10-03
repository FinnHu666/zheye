package com.aitome.config;

import com.aitome.content.*;
import com.aitome.user.*;
import com.aitome.vps.*;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import java.math.BigDecimal;

@Configuration
public class DemoData {
    @Bean @Profile("!postgres") CommandLineRunner seed(UserRepository users,ContentPostRepository posts,CollectionRepository collections,VpsRepository vps,BCryptPasswordEncoder encoder,CollectionPublicationService publications){
        return args->{
            if(users.count()>0)return;
            UserAccount user=users.save(new UserAccount("demo@aitome.dev",encoder.encode("aitome123"),"纸飞机"));
            posts.save(new ContentPost(ContentPost.Type.ARTICLE,"把 AI 变成可靠的结对开发者","从上下文、任务拆分到验收闭环，一套能复用的 AI 协作方法。","# 从规格开始\n\n真正高效的 AI 开发，不是一次生成更多代码，而是让每一步都有清晰输入与可验证输出。\n\n## 三个原则\n\n1. 小步垂直切片\n2. 契约先行\n3. 每次改动都能测试",user.getId(),user.getDisplayName()));
            posts.save(new ContentPost(ContentPost.Type.TOPIC,"你在生产环境中如何选择 Java LTS？","17、21 还是更激进的新版本？欢迎分享迁移成本与收益。","聊聊你所在团队的版本选择，以及最让你意外的兼容性问题。",user.getId(),user.getDisplayName()));
            posts.save(new ContentPost(ContentPost.Type.ARTICLE,"轻量社区的缓存设计笔记","从读多写少出发，避免缓存成为第二套数据库。","热门内容采用短 TTL，计数聚合走异步，数据库仍是事实来源。",user.getId(),user.getDisplayName()));
            collections.save(new CommunityCollection("AI 工程实践","把提示词、评测、代理工作流与真实工程经验放在一起。",user.getDisplayName(),12,user.getId()));
            collections.save(new CommunityCollection("独立开发手册","从第一个提交到部署、运营和成本优化。",user.getDisplayName(),8,user.getId()));
            collections.findAll().forEach(c -> publications.publishSnapshot(c.getId()));
            vps.save(new VpsRecommendation("Cloudway","Starter 2G","新加坡",2,2,50,new BigDecimal("8.00"),4.7,"延迟稳定，适合轻量 Java 服务与个人站点。",user.getDisplayName()));
            vps.save(new VpsRecommendation("NorthNode","Builder 4G","东京",2,4,80,new BigDecimal("12.00"),4.5,"NVMe 存储，构建速度快，月流量充足。",user.getDisplayName()));
            vps.save(new VpsRecommendation("ByteHarbor","Micro","法兰克福",1,1,25,new BigDecimal("4.50"),4.2,"适合作为低成本监控节点或静态站。",user.getDisplayName()));
        };};
}
