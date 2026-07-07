package com.bosi.ykt;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@MapperScan("com.bosi.ykt.mapper")
@EnableAsync
@EnableScheduling
public class YktApplication {
    public static void main(String[] args) {
        SpringApplication.run(YktApplication.class, args);
    }
}
