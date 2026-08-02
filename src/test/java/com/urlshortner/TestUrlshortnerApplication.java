package com.urlshortner;

import org.springframework.boot.SpringApplication;

public class TestUrlshortnerApplication {

    public static void main(String[] args) {
        SpringApplication.from(UrlshortnerApplication::main).with(TestcontainersConfiguration.class).run(args);
    }

}
