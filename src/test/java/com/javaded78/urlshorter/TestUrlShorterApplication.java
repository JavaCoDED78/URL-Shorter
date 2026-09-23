package com.javaded78.urlshorter;

import org.springframework.boot.SpringApplication;

public class TestUrlShorterApplication {

	public static void main(String[] args) {
		SpringApplication.from(UrlShorterApplication::main).with(TestcontainersConfiguration.class).run(args);
	}

}
