package com.wedding;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class WeddingPaiApplication {

    public static void main(String[] args) throws Exception {
        if(args.length==1 && "--migrate".equals(args[0])) { DatabaseMigration.run(); return; }
        SpringApplication.run(WeddingPaiApplication.class, args);
    }

}
