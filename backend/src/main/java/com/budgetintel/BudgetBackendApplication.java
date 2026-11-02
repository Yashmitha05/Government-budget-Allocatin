package com.budgetintel;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class BudgetBackendApplication {

    public static void main(String[] args) {
        SpringApplication.run(BudgetBackendApplication.class, args);
    }
}
