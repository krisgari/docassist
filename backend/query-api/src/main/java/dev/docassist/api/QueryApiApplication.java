package dev.docassist.api;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication(scanBasePackages = "dev.docassist")
public class QueryApiApplication {
    public static void main(String[] args) {
        SpringApplication.run(QueryApiApplication.class, args);
    }
}
