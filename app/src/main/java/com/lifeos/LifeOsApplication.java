package com.lifeos;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication(excludeName = {
        "org.springframework.boot.autoconfigure.data.jdbc.JdbcRepositoriesAutoConfiguration"
})
@EnableScheduling
public class LifeOsApplication {
    public static void main(String[] args) {
        SpringApplication.run(LifeOsApplication.class, args);
    }
}
