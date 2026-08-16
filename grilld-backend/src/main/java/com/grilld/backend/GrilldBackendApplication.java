package com.grilld.backend;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

// Powers GenerationResumeSweep's @Scheduled staleness check (§10.5).
@EnableScheduling
@SpringBootApplication
public class GrilldBackendApplication {

	public static void main(String[] args) {
		SpringApplication.run(GrilldBackendApplication.class, args);
	}

}
