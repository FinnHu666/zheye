package com.aitome.account;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

@Configuration
public class AccountConfig {
    /** 账号流程的时间来源；测试可用 @Primary 的可调时钟替换以验证冷却与过期。 */
    @Bean
    Clock accountClock() { return Clock.systemUTC(); }
}
