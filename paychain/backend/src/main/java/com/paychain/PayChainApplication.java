package com.paychain;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class PayChainApplication {

    public static void main(String[] args) {
        SpringApplication.run(PayChainApplication.class, args);
    }
}
